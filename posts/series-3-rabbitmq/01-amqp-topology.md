# AMQP Topology for Pharmacy Workflow Messages

RabbitMQ is easy to demo and hard to design. The tutorial version gives you one queue, one exchange, and a console that flashes green, which is exactly why the first instinct for the pharmacy challenge is wrong: route every workflow step through a single queue and make each patient SSE connection a consumer of it. That topology works in a demo and collapses in a system-design discussion.

The AMQP model is a routing layer, not a work queue. Before writing any listener code, decide which messages are *work* and which are *facts*, because they require opposite topologies. Work goes to a competing-consumer queue where exactly one worker handles each message. Facts go to an event exchange that fans out to every logical subscriber. Confusing the two is the core RabbitMQ failure mode in this challenge.

This post designs the approval, packaging, fulfillment, and status flows for the pharmacy system and explains the choices an interviewer will probe.

## The Two Message Classes

Every message in the workflow is either:

- **Work**: a command that must be performed exactly once by one worker. "Package prescription 42." It has side effects and can fail, so it needs retries, dead-lettering, and manual acknowledgement.
- **Fact**: a domain event that already happened. "Prescription 42 was approved." It is immutable, append-only, and interesting to multiple subscribers.

The same RabbitMQ primitives serve both, but the topology differs:

| Concern | Work | Fact |
| --- | --- | --- |
| Number of handlers | One (competing consumers) | Many (fan-out) |
| Failure model | Retry and dead-letter | At-least-once delivery |
| Content | Command payload | Event envelope with stable ID |
| Exchange choice | Direct (or topic) | Topic or fanout |
| Queue ownership | One queue per work type | One queue per subscriber |

Patient status updates are facts. Packaging is work. If the status stream is forced through a work queue, the system starts dropping or misrouting patient updates, which is precisely the mistake this series corrects.

## AMQP Primitives In One Page

RabbitMQ does not deliver messages from publishers directly to consumers. Three primitives sit in between:

- **Exchange**: a routing table. Publishers send messages to an exchange; the exchange decides where they go.
- **Queue**: a buffer that holds messages until a consumer takes them. Queues are where messages actually live.
- **Binding**: a rule connecting an exchange to a queue, usually with a routing key.

A message has a routing key chosen by the publisher. The exchange type decides how that key is matched against bindings:

- **Direct**: exact match. Routing key `packaging.request` reaches only queues bound with `packaging.request`.
- **Topic**: pattern match with `*` (one word) and `#` (zero or more words). `prescription.#` binds every `prescription.*` event.
- **Fanout**: ignores the routing key entirely and copies the message to every bound queue.
- **Headers**: matches on message headers instead of the routing key. Rarely the right default; use it only when the contract is genuinely header-driven.

For the pharmacy system, two exchanges cover the design: a direct exchange for work commands and a topic exchange for domain facts. Fanout earns its place only when the subscriber set is unknown or deliberately unrestricted, such as a god-mode operational log that any future tool may join.

## The Pharmacy Topology

The flows from the challenge map cleanly onto this design:

1. Submission and inventory reservation stay synchronous REST (series 2 covers the transaction).
2. Pharmacist approval writes the approval and creates a `packaging.request` work item.
3. Packaging workers compete for `packaging.request` messages; completion creates a `fulfillment.request`.
4. Fulfillment workers mark the prescription fulfilled.
5. Every status change also emits a `prescription.*` fact that any status or notification subscriber can receive.

```text
                     pharmacy.work (direct)
                     routing keys: packaging.request, fulfillment.request

  outbox relay ---> [packaging.request] --> packaging workers (competing)
                     [fulfillment.request] -> fulfillment workers (competing)

                     pharmacy.events (topic)
                     routing keys: prescription.approved, prescription.packaging,
                                   prescription.ready, prescription.fulfilled,
                                   prescription.rejected

  outbox relay ---> [pharmacy.notifications] --> status/notification subscriber
                     binding: prescription.#
```

The two exchanges deliberately separate traffic. Work messages carry commands and must not be duplicated; fact messages carry history and must not be lost. If packaging commands and status facts shared one queue, a packaging worker would silently consume patient events, and no single consumer would see the full event stream.

## Approval To Packaging: A Work Flow

Approval is the trigger for the first async step. The approval transaction inserts the state change and the outbox event; the relay (covered in the next post) publishes to `pharmacy.work` with routing key `packaging.request`.

```kotlin
object RoutingKeys {
    const val PACKAGING_REQUEST = "packaging.request"
    const val FULFILLMENT_REQUEST = "fulfillment.request"
}

data class PackagingRequest(
    val prescriptionId: UUID,
    val packagingRunId: UUID,
    val medicationLines: List<Line>,
)
```

