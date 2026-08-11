# From Prescription Submission to Patient Notification: A Defensible Product Slice

This post is the capstone of the product workflow series. The four posts before it each solved one slice of the challenge: the minimum patient-first API, SSE correctness, testing the realtime experience, and time-boxed scoping. This post unifies them into one realistic walkthrough of the pharmacy system — because in a Product Engineer interview you are never asked to defend a single endpoint or a single test. You are asked to defend a product slice: from the moment a patient submits a prescription to the moment they are notified it is fulfilled, and everything in between that could fail.

The structure of this post is the structure of your answer. It follows the build progression the series has preached from the start: a minimal patient-first API first, an asynchronous staff workflow on top of it, reliability and failure behavior only after correctness, and SSE added last — as a delivery optimization over a baseline that already works, never as a second source of truth. At each layer the post makes the scope decisions explicit, names the failure behavior, and ends with the live-walkthrough script you would actually speak. Read it as the dress rehearsal for the interview, not as a summary of four articles.

## The Scenario: One Prescription, One Patient, One Defensible Story

The system exists for one person: the patient. So the walkthrough starts with her.

Ana submits a prescription for a 30-day supply of amoxicillin at 09:14. For the next few minutes she wants to know, at any moment, exactly where her prescription is: submitted, approved, being packaged, ready, or fulfilled. The product slice is the journey that connects her submission to the notification that the work is done — and every technical decision in this series exists to make that journey *observable* and *truthful*.

```text
09:14:00  Ana submits prescription            -> status SUBMITTED
09:14:03  Ana's app: status GET              -> SUBMITTED
09:15:12  Pharmacist approves                -> APPROVED
09:15:20  Packaging worker picks up command  -> PACKAGING
09:16:04  Packaging worker completes         -> READY
09:16:31  Fulfillment worker completes       -> FULFILLED
09:16:31  Ana's SSE stream delivers          -> FULFILLED  (sequence 5)
```

Every row of that table is either a patient-visible fact, a staff action, or a worker action — and each maps to exactly one piece of the design. The state machine defines which rows are legal. The API exposes them to the right consumers. The asynchronous workflow moves the journey forward. The SSE layer delivers the last row the moment it exists. And when any of it fails, the failure behavior decides whether Ana ever sees a lie.

The evaluator's criteria — patient experience, simplicity, system design, failure handling — are four lenses on this one journey. The rest of this post walks the journey once per lens.

## Layer 1: The Journey Contract — A State Machine and a Truth-Telling GET

Before any code, the contract: the journey is a deliberately flat state machine, and it is the same machine everywhere — in the database, in the API, in the RabbitMQ messages, and in the SSE stream.

```text
SUBMITTED -> APPROVED -> PACKAGING -> READY -> FULFILLED
    |
    +-----> REJECTED
```

Every legal action is a transition owned by exactly one actor. Ana can only submit. The pharmacist can only approve or reject, and only from `SUBMITTED`. Workers advance `PACKAGING -> READY -> FULFILLED` as commands complete. There is no `DELETE /prescriptions`, no edit endpoint, no way to go backward — the machine forbids it, and the API returns `409 Conflict` when a client attempts an illegal transition. The state machine from series 1 (sealed Kotlin types, exhaustive `when`) is not an implementation detail; it *is* the API contract, because the transitions it forbids are the ones a patient's medication safety depends on.

The second half of the contract is the correctness baseline: `GET /prescriptions/{id}` reads the persistent state directly and returns the full view — status, medication lines, and the timeline of every transition that happened. Everything else in this system exists to make that view faster or to move the workflow that produces it. The GET converges no matter what happens: retries, dead letters, reconnects, server restarts. If the workflow is correct, the GET is correct. If the GET is wrong, every fancier channel is wrong with extra machinery attached.

That sentence — *the GET is the truth; SSE is a delivery optimization* — is the load-bearing claim of the entire series, and it is what makes every later layer cuttable. A two-hour submission with polling only is a *correct* product; it just is not instant. Keep the pair in mind: state machine and GET. Everything below layers on top of them.

## Layer 2: The Minimal Patient-First API (Series 4, Post 1)

The API has three distinct consumers with three distinct contracts, and the first design decision is to keep them separate:

