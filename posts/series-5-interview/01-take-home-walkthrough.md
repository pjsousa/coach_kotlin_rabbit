# How to Walk Through a Take-Home System

The take-home challenge is not scored by the code sitting in a repository. It is scored by what you can make an interviewer understand in forty-five minutes: why the system exists, how it is put together, and what happens when things fail. This post gives you a rehearsable walkthrough structure for the pharmacy challenge, built around the four things the challenge actually assesses — user experience, simplicity, system design, and failure handling.

## The interviewer's lens

Before planning your narration, understand the constraints on the person listening to you. The interviewer has almost certainly read your README and skimmed the code before you join. They have not run the system, they have not seen the patient flow the way you have, and they have a limited window. What they are doing during your walkthrough is checking a single hypothesis: *does the person who built this understand the user, the design, and the failure modes well enough to own it in production?*

That changes how you speak. You are not doing a code tour — you are making a series of claims about patient experience, design choices, and failure behavior, and then pointing at concrete evidence in the code for each claim. The structure below maps one-to-one onto the challenge's assessment criteria, in the order that makes the strongest impression: the patient first, infrastructure last.

## 1. Start with the patient, not the RabbitMQ topology

Open with a two-minute statement of what the system does for the person it exists for: the patient in the waiting room. Say the domain in plain words first — prescription submitted, inventory verified, pharmacist approves, packaging completes, patient's name is called — and only then say what you built to make that visible.

A strong opening takes about ninety seconds:

> "The core user is a patient in the waiting room. They hand in a prescription, and from that moment they need to know what is happening: submitted, approved, being packaged, ready. The pharmacy's own assessment is that patients must be convinced first — pharmacists and packagers can tolerate inconvenience. So the system is built around one synchronous question — 'what is the status of my prescription?' — with every other actor simulated as a worker. Pharmacists and packagers are modelled as automated workers consuming work queues, because building internal UIs for them would eat the time budget without improving the assessed experience."

This does several things at once: it shows you read the brief, it demonstrates product judgment, and it pre-answers the most likely first question ("why didn't you build a pharmacist UI?"). Notice the reasoning is anchored to the challenge's own framing, not to taste. That is the standard for every claim that follows.

## 2. Architecture in one minute

Now give the shape of the system, but only at the level of components and one-hop dependencies. A diagram in words, five or six components, each with a single sentence of responsibility:

- **REST API (Spring Boot, Kotlin)** — prescription submission, status lookup, and the actions a staff member would take: approve, reject, mark packaged.
- **PostgreSQL** — the source of truth: prescriptions, inventory, status history, plus the outbox table that makes state changes durable before anything is published.
- **Outbox relay** — a poller that reads committed outbox rows and publishes them to RabbitMQ with publisher confirms.
- **RabbitMQ** — two separated concerns: work queues for staff tasks (approval, packaging) and a fan-out of status events for patient notification.
- **Workers** — simulated pharmacist and packager consumers that process commands with manual acknowledgements.
- **SSE endpoint** — patient-specific status streams fed from the status history, one stream per patient, not one consumer per patient (more on that below).

State the one dependency direction that matters: *every durable decision is written to PostgreSQL in a transaction first; RabbitMQ is how work moves afterwards, and patient notification is derived from the same status history.* If you can express that in one sentence, you have already defused the dual-write failure mode before the interviewer asks about it.

## 3. Data invariants: what the database guarantees

This section is where you prove the workflow is safe under concurrent use. Do not read out schema. Instead, state each invariant as a claim and show the mechanism that enforces it.

### The state machine is constrained

Prescription status is a typed state machine, and illegal transitions are rejected by the code, not just by convention:

```kotlin
enum class PrescriptionStatus { SUBMITTED, INVENTORY_VERIFIED, APPROVED, REJECTED, PACKAGING, FULFILLED }

fun canTransition(from: PrescriptionStatus, to: PrescriptionStatus): Boolean = when (from) {
    SUBMITTED -> to == INVENTORY_VERIFIED || to == REJECTED
    INVENTORY_VERIFIED -> to == APPROVED || to == REJECTED
    APPROVED -> to == PACKAGING
    PACKAGING -> to == FULFILLED
    REJECTED, FULFILLED -> false
}
```

The database backs this up with a conditional update that returns the number of affected rows — the classic guard against two concurrent actions both thinking they moved the prescription:

```sql
UPDATE prescriptions
   SET status = :to, updated_at = now()
 WHERE id = :id
   AND status = :from
```

In the service you check `updateCount == 1`; if it is zero, the transition was not legal at that moment, and you return the appropriate error. This is a one-line explanation of a concurrency problem that intimidates many candidates, and it takes you ten seconds to deliver.

### Inventory cannot oversell

Reservation is an atomic decrement against available stock, inside the same transaction that records the prescription:

```sql
UPDATE medication_inventory
   SET reserved = reserved + 1
 WHERE medication_id = :medicationId
   AND stock - reserved >= 1
```

Again, affected rows decide the outcome. If the update matches zero rows, the prescription is rejected for insufficient stock — no separate read-then-write, no race window. Lead-level interviewers will ask "what if two orders arrive for the last unit?" and this is the exact mechanism that answers it.

### Status history is append-only

Every transition inserts a row into `status_history` with a monotonic sequence. This single decision powers three later features: the synchronous status endpoint, SSE replay after reconnect, and auditability. When you mention it here, you are planting the seed for the SSE section — the interviewer will recognize that you designed for replay rather than bolting it on.

## 4. Messaging semantics: say exactly what your system does and does not guarantee

