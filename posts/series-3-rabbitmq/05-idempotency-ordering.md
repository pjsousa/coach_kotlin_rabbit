# Idempotent Consumers and Ordering Guarantees

The last post ended with the honest consequence of at-least-once delivery: every crash window in this design — the relay's republish, the commit-then-ack redelivery, the retry bounces, the DLQ replay — produces another copy of the same message. Retry topology without idempotent consumers is not a safety net; it is a slower way to corrupt state, because a second delivery of `packaging.request` for prescription 42 either double-packages it or, on the status side, replays an old event over a newer one.

This post builds the layer that makes copies harmless: the inbox table, a unique constraint on stable event IDs, and the transaction that makes the deduplication and the business effect one atomic decision. Then it takes the ordering question that tutorials wave away and splits it into what the broker can actually guarantee, what it cannot, and what the application must supply with sequence numbers. The through-line of the whole series lands here: the broker delivers at-least-once, the database makes the effect at-most-once, and the two together — never the broker alone — are the closest thing to exactly-once a pharmacy workflow is allowed to claim.

## A Duplicate Is Not An Anomaly

Every layer of the previous posts was built to keep delivering a message until it is acknowledged, and that is precisely why duplicates are guaranteed to happen. Inventory them once so the inbox design has a complete list:

| Source | Crash window | Duplicate shape |
| --- | --- | --- |
| Outbox relay (post 2) | Relay crashes after the broker confirms but before `published_at` is written | The row is republished on restart |
| Consumer crash (post 3) | Worker commits the effect, crashes before the ack | The broker redelivers the message |
| Retry topology (post 4) | Each bounce, and the DLQ publish-then-ack hop | One message, several deliveries |
| DLQ replay (post 4) | Replay is a fresh publish | Can be delivered more than once, and routed into the retry topology again |

All four share one property: the same logical event, carrying the same stable `eventId`, delivered more than once. That is the invariant the inbox exploits. If a duplicate ever carried a different ID, no deduplication could work — which is why the outbox generates the ID once, inside the transaction, and every later copy of the message reuses it. The `eventId` in the envelope from the topology post is not metadata; it is the handle the database needs to recognize a second delivery.

One more duplicate source belongs on the list even though it is not a crash: the replay utility that republishes an old event. If the replay regenerates the event ID instead of preserving it, the inbox treats the copy as a brand-new event — the duplication defense silently disappears. Preserving the ID is not a nicety; it is the contract.

## The Inbox Table

The inbox is a small table whose only job is to remember which events this consumer has already processed:

```sql
CREATE TABLE inbox_messages (
    consumer_id     text NOT NULL,   -- 'status-projection', 'packaging', ...
    event_id        uuid NOT NULL,
    prescription_id uuid NOT NULL,
    received_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_id, event_id)
);
```

Two decisions in that DDL matter. First, the primary key is the *composite* `(consumer_id, event_id)` — the deduplication state is per logical consumer, not global. The status projection, the packaging worker, and a future analytics subscriber each keep their own "already processed" memory in the same table, because each applies the event to its own store; one consumer's record must not mask another's. Second, the mechanism is a *unique constraint*, not a lookup. The constraint is what the database enforces under concurrency, and that distinction is the heart of the post.

## Deduplicate Inside The Transaction

The naive version reads the inbox, then processes:

```kotlin
if (inboxRepository.exists(event.eventId)) return   // wrong: two threads can both see "absent"
statusProjection.apply(event)
inboxRepository.insert(event.eventId)
```

That is a check-then-act race: two concurrent deliveries of the same event both read "not processed", both apply, both insert — the second insert fails, but the damage is done. The inbox only works if the claim and the effect share one transaction and the constraint does the arbitration:

```kotlin
@Transactional
fun handleEvent(event: PrescriptionEvent): EventResult {
    val claimed = inboxRepository.claim("status-projection", event)
        ?: return EventResult.AlreadyProcessed
    statusProjection.apply(event)      // same transaction as the claim
    return EventResult.Processed
}
```

The claim is an insert that only succeeds the first time:

