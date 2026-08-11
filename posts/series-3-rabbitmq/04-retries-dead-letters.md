# Retries, Dead Letters, and Poison Messages

The previous post ended with a warning: `basicNack(tag, false, true)` is retry without a budget. This post builds the budget. A failed packaging message needs three decisions that tutorials collapse into one "catch the exception" block: *should it be tried again, how long should it wait, and when do we stop trying and quarantine it instead?* Immediate requeue answers all three with "yes, immediately, never" — which is a hot loop, not a strategy.

This post replaces that with the standard RabbitMQ failure topology: a delayed retry queue that bounces a failed message back into the work queue on a timer, a dead-letter queue that receives the message when the budget is spent, and an honest account of what poison messages are and how to replay them. The through-line is the same as every post so far: the broker records evidence, the application makes policy — and no amount of topology produces exactly-once delivery.

## Classify the Failure Before You Retry It

Retry is a bet that the failure will not repeat. That bet is only worth making on *transient* failures. Classify first, route second — the mechanism is meaningless without the classification.

| Class | Pharmacy example | Retry verdict |
| --- | --- | --- |
| Transient | Inventory row locked by another transaction; PostgreSQL connection blip; barcode service timeout | Retry with delay |
| Transient but slow | Dependency is down for minutes | Retry with delay, then quarantine when budget runs out |
| Permanent | Payload is not valid JSON; `prescriptionId` missing; prescription was cancelled after the request was published | Do not retry. Quarantine immediately |
| Permanent but diagnosed late | A code change makes the handler throw on every delivery | Retries spend the whole budget pointlessly, then quarantine — that is the budget's job |

The classification must be based on the *kind* of failure, not on which attempt it is. A parse error fails identically on attempt one and attempt forty — the retry queue cannot fix a payload, it can only delay the inevitable. That is why the listener in this post decides retryable-versus-permanent from the exception, and why permanent failures skip the retry topology entirely and go straight to quarantine.

```kotlin
enum class FailureClass { RETRYABLE, PERMANENT }

fun classify(ex: Exception): FailureClass = when (ex) {
    is JsonParseException -> FailureClass.PERMANENT
    is PessimisticLockingFailureException -> FailureClass.RETRYABLE
    is PrescriptionNotFoundException -> FailureClass.PERMANENT
    is DependencyTimeoutException -> FailureClass.RETRYABLE
    else -> FailureClass.RETRYABLE
}
```

Interviewers like the `else` clause here. A catch-all that defaults to retryable means every logic bug you ever ship gets three slow retries before it poisons — acceptable. A catch-all that defaults to permanent means transient network failures skip the safety net — unacceptable. When in doubt, retryable.

## The Three Fates of a Failed Message

After the worker decides the message failed, the broker offers three destinations. The previous post showed two of them; the dead-letter exchange is the third:

| Fate | Mechanism | What the broker does | Pharmacy verdict |
| --- | --- | --- | --- |
| Immediate requeue | `basicNack(tag, false, true)` | Requeues to the work queue, delivered again instantly | No delay, no budget, poison hot loop — never |
| Silent discard | `basicNack(tag, false, false)` on a queue with no dead-letter exchange | Message is gone | Unbounded work loss with a clean log line — worst of both |
| Dead-letter | `basicNack(tag, false, false)` on a queue with a dead-letter exchange | Message is routed to the queue bound to that exchange | The only option that preserves the failure for review |

The crucial sentence: *rejection is not retry*. Rejecting with `requeue = false` does not mean "give up"; it means "route this message according to the queue's dead-letter configuration". That configuration decides whether the message enters a retry queue, a quarantine queue, or the void. The worker's job is to choose retryable-versus-permanent correctly; the topology's job is to implement delay and quarantine.

## The Delayed Retry Queue

The delayed retry pattern uses a queue with no consumers and a TTL as a parking lot. When the worker rejects a retryable message, the work queue's dead-letter exchange routes it into the retry queue. Nothing consumes it there — it simply waits — and when its TTL expires, the broker dead-letters it again, back to the work exchange, where it is routed to the work queue and delivered as a fresh attempt:

