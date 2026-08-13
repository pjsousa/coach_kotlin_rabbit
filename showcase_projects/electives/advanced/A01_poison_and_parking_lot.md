# A01 Poison and Parking Lot — Code-Along Elective

## Objective

You already saw poison messages and a dead-letter queue in R04 — the quarantine, not the strategy. This elective builds the part R04 deliberately skipped: a **retryable-vs-permanent failure classifier**, a **delayed retry parking lot** (TTL queue with no consumer) that bounces a failed message back to work with a budget, and an **operator replay path** from the DLQ. One primary objective: make every failed message land in exactly one of three visible fates — retry-with-delay, dead letter, or processed — and prove the budget survives a broker restart.

## Time box

- Core: 2–2.5 hours
- Optional: 0.5h for graduated retry tiers (A/B retry queue) or replay-tool CLI

## Prerequisites

- R04 (`../rabbit/R04_poison_to_dlq.md`) — you already built the basic DLQ topology; do not rebuild it. You saw `x-death` accumulate; now prove a budget reads it.
- R03 (`../rabbit/R03_manual_ack_consumer.md`) — manual ack/nack under your belt.
- X01 (`../glue/X01_docker_compose_trio.md`) — Compose trio running.
- Showcase position: **during Exercise 3** — this is a dress rehearsal for Milestone 4 (bounded retries and dead-letter handling) in `../../pharmacy-fulfillment/exercise_03_production.md`.

## Blog & curriculum links

- Primary: `posts/series-3-rabbitmq/04-retries-dead-letters.md`
- Secondary: `posts/series-3-rabbitmq/06-operational-testing.md` (the "unit-test the policy, integration-test the evidence" split)
- Coach-assessment gap: RabbitMQ "basic/tutorial familiarity" → operational retry topology with evidence.

## Background & motivation

R04 ended with a working DLQ and a warning you've heard in every post of this series: **rejection is not retry**. A message that dead-letters on attempt one is not failed *better* than one that requeues forever — it is failed *faster*. Real workflows need a third bucket between "requeue immediately" and "quarantine now": transient failures deserve delay. The parking lot is the topology that implements delay without a sleeping consumer (a sleeping consumer holds prefetch capacity hostage). This kata exists to make the retry **policy** — who decides retryable vs permanent, how many attempts, how the budget is counted — an explicit, testable part of the application rather than a broker accident. It deliberately ignores consumer-side idempotency (that's R05/A04's job) so the retry loop itself is the only thing on the table.

## Learning objectives

- Classify exceptions as retryable vs permanent with an explicit, auditable `classify` function (and a Kotlin `sealed` outcome for the ack decision).
- Build a TTL parking-lot retry queue wired through dead-letter exchanges, with no consumer attached.
- Count attempts from broker evidence (`x-death`) and enforce a budget that routes exhaustion to the DLQ.
- Inspect and replay a dead-lettered message while preserving its stable event ID (idempotency contract from `posts/series-3-rabbitmq/05-idempotency-ordering.md`).
- Prove the budget survives a broker restart and that healthy work is never starved by a poison hot loop.

## Warm-up

Re-read the "Three Fates of a Failed Message" table in `posts/series-3-rabbitmq/04-retries-dead-letters.md`. Then open the management UI (`http://localhost:15672`), find the queue your R04 DLQ test produced, and look at the `x-death` headers on a dead letter. Answer: *who wrote those headers — the broker or your app?* The answer is the whole topology.

## System specification

**Scope in:** one durable work queue (`fulfillment.work`), one TTL retry queue (`fulfillment.retry`, no consumers, TTL ~5s for lab speed), one dead-letter queue (`fulfillment.dlq`), a retryable vs permanent classifier, a replay endpoint or small CLI, and an attempt-budget that stops at 3 attempts.