```kotlin
@Query("""
    INSERT INTO inbox_messages (consumer_id, event_id, prescription_id)
    VALUES (:consumerId, :eventId, :prescriptionId)
    ON CONFLICT DO NOTHING
    RETURNING event_id
""", nativeQuery = true)
fun claim(consumerId: String, eventId: UUID, prescriptionId: UUID): UUID?
```

Trace both races and the design closes each one:

- **Two deliveries of the same event arrive concurrently** — from a relay republish landing on two workers, or a redelivery reassigned to a different consumer. Both transactions attempt the insert; the unique constraint admits exactly one. The loser's `RETURNING` yields no row, the claim returns null, and the effect is skipped. The database decides, not a check, and the decision is atomic with the effect.
- **The claim succeeds but the effect fails** — a constraint violation or a deadlock inside `apply`. The transaction rolls back, and the inbox row rolls back with it. The event is redelivered, the claim succeeds on the retry, and the effect runs fresh. No orphaned "already processed" row, no permanently lost effect.

The uniqueness of the effect does not depend on RabbitMQ doing anything clever. It is a PostgreSQL guarantee on a primary key, which is exactly why the pattern transfers to any at-least-once broker.

## The Acknowledgement Completes The Contract

The inbox interacts with the acknowledgement from post 3 in a precise sequence: claim and effect in one transaction, commit, then `basicAck`. Walk the crash points and every outcome is either correct or harmless:

| Crash point | Inbox row? | Effect? | Redelivery outcome |
| --- | --- | --- | --- |
| Before commit | No | No | Claim succeeds again, effect runs — correct |
| After commit, before ack | Yes | Yes | Claim returns null, event skipped, ack sent — harmless |
| After ack | — | — | Nothing is redelivered — correct |

Without the inbox, the middle row is a duplicate effect: the packaging run happens twice, or the projection applies `packaging` after `ready` and corrupts the status. With the inbox, the crash window that post 3 deliberately left open — the gap between commit and ack, which no acknowledgement ordering can eliminate — costs nothing. The ack is still mandatory; it is what stops the redelivery loop. The inbox is what makes the redelivery that still arrives a no-op instead of a bug.

One operational note: a duplicate should be acked, never nacked. Nacking a message whose effect already happened sends it into the retry topology for no reason, consumes budget, and delays the queue behind it. The listener treats `AlreadyProcessed` as success — the business requirement is *not that the event be processed, but that it be processed at most once*.

## What The Broker Can And Cannot Guarantee About Order

Ordering is where tutorial confidence dies, so make the guarantee list precise. RabbitMQ's real guarantees are narrower than most people state them:

- **Per queue, per channel**: messages enqueued by a single producer channel arrive in publish order. That is a real guarantee, and it survives fan-out only in the sense that *each* subscriber queue is independently ordered.
- **One consumer**: a single consumer receives a queue's messages in that order. Multiple consumers are dispatched round-robin; each consumer receives its own share in order, but *processing order across consumers is undefined* — message 5 can be delivered to consumer B and finished before consumer A finishes message 4.
- **Delivery order is not completion order**: even on one channel, the Java client dispatches deliveries in order while multiple listener threads process them concurrently. Post 3's `concurrency` knob multiplies this effect on purpose.
- **Redeliveries reorder**: a requeued message is placed back at its original position when possible, and closer to the head otherwise — either way, it sits among messages that were enqueued around it, and anything published after it may overtake it. A message that bounced through the retry queue's TTL returns after *everything* published since — its position is destroyed, not restored.
- **Across queues, nothing**: fan-out delivery order is per-subscriber-queue. And the outbox relay claims rows with `SKIP LOCKED` in an arbitrary order, so even the relay's publish order does not match `created_at`.

The honest sentence to give an interviewer: **RabbitMQ preserves order on the happy path — one queue, one producer channel, one consumer, no redeliveries — and nothing else.** Everything after the first failure is the application's problem. That is why the event envelope carries a sequence number, and why ordering is asserted in the projection, not hoped for from the broker.

## Per-Prescription Ordering: Sequence Numbers

The status events for one prescription are order-sensitive: applying `prescription.ready` before `prescription.packaging` produces a patient-visible lie. The topology gives the projection a single subscriber queue; the sequence number in the envelope (from the topology post) gives it the means to enforce order even when delivery misbehaves.

