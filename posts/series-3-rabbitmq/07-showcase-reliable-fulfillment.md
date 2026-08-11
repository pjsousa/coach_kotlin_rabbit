# Reliable Prescription Fulfillment with RabbitMQ

A pharmacist clicks "approve" on prescription 42. In the next few seconds that one click must become a packaging run, a fulfillment run, and a visible status change for the patient — without anyone watching the wiring. And yet between the click and the patient's phone screen there are four systems, three brokers of the message, and a dozen places where a process can die mid-sentence. The claim of this series is that the design still completes, with every interruption turning into a defined outcome rather than a silent stall.

The previous six posts each built one piece: the topology, the outbox relay and confirms, manual acknowledgement, retries and dead letters, idempotent consumers and ordering, and the tests that turn the claims into evidence. This post unifies them into the single end-to-end scenario the challenge would submit: a PostgreSQL transaction creates an outbox event, a relay publishes it with a confirm, workers process it with manual acknowledgements and an inbox, transient failures retry on a timer, poison messages dead-letter, and patient status fans out separately from the work. The through-line of the series lands in one sentence: *the broker records evidence, the application makes policy* — and every crash window has a defined recovery result.

## The Journey of One Approval

One message, two exchanges, four queues, and four crash windows. Before any reliability mechanism, draw the happy path so every mechanism has a place on it:

```text
  pharmacist approves prescription 42
  │
  │  one PostgreSQL transaction
  ▼
  UPDATE prescriptions SET status = 'APPROVED' ...        ── state change
  INSERT INTO prescription_status_history ...             ── the fact of it
  INSERT INTO outbox_events ...                           ── the handoff to RabbitMQ
  │
  │  COMMIT
  ▼
  outbox relay (polls every 1s, claims with SKIP LOCKED)
  │  publish with publisher confirm  ── broker accepts
  ▼
  pharmacy.work (direct)                    pharmacy.events (topic)
  │  routing: packaging.request             │  routing: prescription.approved
  ▼                                         ▼
  packaging.requests (competing workers)    pharmacy.notifications (single consumer,
  │                                         │  binding prescription.#)
  │  manual ack AFTER commit                 │  inbox claim + projection, same transaction
  ▼                                         ▼
  fulfillment.requests (competing workers)  status store → patient sees APPROVED
  │
  ▼
  prescription 42 fulfilled; prescription.fulfilled fans out too
```

Each hop of that diagram was one post, and each hop has exactly one or two crash windows. The rest of this post walks the journey and names every window as it appears. At the end, the crash table collects them all — because the interview answer is not "it works"; it is "here is every way it can break, and here is what each one produces."

## Step 1: The Approval Transaction

The journey starts with the one line of the diagram that does not involve RabbitMQ at all, and it is the one that makes everything else possible. The approval service writes three rows in one transaction — the status change, its history, and the outbox event:

```kotlin
@Transactional
fun approve(prescriptionId: UUID): ApproveResult {
    val updated = prescriptionRepository.approve(prescriptionId)   // WHERE status = 'AWAITING_APPROVAL'
    if (updated == 0) return ApproveResult.AlreadyProcessed
    outboxRepository.insert(
        OutboxEvent(
            eventId = UUID.randomUUID(),
            prescriptionId = prescriptionId,
            eventType = "approved",
            routingKey = "prescription.approved",
        )
    )
    return ApproveResult.Approved
}
```

There is no `rabbitTemplate` call in this method. There is no RabbitMQ dependency in this transaction at all. The outbox post's argument is the reason: publish-after-commit leaves the prescription approved in PostgreSQL with nobody told to package it, and publish-before-commit sends a message about a state change that may roll back. Both orders have a crash window where the two systems disagree. Inserting the event inside the same transaction as the state change removes the choice — the database is the single source of truth for both the change and the intended publish, and the two commit or roll back together.

