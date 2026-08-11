# Designing the Minimum Patient-First API

The pharmacy challenge is judged on patient experience, simplicity, system design, and failure handling — in that order, and the API is where the first two are decided. The temptation is to start with the impressive surface: a dashboard, a chat window, an admin portal, a status page with sparklines. All of that is a tax on the 2-5 hours you actually have, and none of it is what the evaluator is scoring. What they are scoring is whether a patient can submit a prescription and see, at any moment, exactly where it is in the fulfillment workflow.

This post defines the smallest REST surface that proves that experience: a patient-facing read/write API, a staff-facing queue API for the simulated pharmacist, and worker commands that belong to RabbitMQ, not to HTTP. Everything else is deliberately omitted and defended as omitted.

## The Three Surfaces, Not One

A common failure is a single generic CRUD API where every resource exposes every verb. The pharmacy system has three distinct consumers with three distinct contracts, and mixing them produces an API that is safe for nobody:

| Consumer | Wants | Surface |
| --- | --- | --- |
| Patient | Submit, then *observe* | REST: `POST /prescriptions`, `GET /prescriptions/{id}` |
| Pharmacist | A work queue + two decisions | REST: queue fetch, approve, reject |
| Packaging/fulfillment workers | Work commands with reliability | RabbitMQ (series 3), not HTTP |

The patient surface is about facts: the patient reads a status that is the source of truth. The staff surface is about commands: the pharmacist acts on a prescription. Series 3 drew the same line in the messaging layer — work vs. facts — and the API draws it in exactly the same place. A packaging worker must never consume patient-facing REST endpoints, and a patient must never call an approval endpoint. The separation is not ceremony; it is how you keep the patient contract stable while the workflow internals change.

## The State Machine Is the Contract

Every endpoint maps to a legal transition of one state machine. The machine is deliberately flat:

```text
SUBMITTED -> APPROVED -> PACKAGING -> READY -> FULFILLED
    |
    +-----> REJECTED
```

| From | Action | To | Actor |
| --- | --- | --- | --- |
| SUBMITTED | approve | APPROVED | Pharmacist |
| SUBMITTED | reject | REJECTED | Pharmacist |
| APPROVED | start packaging (worker picks up command) | PACKAGING | Worker |
| PACKAGING | complete packaging | READY | Worker |
| READY | complete fulfillment | FULFILLED | Worker |

Rejection is only legal from SUBMITTED. Approval is only legal from SUBMITTED. Once READY, nothing may be rejected. If the endpoint accepts the action but the state does not, the answer is `409 Conflict`, not a silent no-op and not a 500. The state machine from series 1 (sealed Kotlin types) is not an implementation detail; it *is* the API contract, because the transitions it forbids are the ones a patient's medication safety depends on.

## The Patient-Facing Surface: Two Endpoints

The entire patient experience reduces to two calls:

```kotlin
@RestController
@RequestMapping("/prescriptions")
class PrescriptionController(
    private val submissions: PrescriptionSubmission,
    private val lookup: PrescriptionLookup,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun submit(@Valid @RequestBody request: SubmitPrescriptionRequest): PrescriptionView =
        submissions.submit(request)

    @GetMapping("/{prescriptionId}")
    fun status(@PathVariable prescriptionId: UUID): PrescriptionView =
        lookup.byId(prescriptionId)
}
```

Submission returns the full current view (status, timeline, medications) so the patient's first screen never needs a second round trip. The request carries the prescription lines; the response carries `prescriptionId` and the initial status:

```json
{
  "prescriptionId": "5f1c1f2e-8a0e-4b2a-9c3d-7a2e1b0c4d5e",
  "status": "SUBMITTED",
  "medicationLines": [ { "medicationId": "m-104", "quantity": 30 } ],
  "submittedAt": "2026-08-11T09:14:00Z",
  "timeline": [
    { "status": "SUBMITTED", "occurredAt": "2026-08-11T09:14:00Z" }
  ]
}
```