**Scope out:** per-message exponential backoff tiers (stretch), persistent retry *state* in Postgres (the broker's `x-death` is the state for this kata), consumer-side inbox dedupe (A04), multiple work classes.

**Functional requirements:**
- A transient failure (e.g. simulated DB lock) is retried with delay, at most 3 attempts, then dead-lettered.
- A permanent failure (malformed JSON payload) dead-letters on the first attempt — no retry.
- A healthy message is processed exactly once per attempt and acked.
- Replay from the DLQ republishes the message with its original event ID intact.

**Constraints:** local Docker Compose (X01), pinned broker image, one Spring Boot app, manual ack mode, Kotlin only.

## Step-by-step code-along

1. **Do:** Declare the topology — work queue with `x-dead-letter-exchange` pointing at a retry exchange; retry queue with `x-dead-letter-exchange` back to the work exchange, `x-message-ttl` = 5000, and `autoDelete=false`; DLQ bound to a quarantine exchange. Use `QueueBuilder` (`posts/series-3-rabbitmq/01-amqp-topology.md` shows the declaration pattern). Acknowledge mode MANUAL on the listener container factory.
   **Run:** `docker compose up -d` then start the app. **Observe:** in the management UI you see three queues, and the retry queue lists *zero consumers* — that is the parking lot's whole point. **Decision:** routing-key scheme for the retry bounce — pick a pattern like `work.retry` → `work` and write it down; you'll reuse it in Exercise 3.

2. **Do:** Implement `fun classify(ex: Exception): FailureClass` returning a sealed `FailureClass { RETRYABLE, PERMANENT }` (mirror `posts/series-3-rabbitmq/04-retries-dead-letters.md`). Give the listener a try/catch whose catch block reads the *exception type*, not the attempt count.
   **Run:** a plain JUnit test feeding each exception class; assert the verdict. **Observe:** no broker involved — this is the "unit-test the policy" layer from `06-operational-testing.md`. **Decision:** default the `else` branch to RETRYABLE (nudge: a transient network blip classified permanent loses work silently; a bug classified retryable only spends budget).

3. **Do:** In the consumer, on failure, nack with `requeue=false` for **both** classes. Retryable messages bounce into the parking lot; permanent ones go straight to the DLQ because *your* classifier said so — the broker routes identically either way.
   **Run:** publish a valid payload to a listener that throws a retryable exception once, then succeeds. **Observe:** management UI — message appears in retry queue, disappears after ~5s TTL, reappears in work queue with redelivery, and is processed. Capture the timing evidence (`ready` counts) as proof.

4. **Do:** Add the budget. Read the `x-death` header (a `List<Long>` of death timestamps, one entry per dead-letter event). If `x-death` count >= 2 (i.e. attempt 3 arrives), classify as exhausted and nack to the DLQ instead of the retry path.
   **Run:** make every attempt fail; watch attempt 3 land in the DLQ. **Observe:** `x-death` on the dead letter shows 3 entries; the retry queue never recycles it. **Decision:** fixed budget vs graduated delay — start fixed; the fork below makes you argue it.

5. **Do:** Implement a replay action — either a `POST /dlq/replay` endpoint that publishes the stored payload back to the work exchange, or a tiny Kotlin main/CLI using a raw `ConnectionFactory`. **Critical rule from R05:** republish with the **original event ID**; never regenerate.
   **Run:** replay a dead letter; confirm it is delivered and either processed or dead-letters again per the classifier. **Observe:** the message keeps its identity end-to-end — this is what makes at-least-once *safe* instead of *messy*.

6. **Do:** Wire it into Ex3's packaging worker shape (`../../pharmacy-fulfillment/exercise_03_production.md` Milestone 4): the packaging worker gets the classifier + budget, and the DLQ payloads carry `prescriptionId` for the operator.
   **Run:** Ex3's existing workflow tests still pass. **Observe:** no behavior regression; the retry path is additive.

## Try this

**Starve the queue.** Publish 100 messages where every 10th payload is malformed (permanent) and the rest succeed but take 100ms. Watch the management UI: the healthy 90 flow through; the 10 poison messages do *not* block them — they vanish into the DLQ on attempt one. Now flip the experiment: make *all* 100 messages throw a retryable exception. Watch the retry queue pin the work queue's `ready` count and the DLQ grow at exactly attempt 3 per message. The lesson worth being able to say in an interview: *a retry topology protects healthy work from poison, but a misclassified failure class turns every message into poison.*

## Trade-off fork

Pick one of three, implement it, and write 3–5 lines justifying the choice (this is interview muscle, not homework):

- **Option A — Immediate requeue** (`basicNack(tag, false, true)`): zero topology, zero delay, zero budget. Fast to build, hot-loop guaranteed, unbounded broker churn.
- **Option B — TTL retry parking lot** (the one you just built): delay outside the consumer, budget via `x-death`, one retry queue per work class. Adds topology and an operator-facing DLQ.
- **Option C — Per-attempt delay growth (multiple TTL queues or graduated tiers)**: nicer to downstream dependencies, but N queues per work class and a more complex attempt bookkeeping.

Do not look for a single official winner — name the lost benefits of the options you rejected (e.g. "A loses budget and starves the queue; C loses simplicity and costs a queue per tier, which is why I picked B for a single worker class").

## Hints

- **Hint 1:** The budget counts *deaths*, not deliveries. A message redelivered by a consumer crash does not necessarily carry new `x-death` entries — decide whether a crash redelivery should cost budget, and document it.
- **Hint 2:** `x-death` is a list, not a scalar: `headers["x-death"] as? List<Map<String, Any>>`. There is also `x-first-death-queue`. The broker writes these; your tests should assert on them via the management API or a raw channel consumer, not by mocking.

## Checkpoint / success criteria

Done when:

- A transient failure retries with delay and eventually succeeds (timed UI evidence).
- A permanent failure dead-letters on attempt one (UI evidence, no retry hop).
- Exhausted budget lands in the DLQ with 3 `x-death` entries and intact payload/event ID.
- Healthy messages keep flowing while poison messages are parked (UI `ready` counts over time).
- Replay republishes with the original event ID and the classifier still applies.
- Broker restart mid-retry does not reset the budget (message returns to work, not to the head of the budget).

## Bottleneck & reflection questions

1. Your classifier defaults unknown exceptions to RETRYABLE. What does that mean for a *logic bug* in the handler — how long until it poisons, and is that the right cost?
2. A sleeping consumer was banned for holding prefetch capacity. Where else can a *broker-side* delay hide capacity problems in Ex3?
3. The replay action assumes the consumer is idempotent (R05). What happens if you replay an event that already committed its effect — and whose job is it to notice?
4. If the DLQ grows unboundedly, which metric warns you — queue depth, or something else? (Hint: `06-operational-testing.md` makes this a test-design question.)
5. Where does the retry policy belong in a 2-hour vs 5-hour submission — and what does your answer say about how you scope reliability work?

## Handoff

- Next: A02 (`A02_backpressure_and_prefetch.md`) — retries give you failure *fates*; prefetch gives you failure *capacity*. Or A04 (`A04_inbox_exactly_once_effect.md`) — the consumer half of "duplicates are safe."
- Related showcase work: `../../pharmacy-fulfillment/exercise_03_production.md` **Milestone 4** — this kata is its dress rehearsal.
- Interview line: *"Retries need a budget and a delay; I classify failures as retryable or permanent in the application, park retryable messages in a TTL queue so the consumer never sleeps, and use the broker's x-death evidence to stop after three attempts and quarantine — so a poison message can never starve healthy work."*

## Optional stretch

Build a **graduated retry path**: two retry queues (short TTL 5s, long TTL 30s). First failure → short queue, second → long queue, third → DLQ. The classifier stays the same; only the routing decision on attempt number changes. Document the queue-count cost per work class and whether you'd accept it in Ex3.