The `eventId` generated here is not metadata. It is the identity the whole reliability chain hangs on: it travels from the outbox row into the message envelope, and later it is the value the consumer's inbox primary key matches against. A duplicate delivery of that event carries the same ID; that sameness is what makes the duplicate harmless. If any hop regenerates the ID — a sloppy replay utility, a relay that re-rolls IDs — the deduplication layer stops recognizing the copy, and the whole at-least-once story collapses.

**Crash window 1 (closed here):** a crash at any point before the commit rolls everything back together, including the outbox row. The prescription stays `AWAITING_APPROVAL`, no event exists, nothing to reconcile. A crash after commit leaves both the state change and the event durable; the only consequence is that the event may be published a moment later than the state changed, which is a latency detail, not a correctness one.

## Step 2: The Relay

The relay is a scheduled loop over the outbox table, and its entire job is to convert "durable row" into "broker accepted" with a receipt:

```kotlin
@Scheduled(fixedDelay = 1000)
fun relayPendingEvents() {
    outboxRepository.claimPending(limit = 100)   // SELECT ... FOR UPDATE SKIP LOCKED
        .forEach { event -> publishWithConfirm(event) }
}

fun publishWithConfirm(event: OutboxEvent) {
    val correlationData = CorrelationData(event.eventId.toString())
    template.convertAndSend(
        EXCHANGE, event.routingKey,
        PrescriptionEvent(
            eventId = event.eventId,
            prescriptionId = event.prescriptionId,
            status = event.eventType,
            sequenceNumber = event.sequenceNumber,
            occurredAt = event.createdAt,
        ),
        correlationData,
    )
}
```

Three details carry the correctness of this loop. `SKIP LOCKED` lets two relay instances (or a restart overlapping a running one) claim disjoint rows instead of contending. The `CorrelationData` ID is the outbox event ID, so the confirm callback can mark exactly the right row:

```kotlin
template.setConfirmCallback { correlationData, ack, cause ->
    when {
        ack -> outboxRepository.markPublished(correlationData.id)
        else -> logger.warn("Broker nacked publish {}: {}", correlationData.id, cause)
    }
}
```

And the mark happens only *after* the confirm — never before, never instead of. The relay marks rows; it never deletes them. That ordering is the entire crash-window story of this step:

**Crash window 2 (this step):** the relay crashes after the broker accepted the message but before `published_at` is written. On restart, the row is still pending, so the relay publishes it again. From the relay's point of view the publish never happened; from the broker's point of view it did. This is the one unavoidable duplicate window in the system — no combination of confirms, persistent messages, and durable queues closes it, because the marker can always lag the broker's acceptance. The window produces a duplicate, never a loss, and the duplicate is recognizable: it carries the same event ID as the original.

There is no crash window here that produces loss. The row exists before the publish, so an event cannot be published before it exists in the database — and it cannot fail to be published forever, because the row remains pending until a confirm is recorded. That is at-least-once in its cleanest form, and it is the foundation every later hop builds on.

## Step 3: The Packaging Worker

The packaging worker consumes `packaging.requests` with manual acknowledgement, and its correctness is one ordering rule: do the durable effect first, ack second.

```kotlin
@RabbitListener(queues = ["packaging.requests"], ackMode = "MANUAL")
fun handle(request: PackagingRequest, message: Message, channel: Channel) {
    try {
        packagingService.packagePrescription(request.prescriptionId)   // @Transactional
        channel.basicAck(message.messageProperties.deliveryTag, false)
    } catch (ex: Exception) {
        val tag = message.messageProperties.deliveryTag
        when {
            classify(ex) == FailureClass.PERMANENT -> { errorPublisher.publishToDlq(message); channel.basicAck(tag, false) }
            retryAttempts(message) >= MAX_RETRIES  -> { errorPublisher.publishToDlq(message); channel.basicAck(tag, false) }
            else -> channel.basicNack(tag, false, false)   // into the retry topology
        }
    }
}
```