Two endpoints. That is the whole patient API, before SSE. Everything the patient needs to know is answered by the second call, and everything they can do is the first.

### Why `GET /prescriptions/{id}` Is the Correctness Baseline

Every asynchronous mechanism in this system — RabbitMQ work queues, outbox relays, and later SSE — exists to move the workflow forward and to deliver its results. The one endpoint that cannot lie is the status GET, because it reads the persistent state directly, not a notification channel. Its correctness is the product's correctness:

- **It converges.** No matter how many retries, dead letters, or reconnects happen, the patient can poll and see the truth. If the workflow is correct, GET is correct. If GET is wrong, every fancier channel is wrong with extra machinery attached.
- **It is testable.** A GET is deterministic, idempotent, and trivially assertable in an end-to-end test: submit, approve, wait for packaging, GET, assert `READY`.
- **It is the SSE contract.** When the SSE post adds realtime delivery, the stream will replay the same timeline with the same sequence numbers. SSE becomes a delivery optimization over an already-correct baseline, never a separate source of truth. An interviewer who asks "how do you know the patient sees the truth?" should hear "the GET is the truth; SSE just reduces the polling delay."

Build the baseline first, prove it with tests, and treat SSE as an enhancement you can cut without breaking the product — because the 2-hour version of the challenge has to be exactly that.

### Submitting Without Duplicates

Submission is the one patient action with a real failure mode: network retries. If the patient's app times out and resends, the system must not create two prescriptions for one therapy. The minimum fix is a client-generated idempotency key with a unique constraint:

```kotlin
data class SubmitPrescriptionRequest(
    val idempotencyKey: String,
    val patientId: UUID,
    val medicationLines: List<MedicationLine>,
)
```

```sql
CREATE UNIQUE INDEX prescriptions_idempotency_key_uniq
    ON prescriptions (idempotency_key);
```

A retried submission hits the unique index and returns the existing prescription with `200 OK` instead of creating a duplicate. This is the same at-least-once logic the RabbitMQ consumers use (series 3), applied at the HTTP boundary, and it is the cheapest possible version of a much bigger problem.

## The Staff-Facing Surface: A Queue, Not a UI

The pharmacist does not need a dashboard. The pharmacist needs the next thing to look at and a way to decide:

```kotlin
@RestController
@RequestMapping("/staff/pharmacist")
class PharmacistApi(
    private val pharmacistQueue: PharmacistQueue,
    private val approval: ApprovalService,
) {

    @GetMapping("/queue")
    fun queue(@RequestParam(defaultValue = "20") limit: Int): List<PrescriptionView> =
        pharmacistQueue.pending(limit)

    @PostMapping("/prescriptions/{prescriptionId}/approval")
    fun approve(@PathVariable prescriptionId: UUID): PrescriptionView =
        approval.approve(prescriptionId)

    @PostMapping("/prescriptions/{prescriptionId}/rejection")
    fun reject(
        @PathVariable prescriptionId: UUID,
        @RequestBody reason: RejectionReason,
    ): PrescriptionView = approval.reject(prescriptionId, reason)
}
```

The queue endpoint is a query over pending state, not a message stream; approval and rejection are state transitions that return the new view. That is the whole pharmacist UI — and it is deliberately not a UI at all. A simulated pharmacist is a script that polls the queue and posts decisions, which makes the workflow demonstrable with three curl commands and testable end to end. No websockets, no admin portal, no approval workflow UI with buttons and toasts.

The packaging and fulfillment workers sit even further from HTTP. Their commands travel over the RabbitMQ work queues from series 3, and their completion is a database transaction that publishes the next fact through the outbox. If a worker completes work through a REST endpoint instead, the system gains a pointless synchronous hop and loses the retry and dead-letter topology it already paid for. The simulated worker is a small consumer client, not an HTTP client.

## Error Contracts: Four Answers

A good API answers four questions with four status codes:

| Situation | Status | Example |
| --- | --- | --- |
| Validation failure | `400` | Negative quantity, empty medication list |
| Missing resource | `404` | Unknown prescription ID |
| Illegal transition or inventory conflict | `409` | Rejecting an approved prescription; no stock left |
| System failure | `503` | Database unreachable (no stack traces, ever) |

Bodies use the Problem Details shape (`application/problem+json`) so clients and logs parse failures identically:

```json
{
  "type": "https://api.example.com/errors/illegal-transition",
  "title": "Illegal transition",
  "status": 409,
  "detail": "Prescription 5f1c1f2e... is already fulfilled and cannot be rejected.",
  "instance": "/staff/pharmacist/prescriptions/5f1c1f2e.../rejection"
}
```

The `409` is the endpoint that proves you understood the state machine. The approval service must be an atomic conditional transition (series 2), so two pharmacists clicking at once cannot both approve, and neither can approve a prescription that was already rejected. A reviewable API returns the conflict as a contract; a sloppy API returns it as a mystery.

## What Is Deliberately Omitted

Half of API design is saying no. For this challenge, the defensible omission list is part of the submission:

- **Authentication service.** A documented `X-Patient-Id` (and `X-Pharmacist-Id`) header stands in for auth, with the README saying exactly where a real identity layer would slot in. Building OAuth for a 4-hour exercise is scope suicide.
- **SSE and push notifications.** Deferred to the next post. The 2-hour slice ships with polling against the correctness baseline.
- **Staff UI and admin portal.** Simulated staff are scripts. A generated UI follows backend proof, never precedes it.
- **Resource versioning, ETags, and conditional requests.** Idempotency keys cover the one dangerous retry; versioning a brand-new API is architecture theater.
- **Pagination everywhere.** Only the pharmacist queue needs a `limit`, because it is the only unbounded list.
- **Webhooks, web sockets, GraphQL.** The workflow is a state machine; REST matches it with the fewest moving parts.
- **A `/health` endpoint beyond a plain liveness check.** Do not build operational dashboards into the API surface when the README can carry the architecture diagram.

Each omission is a sentence in the README with a one-line justification. That documentation *is* the scope defense in the interview: you are showing that the omissions were decisions, not oversights.

## The Two-Hour Slice Fits in One Screen

With this surface, the whole system — before RabbitMQ reliability work — is: submit, poll status, pharmacist approves, workers advance state, patient sees `FULFILLED`. Every piece is a single HTTP call or a single transaction, and the end-to-end test is a sequence of API calls with status assertions between them. That is the product slice a Product Engineer can defend: the patient journey is complete, truthful, and observable, and nothing in it required a single line of frontend code.

## Interview Review Checklist

Before walking through this design, be able to answer:

- Which three consumers does the system have, and why should each get a different API contract?
- Why is the status GET the correctness baseline, and what does SSE change about it — if anything?
- How does the submission endpoint prevent duplicate prescriptions from a retried request?
- Why does the pharmacist get a queue query instead of a UI, and what does the simulated pharmacist actually run?
- Why do packaging and fulfillment workers get commands from RabbitMQ instead of HTTP endpoints?
- Which transitions are illegal, and what status code enforces them atomically?
- What is in the Problem Details body, and why does the shape matter more than the wording?
- What did you deliberately omit, and what is the one-sentence justification for each omission?

## Interview Takeaway

The minimum patient-first API is two patient endpoints, three staff endpoints, a state machine that only the transitions may cross, and a short, honest list of omissions. The status GET is the product's truth: every reliability mechanism and every realtime channel either serves it or is verified against it. Design the surface so a simulated pharmacist is a script, workers consume commands from the broker, and the patient journey is provable with a sequence of curl commands — then the advanced work in the rest of this series (SSE correctness, reconnect and replay, and time-boxed scoping) is an enhancement to a baseline that already works, not a rewrite of one that never did.
