# R04 Poison to DLQ — Code-Along Elective

## Objective

Replace the unbounded nack-requeue from R03 with a bounded, observable retry topology: a delayed retry queue, a retry budget read from broker-written `x-death` headers, and a dead-letter queue where poison messages land with their attempt history intact. Primary objective: every failed message has a classified fate with broker evidence.

## Time box

~2 hours. Core — the retry layer every other reliability claim leans on.

## Prerequisites

- `R03_manual_ack_consumer.md` — manual ack and the current nack-with-requeue hot loop are your starting point.
- `R01_topology_scratchpad.md` — topology declarations, extended here.
- `../glue/X01_docker_compose_trio.md` for the broker; Management UI required for the x-death forensics step.
- Showcase position: before/during `../../pharmacy-fulfillment/exercise_03_production.md` Milestone 4 (bounded retries and dead letters).

## Blog & curriculum links

- Primary: `../../../posts/series-3-rabbitmq/04-retries-dead-letters.md` — classification, the delayed retry queue, the x-death budget, the DLQ.
- Secondary: `../../../posts/series-3-rabbitmq/06-operational-testing.md` — "Retry Limits And Dead Letters" and "DLQ Forensics And Replay" as the evidence pattern.
- Coach-assessment gap: retry topology was conceptual; here the TTL timer, the bounce, and the budget become UI-visible.

## Background & motivation

R03 left you with a hot loop: `basicNack(tag, false, true)` answers "should this retry, how long should it wait, when do we stop" with "yes, immediately, never." For a pharmacy, "never" is the whole problem — a poison message blocks the queue, starves healthy prescriptions, and looks like *nothing* in the work-queue depth charts. This kata builds the failure topology that gives every message a budget: a parking lot with a timer (the TTL retry queue with no consumers), a quarantine (the DLQ), and the consumer's decision tree that reads the attempt count from the broker's own `x-death` headers. It deliberately ignores idempotency (R05) — you will *see* the duplicates this layer creates, and that is the point.

## Learning objectives

- Classify failures as retryable vs permanent and route accordingly.
- Declare the retry and DLQ topology: retry exchange, TTL retry queue (no consumers), work-queue dead-letter arguments, DLQ.
- Read the retry budget from `x-death` headers, not from an in-memory counter.
- Make the TTL a configuration value and reason about `MAX_RETRIES = 3` → four total deliveries.
- Inspect a poison message's full attempt history in the Management UI.

## Warm-up (3 min)

Read "The Three Fates of a Failed Message" table and "Bounding the Budget with x-death" in `../../../posts/series-3-rabbitmq/04-retries-dead-letters.md`. Then open the Management UI and look at your current `packaging.requests` queue properties — you are about to give it dead-letter arguments.

## System specification

- **Scope in:** `pharmacy.retry` exchange; `packaging.requests.retry` queue (durable, queue-level TTL, zero consumers, dead-lettering back to `pharmacy.work` with key `packaging.request`); `pharmacy.dlq` exchange + `packaging.dlq` queue; work queue dead-letter arguments pointing at `pharmacy.retry`; worker decision tree.
- **Scope out:** inbox/idempotency (R05), graduated backoff (one fixed TTL is correct here), the delayed-message plugin, replay automation (stretch), Postgres.
- **Functional requirements:** a retryable failure bounces with delay, bounded by budget, then lands in the DLQ with `x-death` evidence; a permanent failure goes straight to the DLQ with zero retries; healthy messages keep flowing.
- **Constraints:** local Docker RabbitMQ; single module; TTL configurable via `application.yml`; classic queue for the retry queue (quorum-TTL nuance is a stretch).

## Step-by-step code-along

1. **Do:** Add the failure vocabulary to your worker: a `sealed interface` outcome (e.g. `Processed` / `Retryable / Permanent / Exhausted`) or an enum + a `classify(ex: Exception): FailureClass` function. Make the `else` branch default to retryable — a logic bug getting three slow retries is acceptable; a transient blip skipping the safety net is not.
   **Run:** compile. **Observe:** the decision tree needs a shape before it needs a topology.
   **Decision:** which pharmacy failures are permanent? Nudge: a payload that fails to parse and a prescription that no longer exists can never succeed — the retry queue cannot fix either.

2. **Do:** Extend `RabbitTopology` (or add `RabbitRetryTopology`): `pharmacy.retry` as a `DirectExchange`, `packaging.requests.retry` with `QueueBuilder.durable(...).ttl(30_000).deadLetterExchange("pharmacy.work").deadLetterRoutingKey("packaging.request").build()`, and — crucially — change the *work queue declaration* to add `deadLetterExchange("pharmacy.retry")` + `deadLetterRoutingKey("packaging.requests.retry")` and bind `pharmacy.retry` to the retry queue.
   **Run:** restart the app; inspect the UI's Queues → `packaging.requests` → "Dead letter exchange/routing key" columns and the retry queue's "TTL" column. Screenshot — the topology is now visible in the broker.
   **Decision:** why must the retry queue have no consumers? Nudge: a consumer on it would process before the timer fires, converting the parking lot into an eager second work queue.

3. **Do:** Replace the nack-requeue in the listener with the decision tree: on success ack; on retryable and budget remaining → `basicNack(tag, false, false)` (into the retry topology); on permanent or exhausted → publish to the DLQ exchange yourself, then ack the original. Add `retryAttempts(message: Message): Int` that reads `x-death` from `message.messageProperties.headers` and finds the entry whose `queue` is `packaging.requests.retry`, returning its `count`.
   **Run:** publish a message whose handler always throws a retryable failure. **Observe:** the message leaves the work queue (Unacked→0), appears in `packaging.requests.retry`, waits the TTL, then returns to the work queue and fails again. Watch the UI through two full bounces — TTL expiry is your timer. Screenshot the retry queue at depth 1 and the redelivered log with `redelivered = true`.
   **Decision:** `MAX_RETRIES = 3` — how many deliveries total? Nudge: the original plus three bounces = four. The interview arithmetic.