The projection records the highest sequence applied per prescription and applies a three-way rule:

```kotlin
@Transactional
fun apply(event: PrescriptionEvent) {
    val lastApplied = projectionRepository.lastAppliedSequence(event.prescriptionId) // 0 if none
    when {
        event.sequenceNumber <= lastApplied -> return          // already applied (duplicate or replay)
        event.sequenceNumber == lastApplied + 1 -> projection.apply(event)  // the expected next event
        else -> gapHandler.report(event)                       // sequence gap: something is missing
    }
}
```

The first branch is a second line of defense against the inbox: an old event replayed after its successors have been applied would pass a fresh inbox claim but must not move the status backward. The third branch is where the design gets honest. With a single ordered consumer, a gap (`ready` with no `packaging` in between) can only mean a delivery was lost in a retry bounce or a relay republish is in flight. The defensible challenge behavior is: do not apply, log loudly, and send the event to the dead-letter queue for inspection — the operator decides whether to replay the missing event, exactly as with poison messages. What is indefensible is applying events out of order and hoping nobody checks, or silently dropping the gap.

## Ordering And Parallelism Are The Same Decision

Once ordering is understood as a per-stream property, the topology choices from post 1 become a table with no surprises:

| Stream | Ordering needed | Consumer topology |
| --- | --- | --- |
| `packaging.requests` | No — independent runs | Competing consumers, concurrency free |
| `fulfillment.requests` | No | Competing consumers |
| `pharmacy.notifications` (status projection) | Yes, per prescription | Single consumer; failover via single active consumer |
| Patient SSE stream | Yes, per prescription | Projection store, read by the SSE layer (series 4) |

The first two rows are why packaging can run four workers on one queue: the messages are independent, so interleaving is harmless. The third row is why the notifications queue must not get the same treatment: two concurrent projection workers would apply event 5 before event 4 and corrupt the status. The fix is not more consumers — it is fewer.

Two mechanisms give a single ordered consumer without sacrificing availability. The first is **single active consumer** (`x-single-active-consumer`), a queue-level declaration: the broker delivers to exactly one consumer; if it dies, a registered standby takes over. Ordering is preserved across failover because only one consumer is ever active at a time — the correct answer to "what happens when your one projection worker crashes?":

```kotlin
QueueBuilder.durable("pharmacy.notifications")
    .singleActiveConsumer(true)
    .build()
```

The second is **sharding**: N queues, each bound to `prescription.#`, and the relay publishes to the shard `hash(prescriptionId) % N`, one consumer per shard. Order per prescription survives, parallelism becomes N. The costs are real and must be stated: the shard count is fixed at declaration, load skews if some prescriptions dominate, and each shard still needs its own single consumer. For a 2-5 hour challenge, one queue with single active consumer is the right answer; sharding is the sentence that proves you know how it scales, not the thing you build.

## What "Exactly Once" Can Honestly Mean

This is the sentence that separates the design from the overclaim. RabbitMQ's documented semantics with acknowledgements are **at-least-once delivery**; without them, at-most-once. There is no exactly-once delivery in this broker, and there is no combination of confirms, durable queues, and dead-letter exchanges that manufactures one. What the inbox produces is different and worth claiming precisely:

- **Delivery is at-least-once**: the broker keeps redelivering until an ack.
- **Effect is at-most-once**: the inbox constraint plus the transaction ensures each event's business effect happens at most once.
- **Together: effectively exactly-once on the durable state** — each event changes the projection exactly once, no matter how many deliveries arrive.

Two caveats keep that claim honest. First, deduplication only recognizes *identical event IDs* — a replay that regenerates IDs, or a publisher that sends the same logical change with a fresh ID, bypasses the inbox entirely. Second, the inbox only remembers what it is allowed to keep: rows must outlive the longest plausible redelivery span, which means the retry budget plus the replay window plus operator inspection time. Pruning inbox rows on a 24-hour TTL when the retry topology can deliver a copy a week later reopens the duplicate window. Retention is part of the correctness argument, not a hygiene afterthought.

## Pitfalls Interviewers Probe

