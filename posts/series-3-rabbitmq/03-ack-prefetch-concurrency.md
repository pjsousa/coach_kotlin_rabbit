# Manual Acknowledgements, Prefetch, and Consumer Concurrency

The last post ended with a receipt: publisher confirms tell the outbox relay that the broker accepted the message. This post is the receipt on the other side. RabbitMQ's default consumer behavior is the opposite of what a pharmacy workflow needs: the broker forgets a message the moment it hands it to a consumer. For a packaging worker, "the broker forgot it" means the job is considered done while the worker is still reading the prescription lines out of PostgreSQL — and a crash in that window is not a retry, it is a job that never existed.

This post turns the packaging worker into a correct consumer: manual acknowledgement after the durable effect, prefetch to bound how much work a single slow worker can hold hostage, concurrency to decide how many jobs run in parallel, and an honest map of every crash window. The shape of the answer is the same as the outbox post: choose which failures produce *loss* and which produce *duplicates*, and make the duplicates recognizable.

## Delivery Is Not Processing

AMQP separates three facts that tutorial code blurs:

- **Delivery**: the broker hands the message to a consumer over a channel. Each delivery carries a `deliveryTag` — a number, unique on that channel — that identifies it.
- **Acknowledgement**: the consumer tells the broker, on the same channel, that this delivery is finished.
- **Processing**: the application performed the business effect and made it durable. The broker knows nothing about this. It cannot know; it is not part of the transaction.

Until a message is acknowledged, it is *unacknowledged*: still owned by the broker, counted against the consumer's prefetch, and requeued if the consumer's channel dies. Acknowledgement is the only way the broker learns the consumer is done.

The protocol's default delivery mode is auto-ack: the broker considers the message delivered-and-done the instant it hands it over. There is no failure window, because the window has been designed away — the message simply no longer exists from the broker's point of view. That is correct for telemetry and wrong for work. Packaging is work, and work must not be acknowledged by the broker on delivery.

## The Three Acknowledgement Modes

Spring AMQP exposes the choice on the listener container factory:

```kotlin
@Bean
fun packagingContainerFactory(): SimpleRabbitListenerContainerFactory {
    val factory = SimpleRabbitListenerContainerFactory()
    factory.setConnectionFactory(connectionFactory())
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL)
    factory.setPrefetchCount(10)
    factory.setConcurrentConsumers(4)
    return factory
}
```

| Mode | What Spring does | Pharmacy verdict |
| --- | --- | --- |
| `NONE` | RabbitMQ auto-ack: message acknowledged at delivery | Never for work queues |
| `AUTO` | Ack if the listener method returns normally; nack + requeue if it throws | Closer, but silently wrong if exceptions are swallowed |
| `MANUAL` | Nothing; the code acks or nacks explicitly via the channel | Correct for packaging |

The trap in `AUTO` is subtle and worth stating precisely. Spring does not ack at delivery; it acks after the method returns. That is already better than auto-ack. But "returns normally" is the whole contract: a listener that catches its own exceptions and logs them returns normally, so Spring acknowledges a message whose business effect never happened. The exception handler did not fail; the acknowledgement did. That is silent loss with excellent log output.

`MANUAL` removes the guesswork. The listener receives the channel and the message properties, and the code decides:

```kotlin
@Service
class PackagingWorker(
    private val packagingService: PackagingService,
) {
    @RabbitListener(queues = ["packaging.requests"], ackMode = "MANUAL")
    fun handle(request: PackagingRequest, message: Message, channel: Channel) {
        try {
            packagingService.packagePrescription(request.prescriptionId)
            channel.basicAck(message.messageProperties.deliveryTag, false)
        } catch (ex: Exception) {
            channel.basicNack(message.messageProperties.deliveryTag, false, true)
        }
    }
}
```

Two details in that snippet are not decoration. The `deliveryTag` must be the one from *this* delivery, on *this* channel — acking with a wrong or stale tag is a channel-level error (`PRECONDITION_FAILED`) that closes the channel, and closing the channel requeues every unacknowledged message it carried. And the ack happens *after* the transaction committed: `packagePrescription` is `@Transactional`, so by the time it returns, the packaging effect is durable in PostgreSQL. The order — commit first, ack second — is the rule of this post.

## The Ack-After-Commit Rule

The acknowledgement is a promise to the broker, not to the business. The only order that preserves the packaging effect is: do the work in a durable transaction, commit, then ack. Walk the crash windows and the rule becomes obvious:

| Order | Crash point | Outcome |
| --- | --- | --- |
| Auto-ack | after delivery, before effect | Message gone, work never done — **loss** |
| Ack, then commit | after ack, before commit | Message gone, work never durable — **loss** |
| Commit, then ack | after commit, before ack | Message redelivered, effect repeated — **duplicate** |
| Commit, then ack | after ack | Correct |

