# R01 Topology Scratchpad — Code-Along Elective

## Objective

Declare a durable RabbitMQ topology — two exchanges, two queues, bindings with routing keys — in a single Spring configuration class, then prove it exists and survives a broker restart using the Management UI and the HTTP API. You learn to think of topology as a contract you own, not plumbing that appears by accident.

## Time box

~1.5 hours. Core (everything later in the Rabbit track assumes this topology).

## Prerequisites

- `../glue/X01_docker_compose_trio.md` (or any local Docker Compose that runs `rabbitmq:3.13-management` with the Management UI on port 15672). No cloud broker.
- A minimal Spring Boot module with `spring-boot-starter-amqp` and Jackson on the classpath. If you do not have one yet, create a single-module Gradle project with just that starter.
- Showcase position: before/during `../../pharmacy-fulfillment/exercise_03_production.md` — this is the Milestone 2/3 topology, built in isolation first.

## Blog & curriculum links

- Primary: `../../../posts/series-3-rabbitmq/01-amqp-topology.md` — the two message classes (work vs fact), exchange types, durability, the pharmacy topology diagram.
- Secondary: `../../../posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md` — the topology embedded in the end-to-end journey.
- Coach-assessment gap: RabbitMQ is conceptual/tutorial-level; this kata converts the exchange/queue/binding vocabulary into broker-visible evidence.

## Background & motivation

Tutorials make topology invisible: a default exchange, one queue, and the console flashes green. The pharmacy challenge fails exactly there — a single queue carrying both packaging commands and patient facts collapses when an interviewer asks "which consumer owns this message, and what if two subscribe?" This kata exists to make the primitives physical: you declare them, you look at them in the UI, you restart the broker and watch what survives. Everything after this — confirms, acks, retries, outbox — hangs on these flags and bindings being deliberate.

It deliberately ignores: consumers, acknowledgements, confirms, retries, and Postgres. No delivery guarantee is at stake yet; you are only learning where messages *go*.

## Learning objectives

- Distinguish the two message classes (work vs fact) and pick the exchange type each needs.
- Declare durable exchanges, queues, and bindings idempotently in Kotlin Spring config.
- Separate the three durability axes: durable exchange, durable queue, persistent message.
- Verify topology by behavior (a message arriving at a queue) and by Management UI/HTTP API evidence.
- Demonstrate the two routing failures: publish to a missing exchange, and an unroutable publish with the mandatory flag.

## Warm-up (3 min)

Read the "The Pharmacy Topology" and "Durability And Queue Types" sections of `../../../posts/series-3-rabbitmq/01-amqp-topology.md`. Then open http://localhost:15672 (guest/guest) and click through Exchanges, Queues, and the default `amq.*` entries — you are looking at what you are about to build.

## System specification

- **Scope in:** one Spring configuration class that declares `pharmacy.work` (direct) + `pharmacy.events` (topic); `packaging.requests` and `pharmacy.notifications` queues; bindings `packaging.request` and `prescription.#`. A tiny probe that publishes messages. Broker evidence.
- **Scope out:** any consumer logic, ack modes, confirms, retry topology, Postgres, a second application.
- **Functional requirements:** declarations must be idempotent (safe to restart the app); queues durable; a probe publish must land in the expected queue; an unroutable mandatory publish must surface visibly.
- **Constraints:** local Docker RabbitMQ only; single module; Spring AMQP bean declarations (no `rabbitmqadmin` scripting as the deliverable — script is only for evidence).

## Step-by-step code-along

1. **Do:** Create a `RabbitTopology` configuration class declaring two exchanges and two queues with Spring AMQP builders — `DirectExchange("pharmacy.work", true, false)`, `TopicExchange("pharmacy.events", true, false)`, `Queue("packaging.requests", true)`, `Queue("pharmacy.notifications", true)` — plus three `Binding` beans (`packaging.request` → work, and `prescription.#` → notifications). Use `val` and constructor injection — a Java veteran should notice these are properties, not getters.
   **Run:** `./gradlew bootRun`. **Observe:** startup logs succeed; nothing exists in the broker yet until declarations land.
   **Decision:** durable flags on every exchange and queue — `true, false` is durable + non-auto-delete. Nudge: work queues must survive a broker restart, so there is no defensible `false` here.

2. **Do:** Add a third queue + binding — a `fulfillment.requests` work queue bound with `fulfillment.request` on `pharmacy.work`.
   **Run:** restart the app. **Observe:** re-declaring the same topology logs no errors — declarations are idempotent. Now look at the Management UI → Exchanges: you see `pharmacy.work`, `pharmacy.events`; → Queues: three durable queues. Screenshot this state; it is your first evidence artifact.

3. **Do:** Write a small probe (a `CommandLineRunner` or a one-off `RabbitTemplate` call, not a real feature) that publishes a JSON message to `pharmacy.work` with routing key `packaging.request`. Give the payload an `eventId`, `prescriptionId`, and `status` — the envelope shape from the topology post.
   **Run:** run the probe. **Observe:** Management UI → Queues → `packaging.requests`: `Messages ready` = 1, payload visible under "Get message". The message is *routed*, not yet processed — nobody consumed it. That distinction (routed ≠ processed) is the interview checkpoint of this step.