| Consumer | Wants | Surface |
| --- | --- | --- |
| Patient | Submit, then observe | `POST /prescriptions`, `GET /prescriptions/{id}` |
| Pharmacist | A queue + two decisions | `GET /staff/pharmacist/queue`, approve, reject |
| Packaging/fulfillment workers | Work commands with reliability | RabbitMQ, not HTTP |

The entire patient surface is two endpoints. Submission returns the full current view so Ana's first screen never needs a second round trip; the status GET answers everything after that. The pharmacist gets a queue query and two state-transition endpoints — a simulated pharmacist is a script, not a dashboard. The workers consume commands from the broker (series 3), because a packaging worker that calls REST instead loses the retry and dead-letter topology the system already paid for.

Submission carries the one patient action with a real failure mode: network retries. If Ana's phone times out and resends, the system must not create two prescriptions for one therapy:

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

A retried submission hits the unique index and returns the existing prescription with `200 OK` — the same at-least-once logic the RabbitMQ consumers use, applied at the HTTP boundary.

Errors are four answers, consistently shaped: `400` validation, `404` missing, `409` illegal transition or inventory conflict, `503` system failure — in Problem Details form, so clients and logs parse failures identically. No stack traces over HTTP, ever.

That is the whole API, and it fits the walkthrough in one screen: submit, poll, pharmacist decides, workers advance, Ana sees `FULFILLED`. Before RabbitMQ reliability work, before SSE, the patient journey is already complete, truthful, and testable with a sequence of curl commands. Post 1's checklist question — *"why is the status GET the correctness baseline?"* — is answered by the fact that nothing in the rest of the design is allowed to contradict it.

## Layer 3: The Asynchronous Staff Workflow (Series 3 Meets the API)

The journey cannot be synchronous — a pharmacist is not waiting on Ana's HTTP call. Approval, packaging, and fulfillment are commands that travel over RabbitMQ, and series 3 established the topology that carries them. Two kinds of messaging exist in this system, and conflating them is the classic failure:

- **Work commands** — approve, package, fulfill — are competing consumers on dedicated work queues. Any available worker takes one command, does the durable work, acknowledges.
- **Facts** — statuses that already happened — are fanned out via `pharmacy.events` (topic exchange) to subscribers like the status projection. Facts are *not* competing consumers; every subscriber gets every fact, which is exactly what SSE will later need.

When the pharmacist approves Ana's prescription, the transaction that changes state also inserts a row into the outbox table (series 3, post 2). The outbox relay then publishes the event and receives a publisher confirm from the broker. That relay is the first layer of failure behavior: the dual-write window between "database committed" and "broker accepted" is closed by the outbox — the database is the only source of truth for *what happened*, and the broker only carries the announcement.

The workers on the other side follow the acknowledgement discipline from series 3, post 3: the durable business effect (the `PACKAGING -> READY` transition in PostgreSQL) happens inside a transaction, and *then* the worker acknowledges the delivery. An inbox table keyed on the event ID makes the consumer idempotent — a redelivered command is a no-op, not a second packaging run (series 3, post 5).

The walkthrough point: the staff workflow is invisible to Ana by design. She does not poll the pharmacist; the workflow polls itself through the queue. Her contract is only the state machine's visible effects — and the GET always reflects the last committed one.

## Layer 4: Failure Behavior — Every Crash Window Named and Handled

The challenge explicitly assesses failure handling, and this is where the series earns that credit: the system does not *hope* nothing fails, it names each crash window and gives it a recovery. Three windows cover the asynchronous path:

| Crash window | What can happen | The defense | What Ana sees |
| --- | --- | --- | --- |
| Commit → publish | Relay crashes after DB commit, before broker confirm | Outbox row survives; relay republishes on restart | Nothing — event arrives late, not lost |
| Publish → process | Duplicate delivery after a crash before ack | Inbox unique constraint; post-commit ack | Nothing — status deduped |
| Process → commit | Poison message or permanent failure | Retry queue with TTL, then dead-letter exchange | Status stuck at last committed state; DLQ inspectable and replayable |

Note the honest language: each row says *what can happen*, not "exactly-once delivery." The system is at-least-once end to end — which is why the idempotency layer is not optional, and why the series never claims otherwise (series 5 will rehearse saying exactly that sentence out loud).

