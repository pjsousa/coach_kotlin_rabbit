# Publisher Confirms and the Outbox Relay

The first instinct in the pharmacy challenge is to publish after commit: approve the prescription, write the state change, then call `rabbitTemplate.convertAndSend(...)`. The demo works, the walkthrough works, and then an interviewer asks one question that collapses it: *what happens if the process dies between the database commit and the broker publish?* The prescription is approved in PostgreSQL and nobody is ever told to package it. That gap is the dual-write failure mode, and it is the most important RabbitMQ problem in this challenge.

This post builds the standard fix — a transactional outbox and a relay that publishes with publisher confirms — and draws the line between the four moments people routinely blur: the database commit, the broker's acceptance, the routing decision, and the consumer's processing.

## The Four Moments

Every message travels through four independent checkpoints. Interviewers probe exactly here, because most tutorial code only ever sees two of them:

1. **Database commit**: the state change and the event to publish are durable in PostgreSQL.
2. **Broker acceptance**: the broker has taken responsibility for the message (the publisher confirm).
3. **Routing**: the exchange matched the routing key to at least one queue binding (or the message was dropped as unroutable).
4. **Consumer processing**: a worker received the message and completed its business effect (its acknowledgement).

Nothing in RabbitMQ links these moments. A message can be committed but never published, published but not routed, or routed but never processed. Confusing them produces the classic wrong claims: "the confirm proves the consumer got it" and "we publish after commit, so nothing is lost."

| Moment | Durable where | Proven by | Failure symptom |
| --- | --- | --- | --- |
| DB commit | PostgreSQL | Transaction `COMMIT` | State changed, no event exists |
| Broker acceptance | RabbitMQ (persistent message) | Publisher confirm | Publish hangs or errors |
| Routing | Exchange + bindings | Mandatory flag returns | Message silently dropped |
| Consumer processing | Consumer's own DB | Manual ack | Message redelivered forever |

The outbox pattern fixes moment 1 to 2. Confirms make moment 2 observable. The mandatory flag and exchange checks cover moment 3. Manual acknowledgement and idempotent consumers, in the later posts, close moment 4.

## Why Publish-After-Commit Is Wrong

Trace the failure window. The approval service runs one transaction: update prescription status, insert status history, commit. Then it publishes `packaging.request` to RabbitMQ. The window between commit and publish is small — but it is open on every approval:

- Crash after commit, before publish: the prescription is approved, the patient sees it, and no packaging worker is ever invoked. The workflow stalls silently with zero error logging, because the code that would have logged the failure never ran.
- Crash *during* publish: the broker may or may not have received the message. The application cannot tell from the exception alone.
- Retry of the publish after the crash: the message may be sent twice if the first attempt actually reached the broker.

Publish-first has the mirror-image problem: the broker says a packaging run should happen that the database has no record of, and a worker that trusts the message will act on a prescription the system considers not yet approved.

The root cause is that two durable systems receive one logical change, and the ordering can never be both safe and atomic. The transactional outbox removes the choice: the database becomes the single source of truth for *both* the state change and the intended publish, and a relay reconciles the second system from the first.

## The Outbox Table

One table, written in the same transaction as the domain change:

```sql
CREATE TABLE outbox_events (
    event_id       uuid PRIMARY KEY,
    prescription_id uuid NOT NULL,
    aggregate_type text NOT NULL,          -- 'prescription'
    event_type     text NOT NULL,          -- 'approved', 'packaging', ...
    routing_key    text NOT NULL,          -- 'prescription.approved', ...
    payload        jsonb NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz
);
```

The approval transaction inserts the state change *and* the outbox row atomically:

```kotlin
@Transactional
fun approve(prescriptionId: UUID): ApproveResult {
    val updated = prescriptionRepository.approve(prescriptionId)   // UPDATE ... SET status='APPROVED' WHERE status='SUBMITTED'
    if (updated == 0) return ApproveResult.AlreadyProcessed
    outboxRepository.insert(
        OutboxEvent(
            eventId = UUID.randomUUID(),
            prescriptionId = prescriptionId,
            eventType = "approved",
            routingKey = "prescription.approved",
            payload = "...",
        )
    )
    return ApproveResult.Approved
}
```

