# R02 Fire-and-Forget Publisher — Code-Along Elective

## Objective

Publish JSON messages to your R01 topology with explicit exchange and routing key, watch them land in the Management UI, and prove what broker acceptance means — including the moment you enable publisher confirms. Primary objective: a correct, observable publish path and the vocabulary to say what a confirm does and does not prove.

## Time box

~1 hour. Core — R06 and R07 both assume you can publish against a known topology.

## Prerequisites

- `R01_topology_scratchpad.md` — the `pharmacy.work` / `pharmacy.events` topology must exist and be proven.
- `../glue/X01_docker_compose_trio.md` for the broker with Management UI.
- Showcase position: before/during `../../pharmacy-fulfillment/exercise_03_production.md` (the relay and outbox milestones reuse exactly this publish call).

## Blog & curriculum links

- Primary: `../../../posts/series-3-rabbitmq/01-amqp-topology.md` — where publishes go.
- Secondary: `../../../posts/series-3-rabbitmq/02-publisher-confirms-outbox.md` — the "Four Moments" table; a confirm proves broker acceptance (moment 2), not routing (moment 3) and not processing (moment 4).
- Coach-assessment gap: converting "publisher confirms" from concept to observed behavior.

## Background & motivation

Tutorial publishing is `convertAndSend(queueName, obj)` — one argument, zero thought, and a silent assumption that a sent message is a delivered message. The pharmacy challenge punishes that assumption in the interview: a publish to the *queue* bypasses the exchange model, a publish to a *missing exchange* kills the channel, an *unroutable* publish vanishes, and a *confirmed* publish only proves the broker took responsibility. This kata makes each of those visible in the UI and the logs so the four moments stop being abstract. It deliberately ignores consumers, acks, retries, and the outbox — delivery is fire-and-forget here on purpose, so that R06 can show you what fire-and-forget costs.

## Learning objectives

- Publish a typed Kotlin `data class` as JSON with an explicit exchange + routing key.
- Read the UI state that proves routing happened: queue depth, payload, properties.
- Distinguish the four moments (commit → broker acceptance → routing → processing) with evidence for the middle two.
- Enable publisher confirms and correlate a confirm back to your own `eventId`.
- Explain the persistent-message flag and what a confirm does not guarantee.

## Warm-up (2 min)

Re-read the "Four Moments" table in `../../../posts/series-3-rabbitmq/02-publisher-confirms-outbox.md`. Then, in the Management UI, open your `packaging.requests` queue from R01 and click "Get message" once — note the "redelivered" and "exchange/routing_key" columns you are about to influence.

## System specification

- **Scope in:** a publisher component that serializes a `PackagingRequest`/`PrescriptionEvent` envelope to JSON and publishes to `pharmacy.work` / `pharmacy.events` with the R01 routing keys; publisher confirms enabled with a confirm callback; evidence (UI screenshots, confirm logs).
- **Scope out:** consumer code (R03), the outbox table (R07), retry topology (R04), Postgres.
- **Functional requirements:** every publish carries a stable `eventId`; a confirm log line appears per publish; messages are visible in the target queues with the JSON payload.
- **Constraints:** local Docker RabbitMQ; single module; Spring Boot auto-configured `RabbitTemplate`; do not use the default exchange (always name the exchange and key).

## Step-by-step code-along

1. **Do:** Define the envelope as a Kotlin `data class` — `eventId: UUID`, `prescriptionId: UUID`, `status: String`, `occurredAt: Instant` (and, for work, a `packagingRunId`). Kotlin idiom for a Java veteran: the primary constructor *is* the class — no getters, no builders, Jackson maps it via the property names.
   **Run:** compile. **Observe:** nothing yet; this step is about the contract.

2. **Do:** Write a `PrescriptionPublisher` service with one method: `fun publishPackagingRequest(rx: PrescriptionEvent)` that calls `rabbitTemplate.convertAndSend("pharmacy.work", "packaging.request", rx)`. Drive it from a `CommandLineRunner` that publishes one message on startup (you will replace this with real triggers in the showcase).
   **Run:** `./gradlew bootRun`. **Observe:** Management UI → `packaging.requests` → Messages ready = 1; expand "Get message": payload is your JSON, `delivery_mode` is 2 (persistent), exchange and routing_key show `pharmacy.work` / `packaging.request`. Screenshot — this is routing evidence.

3. **Do:** Publish a second message to `pharmacy.events` with key `prescription.approved`.
   **Run:** run again. **Observe:** `pharmacy.notifications` also has 1 message. Two queues, two message classes, one envelope shape — the R01 contract working through a real publisher. Note that *nothing consumed either message*; queue depth is the honest signal that "routed, not processed."