```text
                     reject (requeue = false)
  packaging.requests ──────────────────────────────► pharmacy.retry
        ▲  ▲                                             │ routing: packaging.requests.retry
        │  │                                             ▼
        │  │                               packaging.requests.retry
        │  │                               (durable, TTL 30s, NO consumers)
        │  │                                             │ TTL expiry → dead-letter
        │  │                                             ▼
        │  └─────────────── pharmacy.work ◄────────────────────┘
        │                   routing: packaging.request
        └── worker receives attempt N + 1 (x-death count incremented)
```

Three components are needed. The retry queue declares where its expired messages go — back to the work exchange:

```kotlin
@Bean
fun packagingRetryQueue(): Queue =
    QueueBuilder.durable("packaging.requests.retry")
        .ttl(30_000)                                       // the retry timer
        .deadLetterExchange(PHARMACY_WORK_EXCHANGE)        // bounce back
        .deadLetterRoutingKey("packaging.request")
        .build()
```

The work queue declares where its rejected messages go — into the retry queue:

```kotlin
@Bean
fun packagingRequestsQueue(): Queue =
    QueueBuilder.durable("packaging.requests")
        .deadLetterExchange(PHARMACY_RETRY_EXCHANGE)
        .deadLetterRoutingKey("packaging.requests.retry")
        .build()
```

And the dead-letter exchange for rejected messages, bound to the retry queue:

```kotlin
@Bean
fun pharmacyRetryExchange(): DirectExchange =
    DirectExchange(PHARMACY_RETRY_EXCHANGE, true, false)
```

Two design points are easy to get wrong. First, the retry queue must have *no consumers* — a consumer on it would defeat the timer by processing before the TTL. Second, a message's destination on rejection is a *queue property*: the work queue's dead-letter exchange and routing key are static, set at declaration. The topology cannot express "retry twice, then give up" — that decision belongs to the consumer, which is exactly why the next section puts the budget in the consumer where the evidence lives.

## Bounding the Budget with x-death

The `redelivered` flag was a single bit with no count. The dead-letter layer fixes that: every time a message is dead-lettered, the broker appends an `x-death` header — a list of entries, one per queue the message has been dead-lettered from, each with `count`, `reason`, `queue`, `time`, `exchange`, and `routing-keys`. `reason` is `rejected`, `expired`, or `maxlen` — which alone is useful forensics: an `expired` entry proves the message actually waited its TTL.

The retry budget is read from broker evidence, not application memory (the worker must never try to count attempts in a hash map — it will not survive a redeploy):

```kotlin
private fun retryAttempts(message: Message): Int {
    val deaths = message.messageProperties.headers["x-death"] as? List<Map<String, Any?>>
        ?: return 0
    return deaths
        .firstOrNull { it["queue"] == "packaging.requests.retry" }
        ?.get("count") as? Int ?: 0
}
```

Then the listener has the full decision tree: commit first, ack second; retryable failures reject into the retry topology; permanent failures and exhausted budgets go to the dead-letter queue. Because the destination on rejection is static, the terminal hop is an explicit publish-then-ack — the worker publishes to the dead-letter exchange itself and acks the original delivery:

```kotlin
@RabbitListener(queues = ["packaging.requests"], ackMode = "MANUAL")
fun handle(request: PackagingRequest, message: Message, channel: Channel) {
    try {
        packagingService.packagePrescription(request.prescriptionId)
        channel.basicAck(message.messageProperties.deliveryTag, false)
    } catch (ex: Exception) {
        val tag = message.messageProperties.deliveryTag
        when {
            classify(ex) == FailureClass.PERMANENT -> {
                errorPublisher.publishToDlq(message)   // publish first...
                channel.basicAck(tag, false)           // ...then ack the original
            }
            retryAttempts(message) >= MAX_RETRIES -> {
                errorPublisher.publishToDlq(message)
                channel.basicAck(tag, false)
            }
            else -> channel.basicNack(tag, false, false) // into the retry topology
        }
    }
}
```

Read the arithmetic precisely: `MAX_RETRIES = 3` means three bounces through the retry queue, so the message is delivered up to four times total — the original delivery plus three retries. That is the number to quote in an interview. And the publish-then-ack on the terminal hop is the same crash-window tradeoff as the outbox: the DLQ publish is not atomic with the ack, so a crash between them redelivers the message and it retries one more time — a duplicate, never a loss. The budget is a ceiling, not a jail.