The worker is deliberately unremarkable: it is the previous posts' full decision tree in one method. The transaction commits the packaging run and its history rows; then the ack tells the broker the delivery is done. The ack is a promise to the broker, not to the business — nothing in the broker knows or cares whether the packaging effect is durable. That is why the ordering matters:

**Crash window 3 (this step):** the worker commits the packaging effect and crashes before the ack. The broker never learned the delivery was finished, so it redelivers the message. The second delivery re-runs the packaging method — and here is the whole point of the design: the effect runs *twice* unless the inbox stops it. Without the inbox, the commit-then-ack window is a double-packaging bug. With the inbox, the window costs nothing, as the next step shows.

The ack-after-commit rule is what picks the correct side of the trade: commit-then-ack makes loss impossible and duplicates possible; ack-then-commit makes duplicates impossible and loss possible. Loss means a packaging run that never happened and never will — the pharmacist approved, the patient waits, nobody is ever told. Duplicates mean a packaging run that happens again — and duplicates are the failure mode this design can actually neutralize. Every crash in the system is steered toward the side that has a defense.

The worker's other knobs are already in place from the acknowledgement post: prefetch bounds the unacknowledged messages a worker may hold (so a crash reclaims a bounded batch, not a quarter of the queue), and concurrency of four consumers multiplies throughput while packaging runs remain independent. Neither is a reliability mechanism; they are the accounting that says exactly how much work is in flight and exactly what a crash will reclaim.

## Step 4: The Inbox, Inside The Worker

The packaging worker's first line, before any business logic, is the claim:

```kotlin
@Transactional
fun packagePrescription(prescriptionId: UUID): PackageResult {
    val claimed = inboxRepository.claim("packaging", eventId)
        ?: return PackageResult.AlreadyProcessed
    packagingRepository.runPackaging(prescriptionId)   // the durable effect
    return PackageResult.Packaged
}
```

The claim is an insert that only succeeds once, and the constraint — not a check, not a flag — does the deduplication:

```kotlin
INSERT INTO inbox_messages (consumer_id, event_id, prescription_id)
VALUES (:consumerId, :eventId, :prescriptionId)
ON CONFLICT DO NOTHING
RETURNING event_id
```

The deduplication and the effect share one transaction. That is the detail that separates this from check-then-act: two concurrent deliveries of the same event both attempt the insert, the primary key admits exactly one, the loser's claim returns null, and the effect is skipped. And if the claim succeeds but the effect fails, the transaction rolls back — inbox row and effect together — so the redelivery runs the effect fresh. The unique constraint is the arbiter, the transaction is the atomicity, and neither depends on RabbitMQ behaving well.

Now re-walk crash window 3 with the inbox in place: the worker commits the packaging run *and* the inbox row, then crashes before the ack. The broker redelivers; the second delivery attempts the claim; the insert returns no row; the handler returns `AlreadyProcessed` — and acks, because a duplicate is success, never a nack. Nacking a message whose effect already happened would send it into the retry topology to burn budget on a job that must not run again. The business requirement is not "the event is processed"; it is "the effect happens at most once."

This is the layer that makes every earlier duplicate window — the relay's republish, the redelivery, the retry bounces, the replay — a no-op instead of a bug. The series said it in every post, and the showcase proves it in one method: delivery is at-least-once, effect is at-most-once, and together they are the only honest version of exactly-once this system is allowed to claim.

## Step 5: The Retry Topology

When the packaging worker decides the failure is transient — a locked inventory row, a timeout against the barcode service — it rejects with `requeue = false`, and the work queue's dead-letter configuration routes the message into the retry parking lot:

```text
  packaging.requests ──(nack, requeue=false)──► pharmacy.retry
        ▲                                          │ routing: packaging.requests.retry
        │                                          ▼
        │                          packaging.requests.retry (TTL 30s, NO consumers)
        │                                          │ TTL expiry → dead-letter back
        └─────────── pharmacy.work ◄───────────────┘ routing: packaging.request
```