The packaging queue is declared durable so work survives a broker restart, and the message is published persistent. Multiple simulated packaging workers consume the same queue. That is the competing-consumers pattern, and it is correct here for a specific reason: a packaging run has exactly one legal executor. RabbitMQ guarantees each message is delivered to only one consumer of the queue, so two workers cannot package the same prescription twice by construction — they could still both be told to retry a duplicate delivery, which is why idempotency and the inbox pattern matter in the later posts.

The competing pattern also lets the team scale packaging without changing any code: add a second worker container and RabbitMQ balances the load. Prefetch (covered in the dedicated post) keeps one worker from hoarding the queue; for now, the topology decision is that packaging is a queue, not an exchange fan-out.

Packaging completion publishes `fulfillment.request` through the same work exchange. Fulfillment is a separate queue and separate consumer group because it has a different owner, failure profile, and retry budget. Work queues are named for the work they hold, not the worker that happens to run today.

## Status Changes: An Event Flow

Every state change also produces a fact. The relay publishes it to `pharmacy.events` with a routing key that encodes the event type:

```kotlin
const val ROUTING_KEY_APPROVED = "prescription.approved"
const val ROUTING_KEY_PACKAGING = "prescription.packaging"
const val ROUTING_KEY_READY = "prescription.ready"
const val ROUTING_KEY_FULFILLED = "prescription.fulfilled"
const val ROUTING_KEY_REJECTED = "prescription.rejected"

data class PrescriptionEvent(
    val eventId: UUID,
    val prescriptionId: UUID,
    val status: String,
    val sequenceNumber: Long,
    val occurredAt: Instant,
)
```

A logical subscriber such as the notification service declares its own durable queue and binds it with a topic wildcard:

```kotlin
@Bean
fun notificationsQueue() = Queue("pharmacy.notifications", true)

@Bean
fun notificationsBinding(notificationsQueue: Queue, eventExchange: TopicExchange) =
    BindingBuilder.bind(notificationsQueue).to(eventExchange).with("prescription.#")
```

Topic exchanges give subscribers a scoped contract without coupling them to publishers. The notification service binds `prescription.#` and sees everything. A future patient-activity analytics consumer could bind `prescription.#` too and never disturb the existing one, because each subscriber owns a separate queue. Adding subscribers requires no publisher change; that is the defining property of fan-out.

The `eventId` and `sequenceNumber` are not decoration. The stable ID lets any subscriber deduplicate at-least-once delivery against an inbox, and the per-prescription sequence supports ordering and SSE replay in the later posts. The routing key tells the broker where the fact goes; the envelope tells the consumer what it means.

## Why Patient Updates Are Not Competing Consumers

This is the design correction the coach assessment calls out explicitly: do not make each patient SSE connection a RabbitMQ consumer, and do not serve patient updates from a shared work queue.

The competing-consumer pattern sends each message to exactly one consumer of a queue. If patient status events are delivered to a shared queue and each SSE connection is a consumer, the broker gives prescription 42's update to whichever connection happens to be free — possibly patient 7's browser. The update is delivered once and to the wrong recipient, which is both a correctness bug and a patient-data isolation failure. If instead each SSE connection gets its own exclusive queue, the system creates a queue per browser tab and the broker becomes a session manager it was never meant to be.

The mismatch runs deeper:

- **Lifecycle**: queues outlive connections. A patient's SSE reconnect creates a new consumer with no memory of what was acknowledged; ordering and replay become unknowable from the broker alone.
- **Authorization**: the broker does not know which patient may read which stream. Per-connection filtering must happen in the application anyway, so the consumer role buys nothing.
- **Availability**: a queue with zero connected consumers is a queue with zero delivery. If the patient's browser is closed, the status update is gone, whereas a projection store can replay it on reconnect.

The correct topology is the one shown above: status facts fan out to *logical* subscribers (durable queues with at-least-once semantics), and the SSE layer reads from an application-side projection or event store, never from the broker. SSE connections are clients of your service, not consumers of your queue. The dedicated SSE post will build this on `Last-Event-ID` and per-prescription sequence numbers; the topology decision here is that the event exchange exists so the projection has a complete, ordered, replayable stream to read from.

## Durability And Queue Types

Durability is three separate decisions that interviewers intentionally blur:

- **Durable exchange**: survives broker restart (declarations survive). Always true in this design.
- **Durable queue**: the queue itself survives restart. Needed for work queues and subscriber queues; if you are OK losing unprocessed work on restart, you are shipping a toy.
- **Persistent message**: the message is written to disk before the publisher gets a confirm. Combined with a durable queue, this gives survival across broker restarts.

The default `Queue(name, durable)` constructor in Spring AMQP makes durability explicit. Autodelete and exclusive flags are for temporary queues, which are fine for one-off testing and wrong for workflow infrastructure.

Queue type is a separate axis. Classic queues are the default in local Docker Compose; quorum queues add replication and data safety for production and are the RabbitMQ-recommended replacement for mirrored classics. For a 2-5 hour challenge, the defensible position is: run quorum queues in production, use classic queues in a single-node Docker Compose for speed, and say exactly that in the README. What is indefensible is silently relying on classic single-node behavior and calling it durable.