This is the section where vague messaging language gets exposed, so be precise. Deliver these four claims, each paired with its mechanism:

**Delivery is at-least-once, and the system tolerates duplicates.** The outbox ensures the event exists only after the database commit; publisher confirms tell the relay the broker accepted it; the relay may still republish after a crash, and a consumer may receive a message twice after a redelivery. Duplicates are absorbed by the inbox pattern: a unique constraint on the event ID in PostgreSQL, and the consumer only applies business effects once. Say it as a complete sentence: *"The system is designed for at-least-once delivery; duplicates are possible and are handled by an idempotent inbox, so I never claim exactly-once."*

**Acknowledgement happens after the business effect is durable.** The consumer does its work in a PostgreSQL transaction, inserts the inbox row, commits, and only then sends `basicAck`. If it crashes before the ack, the message is redelivered, the inbox rejects the duplicate, and nothing is double-applied. Walk this crash window deliberately — it is the single most convincing demonstration of failure-handling understanding.

**Transient failures retry; permanent failures dead-letter.** Work that fails with a retryable error (e.g. a temporary database hiccup) goes through a bounded retry queue with a TTL; a message that exceeds the retry limit is dead-lettered to a poison queue where it can be inspected and safely replayed. Keep the description to two sentences; the interviewer can dig deeper if they want.

**Ordering is scoped per prescription, not global.** Messages for one prescription are sequenced and processed one at a time by keying the queue on the prescription ID; different prescriptions run in parallel. You guarantee ordering for the unit that matters to the patient — their own prescription — and you get throughput for everything else. This precise scoping is far stronger than the vague "we guarantee ordering."

One more claim worth making explicitly, because the assessment flagged it: *patient SSE streams are not RabbitMQ consumers.* Each patient connection reads from the status history via the API; RabbitMQ broadcasts status events once, and the SSE endpoint is an application-level projection, not a competing consumer. One subscription for all patients, filtered server-side per connection — that is how you avoid N consumers on a work queue and how you keep patient data isolated.

## 5. Tests: prove, don't describe

By this point you have made claims about concurrency, crash windows, and replay. Now show what proves them. Group your tests by what they demonstrate, not by file layout:

- **State machine tests** — every legal and illegal transition, exhaustively, as pure unit tests.
- **PostgreSQL integration tests (Testcontainers)** — inventory reservation under two concurrent requests for the last unit; the conditional-update race; the inbox unique-constraint absorbing a duplicate event. These run against real PostgreSQL because an in-memory fake cannot prove row-locking behavior.
- **RabbitMQ integration tests (Testcontainers)** — redelivery after a crash-before-ack, retry TTL, dead-letter arrival, duplicate publication through the outbox relay.
- **End-to-end test** — one scripted patient journey: submit, verify, approve, package, fulfil, and observe the status transitions, including the final "your name is being called" event.
- **SSE tests** — reconnect with `Last-Event-ID` resumes from the correct sequence; concurrent patients on different streams see only their own events.

The closing claim matters as much as the list: *"the failure tests are the ones that would catch a production regression, which is why I prioritised them over covering every happy-path branch."* That sentence shows you treat tests as evidence for the assessed criteria, not as a completeness ritual.

## 6. Deliberate omissions: the most underrated section

The challenge explicitly says it prefers clean, working code over extra features, and that you may note what you would do next. A list of intentional cuts — with reasoning — is therefore a feature of the submission, not a confession. Say something like:

> "Within the two-to-five hour budget I deliberately left out: a real pharmacist and packager UI (the challenge treats those users as secondary, and simulated workers prove the workflow); authentication and multi-tenancy (out of scope, but the SSE design keeps event filtering server-side so authorization is a drop-in); Kubernetes deployment and cloud infrastructure (Docker Compose proves the system; deployment is operationalisation, not design); and any exactly-once claim, because RabbitMQ does not offer it and the inbox pattern is the honest answer."

Order this list by user value: the biggest product cut first, then the reliability cuts, then the operations cuts. Each omission should be accompanied by the one thing that would unlock it — the SSE authorization point is the strongest example, because it shows you designed the seam for the missing feature rather than ignoring it.

## The walkthrough as a rehearsal script

Here is the full shape to rehearse, with target durations. The total is about thirty minutes of talking, leaving time for questions:

| Section | Content | Target |
| --- | --- | --- |
| Patient first | User journey, what patients can observe at every step | 2 min |
| Architecture | Components, one-hop dependencies, where decisions become durable | 3 min |
| Data invariants | State machine, atomic transitions, inventory reservation, history | 5 min |
| Messaging | Outbox, confirms, ack timing, inbox, retries/DLQ, ordering scope | 7 min |
| SSE | Replay, ordering, isolation, not-a-consumer | 4 min |
| Tests | What is proven and against which real system | 4 min |
| Omissions | Intentional cuts with the seam that would unlock each | 3 min |

Two rehearsal rules. First, practice the first two sections until they are automatic — a confident opening sets the frame for everything that follows. Second, when the interviewer interrupts, answer the interruption immediately and fully, then return to the section you were in; never power through a question to finish a slide in your head.

## Interview takeaway

The winning walkthrough is not the one that shows the most code — it is the one that shows the most ownership. Start from the patient in the waiting room, state your invariants as claims backed by mechanisms, describe your messaging semantics with the exact scope of what you can and cannot guarantee, and close with the cuts you made on purpose. If an interviewer walks away able to answer "what happens when the packaging worker crashes?" in their own words, you have passed the walkthrough.