4. **Do:** Wire the TTL as `@Value("\${packaging.retry.ttl-ms:30000}")` so tests and experiments can shrink it. Then let the retryable failure exhaust its budget.
   **Run:** wait out the budget (or set TTL to 1000ms). **Observe:** the message lands in `packaging.dlq`. Click the message in the UI and expand the `x-death` header: `count = 3`, `reason = rejected`, `queue = packaging.requests.retry`. Screenshot — this is the broker's own accounting of the budget, and `reason = rejected` proves the bounce came from your nack, not from TTL expiry.

5. **Do:** Make the permanent path visible: publish a payload that fails to parse (or a request for a nonexistent prescription), so `classify` returns permanent.
   **Run:** publish; wait. **Observe:** the message reaches the DLQ *without ever entering the retry queue* — no retry-queue entry in `x-death`. That single difference is the evidence that classification is a real policy. Then confirm healthy messages continue to process while the poison sits in quarantine (work queue drains; DLQ holds 1) — the exact observability claim: alert on the DLQ, not the work queue.

## Try this

Kill the worker mid-retry-cycle: with TTL at 30s, publish a retryable failure, let it bounce once, then `kill -9` the app while the retry queue holds the message. Restart. Observe: the message survives in the retry queue (durable queue + broker-side timer — your "budget" lived in the broker, not in the dead process), bounces, and finishes its budget exactly as before. Compare this with the R03 attempt-counting-by-hand approach: your old in-memory counter would have been lost with the process. Screenshot the x-death count before and after the restart — identical.

## Trade-off fork

**Option A — TTL retry queue (what you built).** One parking lot, fixed timer, dead-letter bounce. No broker plugin.
**Option B — the `rabbitmq-delayed-message-exchange` plugin.** A single exchange delays messages by per-message header, no per-step queues.

Pick one and write 3–5 lines: what does B buy you (graduated per-message backoff, one hop) and what does it cost (a broker dependency you must justify on Docker, version pinning, and — for the interview — the plugin's behavior on quorum queues)? Why is A the defensible default for a 2–5 hour challenge, and what honest limitation of A (fixed delay, head-of-line on classic queues) are you accepting? The curriculum does not declare a winner here; it declares that you can name what you lost.

## Hints

- **Hint 1:** `x-death` is a list of maps; take the entry whose `queue` key equals `packaging.requests.retry` — a message dead-lettered from multiple places will have one entry per source queue, and picking the wrong one quietly zeroes your budget.
- **Hint 2:** If the message never leaves the work queue after a nack, your work-queue declaration predates the dead-letter arguments — RabbitMQ does not update queue arguments on redeclaration. Delete the queue (or the whole virtual host) once and let the app redeclare it, and re-verify the UI's dead-letter columns before debugging anything else.

## Checkpoint / success criteria

You may leave when:

- A retryable failure bounces through `packaging.requests.retry`, waits its TTL, and exhausts the budget into `packaging.dlq` (UI screenshots at each stage).
- The DLQ message's `x-death` shows `count = 3`, `reason = rejected`, and the payload intact.
- A permanent failure reached the DLQ with zero retry-queue entries.
- Healthy messages processed while the poison sat in quarantine, with the work queue draining.
- You can quote the total-delivery arithmetic and explain where the budget lives (in the broker's headers, not your process).

## Bottleneck & reflection questions

1. Your DLQ depth is 40 and the work queue is empty — what is the patient symptom, what alerted you, and what is your recovery procedure?
2. Where is the remaining duplicate window in the terminal hop (DLQ publish, then ack), and what does a crash there produce — loss or duplicate?
3. Why is a fixed TTL retry queue unable to do 1s→5s→30s backoff, and what would each solution cost you?
4. A code change makes the handler throw on *every* delivery of a previously healthy message type. What happens across the full budget, and what does the DLQ now contain that tells you the difference from a poison payload?
5. Which layer makes the duplicates this topology honestly creates harmless — and why is this kata deliberately incomplete without it?

## Handoff

- Next: `R05_idempotent_consumer.md` (neutralize the duplicates this layer produces), then the deeper `../advanced/A01_poison_and_parking_lot.md` (retryable vs permanent at scale, replay) when you want the production drill.
- Related showcase: `../../pharmacy-fulfillment/exercise_03_production.md` Milestone 4 — bounded retry, DLQ, and the inspect/replay procedure.
- Interview line to say aloud: *"Retries multiply deliveries, they never reduce them: my worker classifies each failure, bounces retryable work through a consumerless TTL parking lot, reads the budget from the broker's x-death headers so it survives restarts, and quarantines permanent or exhausted messages in a dead-letter queue with their attempt history intact — and I alert on DLQ depth, not work-queue depth."*

## Optional stretch

Write a tiny replay utility (a probe consumer or a one-off runner) that reads a message from `packaging.dlq`, republishes the *original payload* to `pharmacy.work` with key `packaging.request` — preserving the event identity — and observe it re-enter the retry topology. Then deliberately republish it with a *regenerated* event ID and note what changes in the UI. The second part previews exactly why `R05_idempotent_consumer.md` demands that the ID be preserved: without it, deduplication stops recognizing the copy.
