# R03 Manual Ack Consumer — Code-Along Elective

## Objective

Turn a packaging queue listener into a correct worker: manual acknowledgement after a durable effect, bounded prefetch, deliberate concurrency, and an observed redelivery when the worker dies between the effect and the ack. Primary objective: prove with broker evidence that commit-then-ack turns a crash into a duplicate, never a loss.

## Time box

~2 hours. Core — R04, R05, and R06 all depend on this acknowledgement behavior.

## Prerequisites

- `R01_topology_scratchpad.md` — the `packaging.requests` queue and its binding.
- `R02_fire_and_forget_publisher.md` (nice to have: a way to publish test messages; a one-line `rabbitTemplate.convertAndSend` in a test also works).
- `../glue/X01_docker_compose_trio.md` for the broker.
- Showcase position: before/during `../../pharmacy-fulfillment/exercise_03_production.md` Milestone 3 ("acknowledge only after durable effects commit").

## Blog & curriculum links

- Primary: `../../../posts/series-3-rabbitmq/03-ack-prefetch-concurrency.md` — the three ack modes, the ack-after-commit rule, prefetch, redelivery.
- Secondary: `../../../posts/series-3-rabbitmq/06-operational-testing.md` — "Redelivery And The Commit-Then-Ack Window" (the latch-based crash test you can replay by hand).
- Coach-assessment gap: manual acknowledgements and delivery tags were conceptual; here they become log lines and UI numbers.

## Background & motivation

RabbitMQ's default is to forget a message the instant it hands it over. For a packaging worker, "forgotten" means a crash mid-effect is not a retry — it is work that never existed, and the patient waits while nobody is ever told. The fix is one ordering rule: do the durable effect, commit, then `basicAck`. This kata exists to make that rule *observable* — you will watch a message redeliver because you killed the worker in the gap, and you will watch a swallowed exception turn `AUTO` mode into silent loss. It deliberately ignores retries (R04), idempotency (R05), and the outbox (R07): you are studying the consumer side of the receipt in isolation, and the duplicate this layer honestly produces is someone else's job to neutralize.

## Learning objectives

- Switch a listener to `AcknowledgeMode.MANUAL` and ack with the delivery's own `deliveryTag` on its own channel.
- Order work correctly: durable effect first, ack second, and defend that order.
- Set prefetch and concurrency on a `SimpleRabbitListenerContainerFactory` and explain the product `consumers × prefetch` as in-flight work.
- Observe redelivery in the Management UI and in consumer logs after a channel death.
- Explain why `AUTO` mode can still lose work, and why `redelivered` is a boolean, not a retry count.

## Warm-up (3 min)

Read "The Ack-After-Commit Rule" table in `../../../posts/series-3-rabbitmq/03-ack-prefetch-concurrency.md` — four rows, four crash points. Then check your `packaging.requests` queue in the UI and note its "Unacked" column: you are about to make that number mean something.

## System specification

- **Scope in:** one listener on `packaging.requests` with `ackMode = "MANUAL"`; a simulated durable effect (append a line to a local file, or increment a counter in a map — no Postgres needed for this kata); a container factory with prefetch + concurrency; crash experiments.
- **Scope out:** retries, DLQ, inbox, outbox, ordering, Postgres.
- **Functional requirements:** a happy-path message is acked after the effect and leaves the queue; a listener that throws after the effect (before ack) causes a visible redelivery; prefetch bounds the "Unacked" count in the UI.
- **Constraints:** local Docker RabbitMQ; single module; one listener container factory for the work queue.

## Step-by-step code-along

1. **Do:** Write `PackagingWorker` — a `@Service` with a `@RabbitListener(queues = ["packaging.requests"], ackMode = "MANUAL")` method that takes the payload, a `Message`, and a `Channel`, does the simulated effect (log + sleep 500ms to widen your crash window), then `channel.basicAck(message.messageProperties.deliveryTag, false)`.
   **Run:** publish 3 messages (from R02 or a scratch `RabbitTemplate` call); watch logs. **Observe:** three effects, three ack lines, and in the UI the queue drains to 0 ready / 0 unacked. Screenshot the drained queue — this is the happy path.
   **Decision:** what does your effect *mean* before R05 exists? Nudge: for this kata, "effect happened" is just a log + counter — the crash experiments only need the effect to be visible, not durable.

2. **Do:** Create a `SimpleRabbitListenerContainerFactory` bean with `setAcknowledgeMode(AcknowledgeMode.MANUAL)`, `setPrefetchCount(1)`, `setConcurrentConsumers(1)`, and point your listener's `containerFactory` at it.
   **Run:** publish 5 messages while the handler sleeps. **Observe:** the UI's "Unacked" column never exceeds 1 — prefetch is the lid on in-flight work. Now change prefetch to 10 and repeat: Unacked climbs toward 10. Screenshot both states and write the sentence: prefetch is messages per consumer; a crash reclaims at most that many.
   **Decision:** 1 vs 10 for packaging. Nudge: 1 is fairest and slowest; 10 hides per-message latency; both are defensible — the *explanation* is what is graded.