The direction of the guarantee: commit-then-ack makes loss impossible and duplicates possible. Ack-then-commit makes duplicates impossible and loss possible. Every tutorial that acks before persisting has chosen the loss side; the question that exposes it is "why is the ack after the transaction?"

The duplicate is not a bug; it is the honest price of at-least-once delivery, exactly as with the outbox relay. The message is redelivered because the broker never learned the consumer finished, and the consumer cannot distinguish "never acked" from "acked, and the ack was lost in the crash". The fix is not a cleverer ack — there is none — it is making the second delivery harmless, which is the inbox and idempotency post. This post's contribution is the rule: the duplicate window must sit *after* the durable effect, never before it. Losing work is a failure of this layer; duplicate work is a failure of the idempotency layer, and the two layers must not be merged.

## Nack, Reject, And Requeue

`basicNack` and `basicReject` are the failure acknowledgement. `basicReject` is the single-message form; `basicNack` adds a `multiple` flag to fail several deliveries at once. The third parameter is the one that decides the failure's fate: `requeue`.

- `requeue = true`: the message returns to the queue and will be redelivered — to any consumer, usually immediately, and on classic queues it lands back near the head.
- `requeue = false`: the message is discarded, or dead-lettered if the queue declares a dead-letter exchange.

The naive worker that nacks with `requeue = true` on every exception has built a poison-message hot loop: a message the worker can never process is redelivered instantly, forever, with no backoff, while healthy messages starve behind it. Requeue is a retry mechanism without a retry budget, which is why the next post replaces it with retry queues and dead-letter exchanges. For now, hold two rules: never requeue blindly on a failure that is probably permanent, and never nack-requeue from a catch-all around code that could throw for a permanent reason.

The `deliveryTag` discipline applies here with more teeth. An ack or nack with a tag that does not exist — already acknowledged, or owned by another channel — raises `PRECONDITION_FAILED` at the channel level. In RabbitMQ, that kills the channel, and every unacknowledged message on it is requeued at once. A buggy acknowledgement handler is therefore not a log line; it is a redelivery event for every in-flight message the channel held.

## Prefetch

Prefetch limits how many *unacknowledged* messages the broker may push to a consumer before waiting for acks. Two defaults matter, and they point in opposite directions:

- The RabbitMQ protocol default is **unlimited**: the broker pushes everything it has until the consumer's unacked count is unbounded.
- Spring AMQP's default is **250 per consumer**.

With unlimited prefetch, one fast packaging worker can pull the entire backlog into its memory while its siblings sit idle; round-robin dispatch cannot balance work that was already handed out. With a prefetch of 1, each consumer holds exactly one in-flight job — the fairest behavior, at the cost of a round trip per message. `setPrefetchCount(10)` is the middle ground: bounded memory, meaningful parallelism per consumer, and slack to smooth per-message latency.

Prefetch only counts unacknowledged messages, which is why it is meaningless under auto-ack (every message is acked instantly at delivery) and essential under manual ack (a message can sit unacked for the entire duration of a slow transaction). The interaction with manual ack is the point: prefetch is the number of *uncertain* jobs a worker is allowed to hold, and an uncertain job is one that will be requeued if the worker dies. With a prefetch of 10 and a crash, the broker reclaims up to 10 messages; with Spring's default of 250, up to 250.

## Consumer Concurrency

Concurrency is a different knob that people collide with prefetch. Where prefetch is messages per consumer, concurrency is consumers per queue. Each concurrent consumer is a separate channel with its own delivery tags and its own prefetch budget, so the effective in-flight capacity is the product:

```text
in-flight = concurrent consumers × prefetch per consumer
4 consumers × 10 prefetch = 40 unacknowledged jobs
```

The `concurrency` attribute on the listener (or `setConcurrentConsumers` on the factory) sets a static count; a range such as `"2-6"` lets the container add consumers while the queue is deep and retire them when it is shallow. For the pharmacy, a small static count is the defensible choice: packaging runs are independent jobs, so competing consumers are safe — the topology post established that — and more consumers are just more parallelism for the same retry story.

The ordering caveat belongs here because interviewers use it to check whether concurrency is understood. Two consumers on one queue receive messages in interleaved order; if a single prescription's events must be processed in sequence, concurrent consumers on a shared queue break that silently. Packaging work items are independent, so concurrency is free. Per-prescription ordering on the event side is a constraint of the idempotency and SSE posts, and it is satisfied by routing and by single-consumer subscriber queues, not by hoping the broker preserves order across channels.

## Redelivery

Redelivery is what the broker does when a delivery ends unresolved. Three triggers matter:

- **The consumer's channel dies**: every unacknowledged message on that channel is requeued.
- **`basicNack` with `requeue = true`**: the message is requeued immediately.
- **The consumer stops cleanly** while holding unacknowledged messages: the channel closes and the broker requeues them, same as a crash.

Every redelivery is a full new delivery: a fresh delivery tag, and a boolean flag `redelivered` set on the message properties. Spring surfaces it as `message.messageProperties.redelivered`. That flag is a single bit, and this is where vague answers die: it tells you the message has been through at least one failed attempt, and nothing else. It does not count attempts. The second redelivery and the fiftieth look identical, because a normal queue tracks no delivery count. A worker that tries to "retry twice if `redelivered`" is not implementing retry; it is rolling a biased die. Counting attempts requires application bookkeeping, or the `x-death` headers that appear only after a message has passed through a dead-letter exchange — both of which belong to the retry post.

## Pitfalls Interviewers Probe

The fastest way to expose tutorial-level understanding is to change one detail and ask what breaks:

- **"Why is `AUTO` not enough?"** — Because it acks on method return, not on durable effect. A swallowed exception inside the listener returns normally, and the message is acknowledged as successful. The demo works; the crash test does not.
- **"What happens if the listener returns without acking?"** — The message stays unacknowledged and the channel stays open; it is redelivered only when the consumer stops or the channel dies. Meanwhile it keeps occupying prefetch capacity, silently throttling the worker.
- **"Can the ack happen on another thread, later?"** — No. Spring closes the channel when the listener method returns, and an ack must arrive on the delivery's own channel. Deferred acking is a Spring anti-pattern; ack inside the method, after the transaction.
- **"What is the difference between prefetch and concurrency?"** — Messages per consumer versus consumers per queue. Together they bound in-flight work; one without the other misleads.
- **"How many messages can be in flight at 4 consumers × 10 prefetch?"** — Forty. The interviewer is checking the multiplication, not the number.
- **"What does `redelivered = true` mean?"** — At least one failed attempt, nothing more. No count, no reason, no budget.
- **"What happens when a worker dies with auto-ack and 20 in-flight messages?"** — Nothing. The messages were acknowledged at delivery. They are gone, and the work with them. That single sentence is why work queues are never auto-ack.
- **"Is nack-requeue a retry strategy?"** — No. It is an unbounded immediate retry with no delay, no budget, and a hot-loop risk for poison messages. Bounded retries are the next post.

## Kotlin And Spring Recap

- Work queues use `AcknowledgeMode.MANUAL`; `NONE` loses work, and `AUTO` loses work whenever the listener swallows an exception.
- The worker commits its `@Transactional` effect first, then calls `basicAck` with the delivery's own `deliveryTag` on the delivery's channel.
- Failure acknowledgements use `basicNack`; `requeue = true` is immediate unbounded redelivery, `requeue = false` discards or dead-letters.
- Prefetch bounds unacknowledged messages per consumer; the protocol default is unlimited, Spring's default is 250, and the deliberate choice for packaging is a small number.
- Concurrency is consumers per queue; each has its own channel and prefetch budget; in-flight capacity is the product of the two.
- `redelivered` is a boolean with no count; retry budgets belong to the dead-letter layer, not to flag arithmetic.

## Interview Review Checklist

Before walking through this design, be able to answer:

- Why is auto-ack wrong for packaging work, exactly what is lost, and where?
- Why must the ack come after the transaction commit, and which failure mode does each ordering avoid?
- What is a delivery tag, why must it match the delivery's channel, and what happens on a mismatch?
- What does `basicNack(tag, false, true)` do to the queue, and why is it not a retry strategy?
- What does prefetch limit, how does it interact with manual ack, and why is Spring's default not a good default for work queues?
- What is the difference between prefetch and concurrency, and how do they jointly bound in-flight work?
- What happens to unacknowledged messages when a consumer's channel closes?
- What does the `redelivered` flag tell you, and what does it not tell you?
- Why is "ack in a `finally` block" wrong, and what should replace it?
- Which failure here produces loss, which produces duplicates, and which layer neutralizes the duplicates?

## Interview Takeaway

The broker's receipt is the acknowledgement, and the worker's own transaction is the receipt it must never trade for the broker's. Commit first, ack second; that ordering is what turns a crash into a duplicate instead of a loss, and duplicates are the failure mode a pharmacy system can actually neutralize. Prefetch and concurrency are the two knobs that decide how much work is in flight and how much can be reclaimed on a crash — the factors of the same accounting. Acknowledge deliberately, bound the unacknowledged, and never mistake a boolean redelivery flag for a retry budget. The next post puts a budget where the flag cannot: retries with delay, dead letters, and poison messages.