The time-boxing post (series 4, post 4) makes the scope decision explicit: the two-hour slice does *not* build all of this. It documents the windows instead — *"messages can be lost between the database commit and broker publish; retries are unbounded requeues; the patient polls"* — and that documented gap is worth more than three hours of half-built reliability machinery. The five-hour version closes the windows in risk order: outbox and confirms first, then manual acks and inbox idempotency, then retries and DLQ, then SSE last. Every item on the list fixes a documented failure before it adds a feature.

This is the sentence to rehearse: *the failure behavior of the system is a table, and every row of the table is either closed by a mechanism or documented as a known limitation.* A Product Engineer interviewer is looking for exactly that — the ability to say what breaks, where it surfaces, and what would close it.

## Layer 5: SSE Only After Correctness (Series 4, Post 2)

Now, and only now, does the system go realtime. SSE is added after the baseline is correct and the workflow is reliable, because the SSE design *depends* on the layers below it:

- The event IDs SSE replays are the durable sequence numbers written by the projection consumer — which exist because the workflow publishes facts.
- The ordering SSE guarantees is the ordering the projection maintains per prescription — which exists because of the inbox discipline in series 3.
- The isolation SSE proves is the same isolation the GET already enforces per patient.

The architecture keeps SSE out of the broker entirely. Facts fan out to the status projection; the projection is a per-prescription, per-patient table of append-only rows with monotonic sequence numbers; the SSE layer is an in-process broadcaster that reads committed projection rows and pushes them to open connections. **SSE and GET are two readers of one source of truth** — the stream can never be ahead of the database, and the GET can always be trusted to be at least as fresh as the stream.

```text
pharmacy.events (topic exchange)
        |
        v
[pharmacy.notifications] queue --> status projection (single ordered consumer,
        |                            inbox + sequence numbers)
        v
  status_projection table (per-prescription, per-patient rows)
        |
        v
  SSE layer: reads projection, broadcasts to open connections
        |
        +--> Ana's EventSource      +--> another patient's EventSource
```

Ana's phone receives events with `id:` fields that are the projection's per-prescription sequence numbers — `1` SUBMITTED, `2` APPROVED, `3` PACKAGING, `4` READY, `5` FULFILLED. When her phone drops signal for thirty seconds, the browser's `EventSource` reconnects automatically with `Last-Event-ID: 3`, and the server replays sequences 4 and 5 from the projection. No gap, no duplicate, no replay of 3. That one protocol detail — the `id:`/`Last-Event-ID` pair — is the entire reconnect story, and it only works because the event IDs are durable projection sequence numbers, never server-generated UUIDs thrown away after send.

Ordering is per prescription, never a global clock. Prescription 42's events are numbered 1,2,3… and Ana's stream sees them in exactly that order, because the projection consumer applies each prescription's stream in order and the broadcaster is append-only. "Latest wins" is forbidden for a status stream — Ana needs to see the *steps*, not just the last status, and a delayed replay of `APPROVED` after `FULFILLED` would draw a backwards arrow on her screen. The projection is append-only history, which is also why the SSE stream and the GET's `timeline` array are literally the same rows.

Isolation is structural, not a filter. The broadcaster's emitter map is keyed by `patientId`, so a broadcast for Ana physically cannot reach another patient's connections. And the ownership check runs on both entry points — on connect and again inside the replay query — because an unauthorized replay is a *read* of another patient's history triggered by an ID the attacker should never have. The browser `EventSource` API cannot set custom headers, so in a real browser demo the patient identity arrives by cookie or short-lived token in the query string; the server-side authorization decision is identical either way.

## Layer 6: Proving the Realtime Layer (Series 4, Post 3)

The series rule that covers everything: a design that cannot be tested is a design that has not been finished. For SSE, the demo shows statuses arriving "live" — and it cannot show what happens on a 30-second signal loss, a server restart mid-broadcast, or two patients connected at once. The tests turn the demo's silence into assertions, and there are six of them plus one end-to-end:

1. **Reconnect with `Last-Event-ID`** — client sees 1,2,3, drops, events 4 and 5 happen while disconnected, reconnects with `Last-Event-ID: 3`, must receive exactly `[4, 5]`. The broadcast happens while *no connection is listening*, proving the replay comes from the projection, not from an in-memory buffer.
2. **Fresh connection** — no `Last-Event-ID`, must receive the full history 1..n as a snapshot, in order.
3. **Join during a burst** — `doOnSubscribe` interleaves broadcasts with the subscribe; the client must see every sequence exactly once — this exercises the `catchUp` logic.
4. **Ordered, no gaps, no duplicates** — broadcast 50 sequences, assert the delivered IDs are exactly `(1L..50L).toList()`.
5. **Authorization, both paths** — a patient without ownership gets `403` and zero events on connect *and* on the replay path.
6. **Two-patient isolation** — two concurrent live connections, interleaved broadcasts for both patients, each client receives exactly its own sequence.
7. **One end-to-end test** — real PostgreSQL and RabbitMQ Testcontainers: REST approval → outbox → projection consumer → SSE stream. Exactly one, because each run re-pays the cost of two containers and a broker race.

```kotlin
@Test
fun `reconnect with Last-Event-ID resumes exactly after the gap`() {
    projections.insert(StatusEvent(patientId, prescriptionId, 1, "SUBMITTED"))
    projections.insert(StatusEvent(patientId, prescriptionId, 2, "APPROVED"))
    projections.insert(StatusEvent(patientId, prescriptionId, 3, "PACKAGING"))

    val first = sseClient(baseUrl()).take(3).map { it.id()!!.toLong() }.collectList().block()!!
    assertThat(first).containsExactly(1L, 2L, 3L)

    broadcaster.broadcast(StatusEvent(patientId, prescriptionId, 4, "READY"))
    broadcaster.broadcast(StatusEvent(patientId, prescriptionId, 5, "FULFILLED"))

    val resumed = sseClient(baseUrl(), lastEventId = 3)
        .take(2).map { it.id()!!.toLong() }.collectList().block()!!

    assertThat(resumed).containsExactly(4L, 5L)
}
```

The claim the suite proves is exactly the one the demo cannot: **the stream is as trustworthy as the GET it enhances.** Postman proves a stream exists; these tests prove the stream is correct — reconnect, ordering, authorization, and isolation all asserted against a real HTTP server and a real store. That distinction — "realtime UX is proven, not demoed" — is the exact signal the challenge's evaluation criteria look for.

## Scope Decisions, Made Visible (Series 4, Post 4)

The challenge gives a range — "approximately two to five hours" — and the evaluation instruction that anchors every decision: *"we would rather see clean, working code than extra features."* The scope decision is therefore the first design decision, and it is a decision between two different products:

| Capability | 2-hour slice | 5-hour version |
| --- | --- | --- |
| Patient API + status GET as truth | Yes | Yes |
| State machine, atomic transitions | Yes | Yes |
| RabbitMQ work queues | Minimal, honest | Full: confirms, manual acks, retries, DLQ |
| Outbox relay | No — documented gap | Yes |
| Idempotent consumers (inbox) | No | Yes |
| Retries, dead letters | No | Yes |
| SSE with IDs, replay, isolation | No | Yes |
| Tests | Happy path + state transitions | + redelivery, reconnect, isolation |
| README with decisions and limitations | Yes, including the gaps | Yes, including ADRs |

The difference between the columns is not effort — it is judgment. The two-hour slice is *complete*: the patient journey works, the GET tells the truth, and the README documents every crash window with the one-line fix that would close it. The five-hour version spends its extra budget in risk order — outbox first, retries and DLQ second, SSE third — because each step closes a documented failure before it adds a feature.

Three scope rules carry into the walkthrough:

- **Defer anything whose absence does not break the patient journey and does not hide a correctness bug.** Auth headers with a documented replacement slot are fine; an outbox gap hides a correctness bug, so it moves to the top of the five-hour list.
- **The README is scope, not apology.** "What is included," "Known limitations and crash windows," "What I would do next" — written before the code is finished, because the interviewer's next question is always "how would you fix that?", and the README has already rehearsed the answer.
- **Generated UI follows backend proof.** A button that calls a missing endpoint reads as progress in a demo and as nothing in a code review. The three hours that built a plausible dashboard are the three hours that could have built reconnect tests and patient isolation. Once the contract is fixed by tests, a minimal static page over the API is an afternoon.