The retry queue has no consumers on purpose — a consumer would defeat the timer by processing before the TTL fires. The TTL is the delay; the dead-letter routing back to the work exchange is the bounce; and every bounce is a fresh delivery with `x-death` updated by the broker. The retry budget is read from that broker evidence, never from an in-memory counter that dies with the worker:

```kotlin
private fun retryAttempts(message: Message): Int =
    (message.messageProperties.headers["x-death"] as? List<Map<String, Any?>>)
        ?.firstOrNull { it["queue"] == "packaging.requests.retry" }
        ?.get("count") as? Int ?: 0
```

The classification rule from the retries post decides which failures even enter this topology: parse errors and not-found prescriptions are permanent and go straight to the dead-letter queue; lock waits and dependency timeouts are retryable; and the catch-all defaults to retryable, because a logic bug getting three slow retries is acceptable while a transient network failure skipping the safety net is not.

**Crash window 4 (this step):** the terminal hop. When the budget is exhausted, the worker publishes the message to the dead-letter exchange *and then* acks the original delivery. A crash between those two statements redelivers the message, which retries one more time — a duplicate, never a loss. The budget is a ceiling, not a jail, and `MAX_RETRIES = 3` means the message is delivered up to four times: the original plus three bounces. That is the arithmetic to quote, and the `x-death` entry records it with `reason = rejected` — proof the bounce came from the consumer's nack, not from TTL expiry, which is exactly the forensic distinction an operator needs to tell "the worker rejected it" from "it expired while nobody was looking."

## Step 6: The Dead-Letter Queue

When the budget runs out, or the failure was permanent from the start, the message lands in `packaging.dlq` with its payload and its full `x-death` history intact. The DLQ has no production consumers. It is a quarantine area with two interfaces: inspect (Management UI or HTTP API: depth, headers, payload) and replay (a small utility that consumes from the DLQ and republishes the original to the work exchange).

Two things are true about replay that must stay true in an interview. First, replay is a *new publish*: it can be delivered more than once, and it can re-enter the retry topology. The inbox is what makes the copies harmless — replay without idempotency is just a slower way to double-package. Second, the replay must preserve the event ID and the `x-death` history. Regenerating the ID makes the inbox treat the copy as a new event and the whole deduplication defense disappears; dropping `x-death` erases the attempt history an operator needs to decide whether the message is safe to run again. Replay is a diagnostic rerun, not a fix, and it is safe only because the layer beneath it remembers.

The DLQ also carries the observability claim of the series: work-queue depth looks healthy while poison messages pile up invisibly, so the alert is on the DLQ depth, not the work queue. The poison message is not an error state to be eliminated — it is the system's honest way of saying "this job cannot succeed and here is its complete attempt history." Making that state visible and bounded is the entire job of the layer.

## Step 7: Status Fan-Out, Separately

While the work side of the diagram handled the packaging, a second copy of the approval event — the same stable ID, published by the same relay — went to the *other* exchange: `pharmacy.events`, the topic exchange, routed to the notifications queue with the `prescription.#` binding. This is the topology post's central decision, and the showcase scenario is where it pays off: work and facts are different message classes and must never share a queue.

The notifications consumer is structurally different from the packaging worker. It is not a competing consumer — it is a single ordered consumer (declared with `x-single-active-consumer` for failover) whose job is to keep a projection store of patient-visible status in order:

```kotlin
@Transactional
fun apply(event: PrescriptionEvent) {
    val lastApplied = projectionRepository.lastAppliedSequence(event.prescriptionId) // 0 if none
    when {
        event.sequenceNumber <= lastApplied -> return
        event.sequenceNumber == lastApplied + 1 -> projection.apply(event)
        else -> gapHandler.report(event)   // gap: dead-letter for inspection, never apply out of order
    }
}
```