## Backoff Options and Their Honest Limits

One retry queue with a fixed 30-second TTL is the defensible default for this challenge. "Graduated backoff" — 1 second, then 5, then 30 — is where the pattern's limits appear, and knowing them beats claiming the plugin solves everything:

- **Head-of-line blocking**: on classic queues, TTL expiry applies only to the message at the *head* of the queue. Mixing per-message `expiration` values in one retry queue means one long-delay message at the head stalls every short-delay message behind it. A single fixed queue-level TTL avoids this by construction.
- **Real graduated backoff** needs one retry queue per delay step (each with its own TTL, each dead-lettering back to the work queue) — three parking lots and three hops — or the `rabbitmq-delayed-message-exchange` plugin, which is a broker dependency you must justify on Docker and explain honestly in a walkthrough.
- **Version trap**: the retry queue is declared quorum in the topology post. TTL support on quorum queues arrived in RabbitMQ 3.12 (with `x-max-ttl`/`x-min-ttl` constraints); on older brokers the TTL retry queue must be a classic queue. If the challenge runs a pinned image, the answer is either "quorum everywhere, broker ≥ 3.12" or "work queue quorum, retry queue classic" — not "it works on my machine".
- **The Spring alternative**: `RetryOperationsInterceptor` retries synchronously inside the listener with exponential backoff, then a `RejectAndDontRequeueRecoverer` rejects into the DLQ. Fewer moving parts, and the natural Spring Boot answer — but the worker is *blocked* during each backoff, holding prefetch capacity hostage. Queue-based retry decouples the delay from the worker. For the pharmacy's volume and the challenge's simplicity criteria, either is defensible; what is not defensible is not knowing which one you built.

## The Dead-Letter Queue and Poison Messages

A poison message is a message that can never be processed successfully — the classification missed it (data-dependent bug, schema drift, a cancelled prescription), or the transient problem outlasted the budget. The dead-letter queue exists so that failure becomes *visible and bounded* instead of infinite.

Three broker events route a message into the dead-letter queue: a consumer rejecting with `requeue = false` on a queue whose dead-letter exchange leads there (the `rejected` reason), TTL expiry (the `expired` reason), and queue overflow (the `maxlen` reason). When a message is dead-lettered, its original headers and body are preserved and `x-death` is appended — so a message that has spent its whole budget arrives at the DLQ with the full attempt history attached, which is exactly what an operator needs to decide its fate.

The DLQ should have no production consumers by default. Poison quarantine is not a queue that something eagerly processes; it is a holding area with two operational interfaces:

- **Inspect**: RabbitMQ Management UI or the HTTP API shows depth, headers, and payload; a probe consumer can dump a batch to structured logs with correlation IDs. The operational signal is *DLQ depth* — an alerting rule on the dead-letter queue, not the work queue, is the first thing to build, because work-queue depth looks fine while poison messages pile up invisibly.
- **Replay**: a small replay utility consumes from the DLQ and republishes the original message to the work exchange. Two decisions matter. First, whether to strip `x-death` so the fresh copy starts a new budget, or preserve it so the attempt history stays honest — for the challenge, preserve it and set a low budget, because the first replay is usually a diagnostic rerun, not a fix. Second, replay is a *new publish*: it can be delivered more than once, and it can be routed into the retry topology again. Nothing about replay is exactly-once.

## What Not To Claim

This layer is where overclaiming shows up in interviews, so keep three sentences precise:

- **Retries multiply deliveries, they never reduce them.** Every bounce is another copy of the work, and the inbox/idempotency layer — the next post — is what makes the duplicates harmless. Retry topology without idempotent consumers is just a slower way to corrupt state.
- **The dead-letter queue is quarantine, not durability.** It is a queue like any other: it can be full, its consumer can lag, its broker can crash. The DLQ moves failure from "invisible hot loop" to "observable queue with a count" — that is its entire claim.
- **There is no exactly-once anywhere in this design.** At-least-once delivery, deduplicated by the consumer's own database, is the strongest honest claim, and it is the claim the whole curriculum has been building toward.