## The Live Walkthrough: How to Explain the Result

Everything above is material; the interview is the delivery. The walkthrough has a fixed shape — patient value first, then architecture, then invariants, then failure modes, then tests, then limitations — and the candidate's job is to hold that shape under interruption. Here is the script, with the sentences that carry the weight:

**1. Start with the patient journey (30 seconds).** "Ana submits a prescription at 09:14. From that moment she can always see exactly where it is: `SUBMITTED`, `APPROVED`, `PACKAGING`, `READY`, `FULFILLED`. The product is that journey, end to end."

**2. The architecture in one diagram.** "The API is a thin surface over a state machine. Staff actions and worker commands move the machine forward asynchronously through RabbitMQ work queues; statuses are facts that fan out to a projection store. The patient reads the projection — synchronously via the GET, or in realtime via SSE. Both are readers of one source of truth."

**3. The invariants.** "Three invariants hold everywhere: the state machine permits only legal transitions, enforced atomically with `409` on conflict; submission and every consumer are idempotent — retries cannot duplicate; and per-prescription sequence numbers make ordering local to each prescription, with no global clock."

**4. Failure behavior, named.** "The system is at-least-once end to end. The commit-to-publish window is closed by the outbox relay; redelivery is deduplicated by the inbox; poison messages are isolated by retry queues and a dead-letter exchange. The two-hour slice documents these windows instead of closing them, and the README says exactly which and why."

**5. SSE as an optimization, not a feature.** "SSE delivers committed projection rows milliseconds after they exist. Reconnect replay uses `Last-Event-ID` against the projection, so a dropped connection resumes exactly where it left off. The stream can never be ahead of the GET, and isolation is structural — a broadcaster keyed by patient, with authorization on connect and on replay."

**6. Tests as evidence.** "Every realtime claim has an assertion: reconnect, ordering, authorization on both paths, and two concurrent patients never seeing each other's events. Plus one end-to-end test through the real broker. The demo shows the stream; the tests show the stream is trustworthy."

**7. Known limitations, first.** "What is not built: real authentication (headers stand in, with a documented slot for an identity layer), a UI (generated only after backend proof), global ordering (only per-prescription), and exactly-once delivery (impossible to claim, so the system dedupes instead). Here is what I would do next, in risk order."

The two questions the walkthrough must survive: *"How do you know the patient sees the truth?"* — answer: the GET reads committed state directly, SSE reads the same store, and every test asserts convergence. *"What happens when the server restarts mid-broadcast?"* — answer: emitters are gone, but they were never the source of truth; clients reconnect with `Last-Event-ID`, and the projection replays the tail. Both answers are already in the code, and both are said in one breath because the design makes them true.

## Interview Review Checklist

- Can you narrate Ana's journey end to end and say which layer of the design owns each step?
- Why is the state machine the contract shared by the API, the messages, and the stream?
- Which three consumers get which three surfaces, and why do workers not use HTTP?
- Walk the crash-window table: outbox, redelivery, poison message — what closes each, and what does the 2-hour slice document instead?
- Why is SSE added *after* correctness, and what exactly does it depend on from the layers below?
- Where do event IDs come from, and why does `Last-Event-ID` make reconnects lossless?
- How is ordering per prescription rather than global, and why is "latest wins" forbidden for a status stream?
- How do you prove isolation and authorization in a test — and why must the broadcasts interleave?
- Which claims does the two-hour slice make, and which does it explicitly not make?
- In what order do you present the walkthrough, and what are the two sentences that survive interruption?

## Interview Takeaway

The defensible product slice is not a feature list; it is a causal chain. The state machine and the status GET make the journey truthful. The asynchronous workflow makes it move. The outbox, inbox, and dead-letter topology make the movement survive failure. And SSE — added last, as a delivery optimization over a proven baseline — makes the truth arrive instantly, with reconnect, ordering, and isolation proven by tests rather than promised by a demo. Every layer was a scope decision, every failure was named, and every claim can be walked through in the order it was built: patient first, workflow second, realtime third. When the walkthrough opens with Ana's prescription and closes with the crash-window table, the evaluator is not reviewing a submission — they are watching a Product Engineer reason about a product.