The sequence number in the envelope does the ordering work because the broker cannot. RabbitMQ preserves order only on the happy path — one queue, one producer channel, one consumer, no redeliveries — and every retry bounce, relay republish, and redelivery in this scenario violates at least one of those conditions. The sequence numbers are the application's order, asserted on every apply: duplicates and replays of old events are ignored (never applied backward), the expected next event is applied, and a gap is dead-lettered for inspection rather than applied out of order.

Why not four concurrent workers here, the way packaging has them? Because the projection is order-sensitive per prescription, and round-robin dispatch plus concurrent completion means event 5 can finish before event 4 — a patient-visible lie. Packaging runs are independent, so concurrency is free there; the status projection is a single stream, so it gets a single consumer with failover. Ordering and parallelism are the same decision, and the decision is made per stream.

And why not make patient SSE connections consumers of this queue? The same topology post's answer holds in the scenario: a competing consumer delivers each message to *one* consumer, so a shared queue would hand prescription 42's update to whichever connection was free — possibly patient 7's. An exclusive queue per connection turns the broker into a session manager with queues outliving connections and zero replay memory. The correct shape is what the diagram shows: the notification service consumes the facts, maintains the projection, and the SSE layer (the next series) reads the projection with `Last-Event-ID` replay. SSE connections are clients of the service, never consumers of the queue. The event exchange exists so the projection has a complete, ordered, replayable stream to build on.

## Every Crash Window In One Table

The scenario is now complete, and so is its inventory of interruptions. This table is the interview answer for "what happens when something crashes?" — every window, the failure it produces, and the layer that handles it:

| # | Crash window | What is at risk | What actually happens | Neutralized by |
| --- | --- | --- | --- | --- |
| 1 | Approval transaction before COMMIT | Partial state | Everything rolls back together, including the outbox row | Transaction |
| 2 | Relay: broker confirmed, `published_at` not written | Event never published | Relay republishes on restart | Duplicate window, closed by the inbox |
| 3 | Worker: effect committed, ack not sent | Effect lost | Broker redelivers; second delivery runs the effect | Inbox claim in the same transaction |
| 4 | Terminal retry hop: DLQ publish, then ack | Poison copy lost | Crash redelivers, message retries one more time | Duplicate, bounded by the budget |
| 5 | Ordering: retry bounce or relay republish reorders events | Patient-visible wrong status | Sequence check ignores stale events, dead-letters gaps | Per-prescription sequence numbers |
| 6 | Notifications consumer dies | Projection stops updating | Single active consumer failover; ordering preserved | `x-single-active-consumer` |

Every crash in the system is steered toward one of two outcomes: nothing committed (windows 1), or a duplicate that the inbox or the sequence rule renders harmless (windows 2-4). There is no window in this table where work is lost and the system does not know it, and there is no claim anywhere of exactly-once delivery. The honest summary is the sentence the series has been building toward: at-least-once delivery, at-most-once effect, application-owned ordering — and the table above is what that sentence means in practice.

## The Operational Evidence

A design defended only with diagrams is a design that has not been finished. The testing post gave every claim of this scenario a test, and the showcase's evidence list is exactly that suite:

- **Topology assertions:** the retry queue exists with zero consumers; a rejected work message actually arrives in the retry queue — proving the binding, the dead-letter exchange, and the routing key by behavior, not by declaration.
- **The commit-then-ack redelivery test:** a latch pauses the listener between the effect and the ack, the channel dies, the broker redelivers — and the inbox makes the second delivery a no-op. This single test proves windows 3 and the whole at-least-once/at-most-once pairing.
- **The duplicate-publication test:** two copies of the same event ID (exactly what window 2 produces) published to the work queue; the packaging run count stays at one. This is the test that proves the event ID is the contract.
- **The retry-budget test:** `MAX_RETRIES = 3` produces four deliveries, the work queue drains, and the DLQ holds exactly one message with `x-death` count 3 and `reason = rejected`, payload intact. Every arithmetic claim of the retry layer, proven end to end.
- **The classification test:** a permanently failing payload reaches the DLQ with zero retries and no retry-queue entry in `x-death` — the proof that `classify` is a real policy, not a design wish.
- **The DLQ-depth observability test:** the work queue reads zero while the DLQ holds one, through the Management API — the proof that the alerting rule is on the right queue.