## Pitfalls Interviewers Probe

- **"Why not nack-requeue with a `Thread.sleep` before retrying?"** — Sleeping blocks the consumer thread, holds prefetch capacity hostage, and throttles healthy messages through a worker that is doing nothing. The TTL retry queue puts the delay in the broker where it costs nothing.
- **"Where does the retry count live?"** — In `x-death` on the message, from the broker. `redelivered` is a boolean with no count; application-side counters die with the redeploy.
- **"What is in an `x-death` entry?"** — `count`, `reason` (`rejected`/`expired`/`maxlen`), `queue`, `exchange`, `routing-keys`, `time`. One entry per queue the message was dead-lettered from.
- **"Can one retry queue give 1s, 5s, 30s backoff?"** — Not with queue-level TTL; per-message expiration causes head-of-line blocking on classic queues. Graduated backoff needs multiple fixed-TTL queues or the delayed-message plugin.
- **"Why must the retry queue have no consumers?"** — A consumer would process before the TTL fires, converting the parking lot into an eager second queue.
- **"What decides the destination of a rejected message?"** — The work queue's declared dead-letter exchange and routing key. It is static, so the retry-versus-give-up decision cannot live in topology alone; the consumer makes it with x-death evidence.
- **"What happens if the worker crashes between the DLQ publish and the ack?"** — The message is redelivered and retried once more — a duplicate, not a loss. Same crash window as the outbox, same answer: duplicates are neutralized by idempotency.
- **"What is a poison message, exactly?"** — A message that can never succeed: either misclassified permanent, or transient past the budget. The DLQ exists to make that state observable with its attempt history intact.
- **"How do you replay a poison message safely?"** — Consume from the DLQ, republish the original to the work exchange, preserve `x-death` for accounting, and rely on the inbox to neutralize the duplicates the replay creates.

## Kotlin And Spring Recap

- Classify failures before routing them: `classify(ex)` returns `RETRYABLE` or `PERMANENT`; parse errors and not-found are permanent, locks and timeouts are retryable.
- The retry queue is a parking lot: durable, fixed queue-level TTL, no consumers, dead-lettering back to the work exchange.
- The work queue declares a dead-letter exchange that routes rejections into the retry queue; `basicNack(tag, false, false)` is the retry trigger, not `requeue = true`.
- The retry budget is read from `x-death` count for the retry queue; `MAX_RETRIES = 3` means four total deliveries.
- Exhausted or permanent failures publish to the DLQ exchange, then ack the original — a duplicate on crash, never a loss.
- Poison messages are quarantined with their full `x-death` history; inspect depth and headers, replay by republishing, and never claim exactly-once.

## Interview Review Checklist

- How do you decide whether a failure is retryable, and which pharmacy failures land on each side?
- Why is `basicNack(tag, false, true)` worse than the dead-letter topology, precisely?
- Draw the retry topology: what are the exchanges, queues, TTLs, and bindings, and which components have no consumers?
- Where does the retry count come from, and why is `redelivered` insufficient?
- What is head-of-line blocking in a retry queue, and which TTL choices trigger it?
- Why can the destination of a rejected message not be decided per-message by the broker?
- What does `reason = expired` in an `x-death` entry prove, and why is it useful forensics?
- What is a poison message, and what two interfaces does the DLQ expose for it?
- What happens on replay, and why does replay make the idempotency layer mandatory?
- Which of this post's claims are at-least-once, and which would be exactly-once overclaiming?

## Interview Takeaway

Requeue without a budget is a hot loop; the dead-letter layer is how the broker gets a budget. The topology is three decisions made once — a parking lot with a timer, a quarantine area, and a static routing rule — and the consumer fills in the policy: classify the failure, count the attempts in `x-death`, reject into the retry path while the budget lasts, and publish to the DLQ when it does not. Retries and dead letters make failures bounded and observable; they do not make deliveries once. The next post takes the duplicates this layer honestly produces — the crash-window redeliveries, the replay copies — and neutralizes them with idempotent consumers, because in the pharmacy workflow the message's own attempt history is never the patient's last word.