4. **Do:** Publish a *second* copy of the same message to `pharmacy.events` with routing key `prescription.approved`.
   **Run:** run, then check `pharmacy.notifications` in the UI. **Observe:** it arrived there via the `prescription.#` binding. One logical event, two queues, two message classes — work vs fact, kept apart. Screenshot both queues with payloads.

5. **Do:** Restart the RabbitMQ container (`docker compose restart rabbitmq` — not the app). Then restart the app.
   **Run:** inspect the UI again before the app restarts. **Observe:** exchanges and queues are still present *before* the app redeclares them — durable declarations survived. This is the difference between durable and auto-delete/exclusive. Evidence: screenshot with the app stopped.

6. **Do:** Add a `ReturnCallback` by enabling returns on the template (`template.setMandatory(true)` + `setReturnsCallback { ... }`), then publish with routing key `packaging.famous` — no binding matches.
   **Run:** run the probe. **Observe:** your callback logs the returned message; the UI shows no new message anywhere. Without the mandatory flag this publish would vanish silently — that silent drop is exactly what the relay post calls "moment 3" failing.

## Try this

Deliberate break #1: publish to `pharmacy.workx` (typo). With Spring AMQP, a publish to a missing exchange is a channel-level error — the channel is closed, and the log shows it. Observe that the app recovers (or does not, depending on your configuration) and that no queue grew. Deliberate break #2: declare a queue *without* the durable flag (or with `exclusive = true`), restart the broker, and watch it disappear while `packaging.requests` survives. Two failure modes, two screenshots.

## Trade-off fork

**Option A — direct exchange for work commands.** Exact match (`packaging.request` only reaches `packaging.requests`). A binding typo is loud: nothing arrives.
**Option B — topic exchange for work commands.** Wildcards (`packaging.#`) let you bind a test queue to "everything packaging" without touching the worker binding — handy for debugging and for a god-mode audit log.

Pick one for `pharmacy.work` and write 3–5 lines justifying it: what do you gain, what do you lose (routing-key discipline? error loudness? flexibility?), and which property of the pharmacy workload drives your choice? There is no single winner here — the curriculum uses direct for work and topic for facts, but you should be able to defend the *losses* either way.

## Hints

- **Hint 1:** If queues do not appear in the UI after startup, check that the app actually connected: a failed connection to localhost:5672 means Spring logs `"Connection refused"` and no declarations are made. Verify `spring.rabbitmq.host/port` in `application.yml`.
- **Hint 2:** For the mandatory-flag step: Spring AMQP needs both `setMandatory(true)` on the `RabbitTemplate` *and* a returns callback wired via `setReturnsCallback`; the callback fires on the template's own thread, so log with the routing key in the message. If you use `convertAndSend(exchange, key, obj)` the payload needs a `MessageConverter` — Spring Boot auto-configures Jackson for you; a `data class` maps cleanly.

## Checkpoint / success criteria

You may leave when:

- The UI shows exactly the topology you declared (2 exchanges, 3 queues, 3 bindings) and screenshots exist.
- A probe message arrives in `packaging.requests` and `pharmacy.notifications`, payload intact, after an app restart.
- `packaging.requests` and its message survive a full broker restart.
- A publish to a missing exchange produced a visible channel error, and a mandatory unroutable publish produced a `ReturnCallback` log.
- You can say in one sentence what "durable" protects and what it does not (it does not protect a non-persistent message).

## Bottleneck & reflection questions

1. Where does this topology lose a message today — and does it *know* it lost it? (Answer both, then connect to why confirms/returns exist.)
2. If a second subscriber joins for patient facts, what must change in the topology and what must not change in the publisher?
3. Your packaging queue bound `prescription.#` by accident — what breaks silently, and why is this a patient-experience bug, not a plumbing bug?
4. Which of the three durability axes did you actually prove in this kata, and which did you only assert?
5. Why is the topology the right place to invest 30 minutes before writing a single listener?

## Handoff

- Next: `R02_fire_and_forget_publisher.md` (publish and inspect) and `R03_manual_ack_consumer.md` (the consumer that makes routing matter).
- Related showcase: `../../pharmacy-fulfillment/exercise_03_production.md`, Milestones 2 and 3 — the same two exchanges and three queues appear there.
- Interview line to say aloud: *"I separate work from facts: a direct exchange with competing-consumer queues for packaging and fulfillment commands, a topic exchange with a `prescription.#` subscriber queue for status facts, all durable and declared idempotently in one configuration class — and I verified the bindings by behavior, not by declaration."*

## Optional stretch

Declare `packaging.requests` as a **quorum queue** (`QueueBuilder.durable("packaging.requests").build()` — then read the "Durability And Queue Types" section of the topology post for the `x-queue-type` argument). Restart the broker and compare what survives and what the UI shows differently in the queue's "Type" column. One paragraph in your notes: why quorum changes the durability *story* even though the local single-node broker cannot demonstrate replication.