There is no `rabbitTemplate` call in this code path at all. If the transaction rolls back, the outbox row rolls back with it. If it commits, the row is durable *and* the state change is durable, because they share one commit point. That is the entire trick: the dual write becomes a single write, and the second system is updated asynchronously by the relay.

The `event_id` matters even before any message exists. It is generated once, in the transaction, and stays with the event through the outbox row, the message envelope, and the consumer's inbox. Two different deliveries of the same logical event carry the same ID; that is what makes deduplication possible later.

## Publisher Confirms

The relay still publishes outside any database transaction, so it needs a way to know whether the broker accepted the message. That is what publisher confirms are: a protocol-level acknowledgement that the broker has taken responsibility for the message, not that it has routed it or that a consumer processed it.

With a plain channel, publishes are fire-and-forget. Spring AMQP turns confirms on by default when a `CachingConnectionFactory` with publisher confirm support is configured:

```kotlin
@Bean
fun connectionFactory(): CachingConnectionFactory {
    val factory = CachingConnectionFactory()
    factory.setHost("localhost")
    factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED)
    factory.setPublisherReturns(true)
    return factory
}
```

`CORRELATED` confirm type asks the broker to correlate each confirm back to the publish, which is exactly what a relay needs to decide which outbox row to mark published. With this in place, a publish is not done until the confirm arrives; a nack or a timeout means the broker never took responsibility, and the message may be republished safely later.

Spring AMQP's `RabbitTemplate` can surface this directly:

```kotlin
template.setConfirmCallback { correlationData, ack, cause ->
    when {
        ack -> relayService.markPublished(correlationData.id)
        else -> logger.warn("Broker nacked publish {}: {}", correlationData.id, cause)
    }
}
```

The `correlationData.id` is the event ID, which is precisely why the outbox row carries one. No broker-generated handle survives the round trip; the application-chosen ID is the only way to match a confirm back to a row.

There are two more confirm-related details interviewers check:

- **Persistent messages**: the confirm means the broker accepted the message, not necessarily that it is on disk. With a persistent message on a durable queue, the broker fsyncs before confirming; on a classic queue, a single-node broker that crashes after the confirm but before the flush can still lose it. Confirms plus persistent messages plus durable queues is the defensible combination, and quorum queues make the survival story much stronger.
- **Mandatory flag**: `setPublisherReturns(true)` plus a `ReturnCallback` catches the moment-3 failure. A mandatory message that matches no binding is returned to the publisher instead of silently vanishing. For the relay, a returned `packaging.request` is a configuration error — the topology from the previous post should make it impossible — but the return callback is what makes it visible instead of invisible.

## The Relay Loop

The relay is a loop over the outbox table, not a complex daemon:

```kotlin
@Scheduled(fixedDelay = 1000)
fun relayPendingEvents() {
    val events = outboxRepository.claimPending(limit = 100)   // SKIP LOCKED
    events.forEach { event ->
        publishWithConfirm(event)
    }
}

fun publishWithConfirm(event: OutboxEvent) {
    val correlationData = CorrelationData(event.eventId.toString())
    try {
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
    } catch (ex: AmqpException) {
        logger.error("Publish failed for event {}: {}", event.eventId, ex.message)
    }
}
```

The relay never deletes rows it published; it marks `published_at`, or drops a row only after an explicit, tested retention window. Marking-not-deleting is what makes crash recovery possible. The two crash windows to be able to walk through:

- **Crash before the publish starts**: the row is still pending. On restart the relay claims it again and publishes. Zero loss, zero duplicates.
- **Crash after the broker accepted the message but before the mark**: the row is still pending. On restart the relay publishes it *again*. This is not a bug; it is the unavoidable consequence of at-least-once delivery. The duplicate is handled by the consumer's inbox pattern in the later post.

There is no crash window in which the event is neither published nor marked — the mark only ever happens *after* the confirm — and there is no window in which the event is lost entirely, because the row predates the publish. Every event is published at least once. Some may be published more than once. None is ever published before it exists in the database.

Two practical details make the claim-and-mark loop trustworthy:

- **Claiming with `SKIP LOCKED`**: concurrent relay instances (or a restart overlapping a running one) must not contend on the same rows. `SELECT ... FOR UPDATE SKIP LOCKED` claims the next unmarked rows without blocking the sibling relay, and the claiming transaction marks them as in-flight so two relays never hold the same event.
- **Polling frequency vs. freshness**: a 1-second fixed delay is fine for a pharmacy challenge and trivially explainable. If the requirement tightens, the relay switches to Postgres `LISTEN`/`NOTIFY` or a channel wake-up, and the loop logic does not change. Starting with polling is not a shortcut; it is the version with the fewest moving parts.

## Why Duplicate Publication Remains Possible

The outbox makes loss nearly impossible and makes duplication *certain to happen eventually*. The relay republishes after any uncertainty, and the only uncertainty that matters is the confirm round trip: the broker may have accepted the message and then the relay crashes before it records the confirm. From the relay's point of view, the publish never happened. From the broker's point of view, it did.

That is at-least-once semantics, and no combination of confirms, durable queues, or persistent messages upgrades it to exactly-once. The honest position for the interview is:

- The outbox guarantees **every committed event is published at least once**.
- Confirms guarantee the relay **knows** whether the broker accepted, so it can retry instead of guessing.
- Nothing removes the **duplicate window**, because the marker can always lag the broker's acceptance.
- Duplicates are neutralized at the consumer with a stable event ID and an inbox uniqueness constraint — the dedicated idempotency post.

Anyone who claims the outbox prevents duplicates has not traced the crash window. Anyone who claims duplicates mean the outbox failed has not understood what it is for. The outbox is not about delivering exactly once; it is about making loss impossible and duplication *recognizable*.

## What This Design Does Not Fix

The relay and confirms stop at the broker's acceptance. Everything after that belongs to the next posts, and the interview answer must not claim otherwise:

- **Routing errors**: a message accepted but matched to no binding is returned by the mandatory flag; a message bound to the wrong queue is a topology bug that no publish mechanism detects.
- **Consumer crashes**: the broker may deliver the message and the worker dies mid-effect. Manual acknowledgement and redelivery, covered next in the series, define what happens.
- **Poison messages**: a message that makes the worker fail permanently must be retried with a budget and dead-lettered, never stuck in the queue. That is the retries and DLQ post.
- **Duplicate processing**: the same event delivered twice to a non-idempotent worker double-packages the prescription. The inbox post makes the second delivery a no-op.

The clean mental model: this post gets the message from the database into the broker with a receipt; the rest of the series gets it from the broker into a completed business effect, at most once in effect, at least once in delivery.

## Kotlin And Spring Recap

- The outbox insert lives inside the same `@Transactional` method as the domain change; there is no publish in that method.
- The relay runs on a `@Scheduled` loop, claims rows with `SKIP LOCKED`, and publishes with a `CorrelationData` whose ID is the event ID.
- The confirm callback marks the row published; the return callback logs unroutable messages; neither callback deletes rows.
- The event envelope reuses the outbox `event_id` so the consumer can deduplicate.

## Interview Review Checklist

Before walking through this design, be able to answer:

- What is the dual-write failure window, and why does publish-after-commit leave it open?
- What does a publisher confirm prove, and what does it not prove?
- Why must the outbox row be inserted in the same transaction as the state change, and what breaks if it is not?
- Why does the relay mark rows as published instead of deleting them, and what happens on restart in each crash window?
- Why does duplicate publication remain possible even with confirms, and why is that acceptable?
- What does `SKIP LOCKED` buy two relay instances, and why is the claim transaction needed?
- How does the relay know the broker accepted the message, and how does it match the confirm back to the event?
- What happens to a message that matches no binding, and how is that surfaced?
- Where do the three failure moments — broker acceptance, routing, consumer processing — each get handled in the full design?

## Interview Takeaway

The outbox turns a dual write into a single write: the database commits the state change and the event together, and the relay reconciles the broker from the database. Publisher confirms give the relay a receipt so it can mark, retry, and stop guessing. The price of that guarantee is duplicate publication in a narrow crash window, and the answer is not a stronger broker setting — it is a stable event ID generated in the transaction and an idempotent consumer. State it that way and the interviewer knows the mental model is structural, not tutorial-shaped. Next in the series: how a worker turns a received message into a completed business effect with manual acknowledgements, prefetch, and the ack-after-commit rule.