3. **Do:** Pause the app (`kill -STOP <pid>`) while one message is in the handler's sleep, then kill the JVM hard (`kill -9`). Restart the app. Log the `message.messageProperties.redelivered` flag on every delivery.
   **Run:** publish 1 message; mid-sleep, kill -9; restart. **Observe:** on restart the same message is delivered again, `redelivered = true`, and your effect counter incremented twice. The broker requeued the unacknowledged delivery when the channel died. This is the commit-then-ack window made visible: a duplicate, not a loss. Screenshot the queue "Unacked"/redelivery state and the two effect logs.
   **Decision:** did you lose work, or duplicate it? Nudge: you steered the crash to the side that has a defense — duplicates are neutralized by R05; loss is not.

4. **Do:** Now run the same crash test with `AcknowledgeMode.NONE` (auto-ack) instead of MANUAL.
   **Run:** publish, kill -9 mid-sleep, restart. **Observe:** the message is *gone* — no redelivery, one effect, zero logs about it. That is the loss mode this kata exists to make you afraid of. Screenshot the empty queue and write the difference between the two runs in your notes.

5. **Do:** Add a failure path: catch a `RuntimeException` in the listener and `basicNack(deliveryTag, false, true)` (requeue).
   **Run:** publish a message whose payload makes the handler throw. **Observe:** the message redelivers immediately, repeatedly — the hot loop. Check the UI: `redelivered` flips, "Unacked" churns, and healthy messages behind it wait. Log the redelivery count per message: it will be 1, then 2, then 3 — and your log will show you have no way to count beyond the boolean without x-death (R04).

## Try this

The `AUTO` trap, proven: with `AcknowledgeMode.AUTO`, make the listener swallow the exception inside a `try/catch` and log it, returning normally. Publish a failing message. Observe: no redelivery, no nack, the queue drains, and your logs look *excellent* — a clean error line and a successful ack. That is silent loss with great observability. Then rethrow and watch `AUTO` actually nack-requeue. The lesson to write down: "returns normally" is not "succeeded durably" — which is the exact sentence that justifies MANUAL for every work queue.

## Trade-off fork

**Option A — ack after the effect returns (commit-then-ack).** The effect transaction commits; then the ack is sent. Crash between them → redelivery → duplicate effect.
**Option B — ack immediately, effect later (ack-then-commit).** A crash after the ack → the message is gone and the effect never ran.

The curriculum has a hard constraint here — ack-after-commit is the only ordering a pharmacy workflow may use — but write your 3–5 lines anyway: name the benefit you give up by choosing A (you will hold unacked messages longer, and duplicates are now *guaranteed* to happen eventually) and the failure B would produce for a patient (a packaging run that never happens, and nobody knows). Then state why duplicates are the only failure mode this system can actually defend.

## Hints

- **Hint 1:** The ack must use the delivery's own `deliveryTag` on the delivery's own channel, inside the listener method — a stale tag on a closed channel is a `PRECONDITION_FAILED` channel error that requeues *every* unacked message on that channel. Log the tag on every ack/nack to see it.
- **Hint 2:** `kill -9` is the point — graceful `SIGTERM` shutdown closes channels cleanly and can ack or requeue in ways that obscure the window. For a gentler in-JVM experiment, throw from the listener after the effect and before the ack; the container treats an uncaught throw as a crash and closes the channel, which is the same redelivery path.

## Checkpoint / success criteria

You may leave when:

- Happy-path messages are acked after the effect and the queue drains (screenshot).
- A `kill -9` mid-effect produced a visible redelivery with `redelivered = true` and a second effect run (two log lines + UI evidence).
- The auto-ack run of the same test produced *no* redelivery — and you documented the difference.
- You can state the in-flight capacity formula and point at the UI column that proves it.
- You can explain why nack-with-requeue is not a retry strategy (R04 fixes this).

## Bottleneck & reflection questions

1. A worker dies with 40 unacked messages at `4 consumers × 10 prefetch`. What exactly does the broker do, and what is the patient-visible symptom while it happens?
2. Where is the boundary between "duplicate effect" (this layer's failure) and "lost work" (the same layer's failure)? Which one does this kata force you to prefer, and why?
3. Your listener logs an error and returns normally under `AUTO` — trace what the patient experiences, end to end.
4. You see `redelivered = true` on a message. What can you truthfully conclude, and what do you need from R04 to conclude more?
5. Why is acking inside the listener method (not from a callback thread, not in a `finally`) a correctness rule, not a style preference?

## Handoff

- Next: `R04_poison_to_dlq.md` (a budget for that hot loop), `R05_idempotent_consumer.md` (neutralize the duplicate you just created), and `R06_dual_write_failure_demo.md` (needs a working consumer to observe loss).
- Related showcase: `../../pharmacy-fulfillment/exercise_03_production.md` Milestone 3 — manual ack after commit, bounded in-flight work, measured unacked counts.
- Interview line to say aloud: *"My work consumers acknowledge manually, after the durable effect commits, on the delivery's own channel with its own delivery tag; a crash between commit and ack produces a redelivery, which is a duplicate I can neutralize — never a loss I cannot see."*

## Optional stretch

Add `setConcurrentConsumers(4)` and publish 20 messages. Watch the UI's "Unacked" peak at `4 × prefetch` and the effect logs interleave across consumer threads. Then write two sentences on which queue in the R01 topology must *not* get this treatment (hint: `pharmacy.notifications` is order-sensitive — see `R05_idempotent_consumer.md`'s stretch and `../../../posts/series-3-rabbitmq/05-idempotency-ordering.md`).