4. **Do:** Turn on confirms: set `spring.rabbitmq.publisher-confirm-type: correlated` in `application.yml`, and add a `setConfirmCallback { correlationData, ack, cause -> ... }` on the template that logs the `correlationData.id` and the ack outcome. Pass `CorrelationData(rx.eventId.toString())` as the last argument of `convertAndSend`.
   **Run:** restart and publish. **Observe:** a confirm log per message with your `eventId` as the id — the broker's receipt, matched to *your* identity because no broker-generated handle survives the round trip. If the broker is up, all acks; kill the broker and publish again: nacks/timeouts. Screenshot both log lines.

5. **Do:** Make the message persistent explicitly. Default Spring AMQP persistence is usually what you want, but set `MessageDeliveryMode.PERSISTENT` on the `MessageProperties` in one publish and compare `delivery_mode` in the UI for both messages.
   **Run:** publish both variants. **Observe:** same `delivery_mode = 2`; then restart the broker *with messages still in the queue* and confirm both survive. Evidence: pre/post-restart screenshots.

## Try this

Break it: temporarily set the routing key to `packaging.typo` with the mandatory flag off (R01 step 6 showed the return path — here remove it). Publish. Observe in the UI: no message anywhere, no error, no log. The message is gone and the system is unaware. This is precisely why the relay in `R07_outbox_relay_mini.md` treats "publish returned no error" as *not proof of routing* — write the one-sentence lesson in your notes: a silent drop is worse than a loud failure because it needs no code path to survive.

## Trade-off fork

**Option A — confirms everywhere.** Every publish (even the future SSE fan-out, demo probes, admin actions) goes through a correlated confirm callback.
**Option B — confirms only in the relay.** The happy-path publisher stays fire-and-forget; only the outbox relay (R07) pays the confirm cost.

Pick one and write 3–5 lines: what latency and code complexity does A add, what blind spot does B keep (which publishes can lose a message without anyone knowing?), and which moments does each option make observable? The curriculum's answer is "at least the relay," but the interview question is whether you can name what the rest of the system gives up.

## Hints

- **Hint 1:** Confirm callbacks arrive asynchronously on a template-owned thread. If your log lines feel unordered, add the `eventId` to the log message itself instead of relying on order. The callback signature is `(correlationData: CorrelationData?, ack: Boolean, cause: String?)`.
- **Hint 2:** If you pass `CorrelationData` but never see callbacks, check both: `spring.rabbitmq.publisher-confirm-type: correlated` *and* that the publish uses the auto-configured template (not a manually built one that bypasses the factory's confirm setup). A nack's `cause` string is your evidence that the broker refused — log it, don't swallow it.

## Checkpoint / success criteria

You may leave when:

- Two queues hold your JSON messages with correct exchange/routing-key headers, screenshotted.
- Every publish produced a confirm callback logged with the matching `eventId`, including a negative case (broker stopped).
- Persistent messages survive a broker restart while sitting unconsumed.
- You can complete the sentence: "A publisher confirm proves ____; it does not prove ____ or ____."

## Bottleneck & reflection questions

1. You published 100 messages and the UI shows 97 in the queue. Where could three have gone, in order of likelihood, and how would each failure have surfaced?
2. A confirm arrives with `ack = false`. What can you safely do next — republish immediately? How many times? (This is the seed of the retry and outbox decisions.)
3. If the patient's notification facts are published by this same fire-and-forget path, what is the patient-experience failure when a publish silently drops? How would you *detect* it?
4. Why does the relay in the blog reuse the outbox `event_id` as the `CorrelationData` id instead of generating a per-publish id?
5. Which moment in the Four Moments table did this kata prove, and which did it deliberately leave to R03?

## Handoff

- Next: `R06_dual_write_failure_demo.md` (the failure this publisher hides) and `R07_outbox_relay_mini.md` (publisher confirms doing real work).
- Related showcase: `../../pharmacy-fulfillment/exercise_03_production.md`, Milestone 2 — the relay's publish call is this step with a claim transaction around it.
- Interview line to say aloud: *"I treat broker acceptance, routing, and processing as three separate moments: my publisher confirms prove the broker accepted the message, the mandatory flag makes unroutable publishes visible, and nothing in my code claims a confirmed message was processed — that is the consumer's contract, covered by manual acknowledgement."*

## Optional stretch

Add the mandatory flag + `setReturnsCallback` permanently to your publisher and build a tiny routing-failure report: for each returned message, log the exchange, routing key, and reason. Then publish one unroutable message and one routable message, and screenshot the two log lines — you now own the evidence for moment 3, which the relay will reuse verbatim.