- **"Why not check the inbox before processing?"** — Check-then-act: two concurrent deliveries both read "absent". The claim insert inside the same transaction as the effect is the only version that is atomic.
- **"What if the inbox insert and the effect are in different transactions?"** — A crash between them applies the effect without the record; the redelivery applies it again. Same transaction or nothing.
- **"Do you ack or nack a duplicate?"** — Ack. It was already handled; nacking sends it through the retry topology and burns budget on a job that must not run again.
- **"One inbox per consumer or one global table?"** — One table, composite key `(consumer_id, event_id)`. Each subscriber deduplicates against its own store.
- **"What breaks if the DLQ replay regenerates event IDs?"** — Everything. The inbox matches on the ID; a new ID looks like a new event and is applied again.
- **"Can the `redelivered` flag replace the inbox?"** — No. It is a hint, not a record: a message may be redelivered with the flag clear, and the flag carries no event identity. At best it lets a consumer skip the inbox check when it is cheap — never the reverse.
- **"Why can't two consumers process an ordering-sensitive queue?"** — Round-robin dispatch plus concurrent completion means event 5 can be applied before event 4. Ordering and parallelism are the same decision; choose per stream.
- **"What happens to order after a retry-bounce?"** — Destroyed. The message returns after everything published since. Sequence numbers exist precisely because the broker cannot restore position.
- **"What is exactly-once in your design?"** — The *effect*, on the durable state, guaranteed by the inbox transaction. The *delivery* is at-least-once. Anyone claiming the broker delivers exactly once has not read the crash windows.
- **"How long do inbox rows live?"** — Longer than the longest possible redelivery span: retry budget, replay window, and inspection time combined. Pruning early is a silent duplicate bug.

## Kotlin And Spring Recap

- The inbox is a table with primary key `(consumer_id, event_id)`; the constraint, not a lookup, does the deduplication.
- The listener's `@Transactional` handler claims via `INSERT ... ON CONFLICT DO NOTHING RETURNING`, and applies the projection effect in the same transaction; a null claim means already processed.
- Commit first, ack second — the ack-after-commit rule from post 3 — and the commit-then-ack crash window now costs nothing.
- Duplicates are acked as success; the effect is at-most-once, so "already processed" is the correct outcome.
- Sequence numbers per prescription enforce application-level order: apply the expected next, ignore older events, dead-letter gaps for inspection.
- Single active consumer (`QueueBuilder.singleActiveConsumer(true)`) gives the ordered projection queue failover without ordering loss.

## Interview Review Checklist

- Which four crash windows produce duplicate deliveries, and what does each one share?
- Why must the inbox claim and the business effect share one transaction, exactly?
- What race does `INSERT ... ON CONFLICT DO NOTHING RETURNING` close that a check-then-insert leaves open?
- Walk the crash table: what does a crash after commit but before ack cost with and without the inbox?
- State RabbitMQ's ordering guarantee precisely, and list the four conditions under which it holds.
- Why does a retry-bounce destroy a message's position, and why does fan-out get per-queue ordering at best?
- How do sequence numbers detect duplicates, gaps, and out-of-order arrival, and what does each branch of the rule do?
- Why is packaging safe with four competing consumers while the status projection is not?
- What does single active consumer buy, and why does sharding still preserve per-prescription order?
- What exactly is "exactly-once" in this design, and which layer provides it?
- Why is inbox retention part of correctness, and what bug does premature pruning reintroduce?

## Interview Takeaway

The broker's contract is at-least-once, and every layer of this series was built to honor it instead of hiding from it. The inbox makes the second delivery a no-op by turning deduplication into a database constraint shared with the business effect; the acknowledgement after commit closes the last crash window for free. Ordering gets the same treatment: the broker guarantees it only on the happy path, so the application owns it with per-prescription sequence numbers, a single ordered consumer with failover, and an honest gap policy. Idempotency and ordering are the last correctness layer of the workflow — which means they are also the layer that must be proven. The next post turns every claim of this series into evidence: broker integration tests for redelivery, duplicate delivery, retry limits, and dead-letter behavior, because in an interview a design that cannot be tested is a design that has not been finished.