```kotlin
@Bean
fun packagingQueue() = Queue("packaging.requests", true, false, false, mapOf("x-queue-type" to "quorum"))
```

The extra arguments show intent. `true` durable, `false` exclusive, `false` autodelete, and quorum as the declared type. If the local broker is single-node, quorum queues still work; they simply carry more overhead than a classic queue would.

## Declaring Topology In Kotlin And Spring

Spring AMQP gives two ways to define topology: bean declarations at startup or manual `RabbitAdmin` operations. For a challenge, bean declarations are readable, idempotent, and tested by every application start:

```kotlin
@Configuration
class RabbitTopology {

    @Bean
    fun workExchange() = DirectExchange("pharmacy.work", true, false)

    @Bean
    fun eventExchange() = TopicExchange("pharmacy.events", true, false)

    @Bean
    fun packagingQueue() = Queue("packaging.requests", true)

    @Bean
    fun fulfillmentQueue() = Queue("fulfillment.requests", true)

    @Bean
    fun notificationsQueue() = Queue("pharmacy.notifications", true)

    @Bean
    fun packagingBinding() =
        BindingBuilder.bind(packagingQueue()).to(workExchange()).with(RoutingKeys.PACKAGING_REQUEST)

    @Bean
    fun fulfillmentBinding() =
        BindingBuilder.bind(fulfillmentQueue()).to(workExchange()).with(RoutingKeys.FULFILLMENT_REQUEST)

    @Bean
    fun notificationsBinding() =
        BindingBuilder.bind(notificationsQueue()).to(eventExchange()).with("prescription.#")
}
```

Consumers attach by queue name, keeping listener code independent of routing details:

```kotlin
@Service
class PackagingWorker(
    private val prescriptionRepository: PrescriptionRepository,
) {
    @RabbitListener(queues = ["packaging.requests"])
    fun handle(request: PackagingRequest) {
        // package the lines, then record READY + outbox event in one transaction
    }
}
```

The listener is deliberately quiet about acknowledgement: the next posts cover manual ack, prefetch, and the ack-after-commit rule. The topology point is that the worker binds to the *queue*, and the queue's binding key is the contract. If packaging is reprioritized later, only the binding changes.

## Topology Hygiene And Common Mistakes

The design survives as long as these habits hold:

- **Own the topology once.** Declare queues, exchanges, and bindings in one configuration class so the broker state is reproducible from code. Declaring queues ad hoc inside a listener constructor produces drift that is invisible until a restart.
- **Name with intent.** `pharmacy.work` and `pharmacy.events` encode scope and purpose. A queue named `mq1` or `data-queue` explains nothing and makes the next on-call engineer reverse-engineer the system.
- **Do not mix work and facts.** A queue consumed by packaging workers should never carry status facts, or the facts silently vanish into a side effect nobody ordered.
- **Bind with the minimum wildcard.** `prescription.#` is correct for a general status subscriber; a consumer that only needs approvals should bind `prescription.approved` and document why, so the topology stays reviewable.
- **Do not publish to a missing exchange.** The exchange is a routing table; a publish to a non-existent exchange is a channel error, not a queue that silently appears. The outbox relay post covers how publisher confirms surface this.
- **Reserve fanout for broadcast.** Use fanout only when every subscriber must receive every message regardless of topic — the god-mode log is a legitimate case. Defaulting to fanout everywhere makes routing keys meaningless and subscribers unselectable.
- **Leave retries and DLQs to their own topology.** The retry queues and dead-letter exchanges of the later post are part of the design from day one, but they belong to the failure-handling layer, not the happy-path exchange set above.

## Interview Review Checklist

Before walking through this design, be able to answer:

- What is the difference between a work message and a fact message, and which topology does each need?
- Why is packaging served by a competing-consumer queue while status events are fanned out?
- Why must each logical subscriber have its own queue, and what breaks if two subscribers share one?
- Why should an SSE connection never be a RabbitMQ consumer, and what happens if it is?
- What do durable queue, persistent message, and quorum queue each protect against, and what do they not protect against?
- Which exchange types are used in this design, and where would fanout genuinely be appropriate?
- How would adding a second packaging worker change delivery behavior, and why is that safe only with idempotent handling?
- Why does the event envelope carry `eventId` and `sequenceNumber` if the routing key already identifies the event?

## Interview Takeaway

A RabbitMQ topology is a contract, not plumbing. Decide first whether each message is work to be executed once or a fact to be shared with everyone, and the exchange and queue choices follow mechanically: direct exchange plus competing-consumer queue for work, topic exchange plus one queue per logical subscriber for facts. Keep patient updates out of the work queues entirely and out of the broker-consumer model; fan them out to a projection that the SSE layer can replay. Then the remaining RabbitMQ posts can build reliability on top of this shape without redrawing it: confirms and the outbox relay in the next post, acknowledgement and prefetch after that, and retries and dead letters last.