The rules that keep that suite honest are the same in the showcase as in the series: the test container's image is pinned to the Compose broker version; the tests run the production topology beans, never a re-declared parallel system; the retry TTL is a configurable value overridden short in tests; Awaitility polls instead of sleeping; queues are drained between tests; and mocks prove only the application's calls, never the broker's responses.

## What Is Intentionally Omitted

The challenge is a 2-5 hour exercise, and the scenario above is already more than the two-hour slice needs. Naming the omissions — and the reason for each — is part of the design:

- **Graduated backoff.** A single retry queue with a fixed TTL is the defensible default. Real graduated backoff needs one parking lot per delay step or the delayed-message plugin — a broker dependency that must be justified on Docker and in the walkthrough. Fixed TTL buys the same correctness with fewer moving parts.
- **Sharded notifications.** One ordered queue with single active consumer serves the challenge; sharding by `hash(prescriptionId)` is the sentence that shows how it scales, not the thing to build for a take-home.
- **Retry queue versioning.** The quorum-vs-classic TTL story (quorum queues support TTL from RabbitMQ 3.12) is resolved by pinning the broker version and saying so — not by testing every combination.
- **Reservation expiry and sweeper jobs.** Work that belongs to the persistence series' omissions, not this one: the scenario assumes the workflow completes.
- **Exactly-once.** Not omitted — impossible. The design claims at-least-once delivery, at-most-once effect, and ordering owned by sequence numbers. Anyone who hears "exactly-once" in this walkthrough is hearing an overclaim.

The two-hour submission keeps the outbox, the relay, manual ack, and the inbox — the first four crash windows and their tests — and documents retries, DLQ replay, and SSE in the README as pending. The five-hour version adds the retry topology and the fan-out. What never changes: every claim is either implemented with its mechanism and its test, or explicitly listed as known-limitation. Never silently assumed.

## Interview Review Checklist

- Draw the full scenario: which exchange carries work, which carries facts, and which queue each consumer reads?
- Why must the outbox row be inserted in the same transaction as the approval, and what are the two wrong orderings it replaces?
- What exactly does a publisher confirm prove, and why does the relay republish after a crash between confirm and mark?
- Walk the commit-then-ack window: what does the broker do, what does the inbox do, and what would happen without it?
- Why is a duplicate acked as success rather than nacked?
- Where does the retry budget live, and what does `MAX_RETRIES = 3` mean in total deliveries?
- What does `reason = rejected` in an `x-death` entry prove, and what would `expired` mean instead?
- Why can the notifications projection have only one effective consumer while packaging runs four?
- How do sequence numbers distinguish a duplicate, an expected event, and a gap — and what does each branch do?
- What does each row of the crash-window table produce, and which layer handles it?
- Which test proves the at-least-once/at-most-once pairing, and why can a mock never run it?
- What does the two-hour submission omit, and how is each omission documented?

## Interview Takeaway

One approval, one journey, and every step is a defined outcome under failure. The database commits the change and the event together; the relay moves the event to the broker with a receipt and one unavoidable duplicate window; the worker commits its effect, claims the inbox, and acks last — turning the duplicate window into a no-op; the retry topology gives transient failures a timer and a budget; the dead-letter queue gives poison messages a home with their attempt history intact; and the status projection fans out separately, ordered by sequence numbers, so the patient's view never lies. Every crash produces either nothing or a recognizable duplicate, never silent loss, and the evidence for each claim is a pinned-broker integration test. That is the design the challenge ships, and the sentence that defends it: delivery is at-least-once, effects are at-most-once, ordering is the application's job, and the tests prove all three on a real broker.
