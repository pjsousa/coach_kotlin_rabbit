# A05 Ordering Keys — Code-Along Elective

## Objective

You already saw in R05 that the broker's happy-path queue order is not an ordering *guarantee* after redelivery, retry, or replay. This elective makes you engineer order where it matters: **per-prescription ordering** with routing keys and a single effective consumer per key, while **competing consumers** keep throughput for independent work. One primary objective: prove with evidence that events for one prescription are applied in sequence — and that independent prescriptions still parallelize.

## Time box

- Core: 2 hours
- Optional: 0.5h for sequence-gap policy (retry vs quarantine vs operator-recover) as a second experiment

## Prerequisites

- R05 (`../rabbit/R05_idempotent_consumer.md`) — you built the inbox and learned "RabbitMQ's happy-path queue order is not enough; per-prescription sequence is the application rule." Now build the topology that *attempts* order and the sequence check that *enforces* it.
- R03 (`../rabbit/R03_manual_ack_consumer.md`) — consumer concurrency is the throughput side of this trade-off.
- Showcase position: **during Exercise 3** — this directly informs Milestone 5's ordering rules and the projection-consumer topology in `../../pharmacy-fulfillment/exercise_03_production.md`.

## Blog & curriculum links

- Primary: `posts/series-3-rabbitmq/05-idempotency-ordering.md`
- Secondary: `posts/series-3-rabbitmq/02-publisher-confirms-outbox.md` (sequence allocation at event creation)
- Coach-assessment gap: "per-order ordering, parallelism tradeoffs" → measured topology.

## Background & motivation

The naive answer to "does RabbitMQ preserve order?" is "yes" — and it does, on one queue with one consumer, on the happy path. The moment a redelivery happens, a retry bounces, a DLQ replay lands, or a second consumer joins the queue, that informal guarantee evaporates. Pharmacy status is patient-visible: a patient seeing `PACKAGING` arrive after `READY_FOR_COLLECTION` is a real product bug, not a theory. This kata exists to replace the informal claim with two mechanisms: a **keyed topology** that puts per-prescription events on a path with one effective consumer, and a **sequence number check** that makes out-of-order application impossible regardless of broker behavior. It deliberately ignores the throughput ceiling of a single consumer per key — measuring and arguing that ceiling is the point of the trade-off fork.

## Learning objectives

- Design a keyed topology (routing key per prescription, single-active-consumer or dedicated queue) that keeps per-prescription events on one ordered path.
- Allocate a per-prescription sequence at event creation (outbox) and carry it through relay, message, inbox, and projection.
- Implement a sequence-check in the consumer: apply only when `sequence == expected + 1`; expose gaps instead of applying out of order.
- Measure throughput on the ordered path vs a competing-consumer path for independent prescriptions.
- Argue where Ex3 must use single-effective-consumer (facts) vs competing consumers (work) — with evidence.

## Warm-up

Re-read the "Ordering" section of `posts/series-3-rabbitmq/05-idempotency-ordering.md`. Then, in the R05 code, look at the projection consumer's container factory: how many consumers does it run? Now write the scenario that breaks order: *two approvals for the same prescription race through a retry queue with different delays — one bounces once, the other bounces twice.* Which event reaches the consumer second, and does your code notice?

## System specification

**Scope in:** an event queue (`pharmacy.events` topic exchange) where status facts carry `prescriptionId` and `sequence`; one ordered consumer path for status projection (single effective consumer per key); a competing-consumer path for independent work (e.g. packaging) to compare; a sequence-check that refuses out-of-order application; a gap policy (log + quarantine or explicit retry) chosen and documented.

**Scope out:** full ordering across all consumers (impossible and unneeded), exactly-once effect (A04), retry topology (A01), DLQ replay design (A01) beyond the gap it creates.

**Functional requirements:**
- Two events for the same prescription, delivered in either order, are applied in sequence order exactly once each.
- A gap (missing sequence 3 of 1-4) is visible — never silently applied out of order.
- Independent prescriptions process in parallel with measurable throughput higher than the ordered path's single consumer.
- A replay of an old sequence is absorbed (see A04's inbox) or quarantined by the gap policy — not applied backward.

**Constraints:** local Docker Compose, pinned broker, one Spring Boot app, manual ack, Kotlin.

## Step-by-step code-along

1. **Do:** Topology for order. Declare a topic exchange `pharmacy.events`; a durable queue `events.fulfillment-status` bound with `prescription.#` routing keys (or per-key bindings for a small fixed set of test keys); consumer with `concurrency = "1"` (single active consumer) on the projection. Keep a *separate* competing consumer queue for packaging work (`concurrency = "4"`).
   **Run:** `docker compose up -d`, start the app. **Observe:** in the management UI, `events.fulfillment-status` shows 1 consumer; the packaging queue shows 4. Note the consumer counts — they are the topology's contract. **Decision:** a single consumer per queue vs per-key queues (nudge: one queue + one consumer is the smallest ordered path; per-key queues cost a queue per key and only pay off at high per-key volume).

2. **Do:** Add `sequence` to the event envelope at creation. In the outbox insert (Ex3's approval transaction or a test event source), set `sequence = (max(sequence) for this prescription) + 1` — allocate inside the transaction, never at publish time. Carry it through the JSON envelope.
   **Run:** approve a prescription twice; inspect the two outbox rows. **Observe:** sequences are 1 and 2, monotonic per prescription. **Decision:** sequence allocation via `SELECT max + 1` (fine for this kata) vs a `(prescription_id, sequence)` unique constraint to *enforce* monotonicity — the constraint is the stronger version and P03's muscle; pick one and defend.

3. **Do:** The sequence check in the projection consumer. Before applying, read the latest applied sequence for `prescriptionId` (a small `status_projection` table with `(prescription_id, last_sequence)`), and apply only if `incoming.sequence == last + 1`. If it's `<= last`: duplicate — absorb via the inbox (A04) or log. If it's `> last + 1`: **gap** — do not apply, and route to the gap policy (see step 4).
   **Run:** publish 1, 2, 4 (skip 3) for one prescription. **Observe:** the consumer applies 1, 2, then *halts on 4* with a structured log line `GAP detected: prescription=…, expected=3, got=4`; the queue shows 4 unacked. **Decision:** how long may 4 wait before you treat the gap as permanent? (This is the gap-policy question — see fork.)

4. **Do:** The gap policy. Implement one of: (a) requeue with bounded delay — the gap is transient (slow producer/relay) — or (b) park in a `gaps` quarantine table/queue and alert an operator. You already have A01's parking-lot machinery; reuse the shape.
   **Run:** the step-3 gap run again with your policy. **Observe:** with (a) the gap closes when 3 arrives (then 4 applies in order); with (b) the gap is visible and 4 stays parked. Record which happened and how you'd notice it in production. **Decision:** (a) vs (b) is exactly the fork below — write your 3–5 lines *before* reading it.

5. **Do:** The throughput comparison. Publish 200 status events across 10 distinct prescriptions (20 per prescription) in a burst. Measure time-to-apply on the ordered path (1 consumer). Then, on a copy of the same workload through the competing packaging queue (4 consumers, same 200 events, independent work), measure again.
   **Run:** both. **Observe:** the ordered path applies in ~serial time (≈ 200 × processing time); the competing path finishes faster (≈ 200 × processing time / 4). Write the two numbers down. **Decision:** which number would you quote in an interview when someone asks "doesn't ordering kill your throughput?"

6. **Do:** The interleaving proof. Run 3 prescriptions concurrently, 5 events each, and log the applied order per prescription. Assert in a test that per-prescription order is strictly increasing while global order is not.
   **Run:** `./gradlew test` with a Testcontainers broker. **Observe:** per-key monotonic; global interleaved. The assertion *is* the evidence that ordering is per-key, not global — and that is exactly the guarantee Ex3 needs (`../../pharmacy-fulfillment/exercise_03_production.md` Milestone 5).

7. **Do:** Wire into Ex3's projection consumer: single active consumer, sequence check, gap policy, inbox absorption for duplicates.
   **Run:** Ex3's SSE/replay tests (Milestone 8) still pass. **Observe:** replay and reconnect never move a projection backward because the sequence check refuses old sequences.

## Try this

**The redelivery reorder.** Publish 3 events (sequences 1-3) for one prescription. Make the consumer crash (SIGKILL) after applying 1 and 2 but before acking 3. On restart, the broker redelivers 3 *first* (it was unacked), then nothing else — order looks fine. Now the harder version: add a retry hop (A01 parking lot) and make 2 fail transiently while 3 succeeds. Watch what arrives at the consumer: 3 can legitimately arrive before 2's retry. Your sequence check should treat 3 as a gap and wait. The observation to say aloud: *the broker reorders under failure; the sequence check is the only thing that stops a patient from seeing the future.*

## Trade-off fork

Pick one pair, implement it, justify in 3–5 lines:

- **Per-key partitioning vs global order:** per-key (one ordered path per prescription, competing consumers across keys) keeps throughput at the cost of N consumers/queues and a "what if two keys share a hot node" story. Global order (one consumer, one queue) is simple and always ordered but caps everything at one consumer's speed. Ex3's work queues can compete because packaging work is independent; the status projection cannot — name the lost benefits of the option you didn't pick.
- **Gap policy — retry vs quarantine:** bounded retry hides transient producer/relay slowness but adds latency and a policy to maintain; quarantine makes gaps operator-visible immediately but leaves the projection stale until a human acts. No winner; your job is the 3–5 lines that show you understood what each costs the patient.

## Hints

- **Hint 1:** The sequence check must read and update the projection's `last_sequence` in **one transaction** with the apply — otherwise two in-order-but-concurrent events both see `last=1` and both apply. Reuse P03's conditional-update muscle: `UPDATE status_projection SET last_sequence = 2 WHERE prescription_id=… AND last_sequence = 1`, check affected rows = 1.
- **Hint 2:** If a gap run seems to apply events out of order anyway, check your `RabbitListener` concurrency — the projection consumer must be `concurrency = "1"` in the container factory, not just "probably serial." And remember a *second* gap source: the relay (A03) can republish the same event with the same sequence — that's a duplicate, handled by the inbox, not by the sequence check.

## Checkpoint / success criteria

Done when:

- Two events per prescription, delivered either order, apply in sequence exactly once each (asserted).
- A missing-sequence run shows a visible gap (log/queue evidence) and no out-of-order application.
- Throughput numbers for ordered (1 consumer) vs competing (4 consumers) paths are recorded and interpretable.
- Interleaving test proves per-key monotonic, global interleaved.
- Gap policy chosen, documented, and its failure behavior demonstrated.
- Ex3 projection consumer runs single-active with sequence check; existing tests pass.

## Bottleneck & reflection questions

1. Ordering is per prescription, not global. What patient-visible fact would break if you ordered globally — and what cost would global order impose on packaging work?
2. Your sequence check halts on a gap. How long can the projection be stale before the *status GET* (`GET /prescriptions/{id}`) is the better read path — and is that GET your safety net or a design smell?
3. If the relay (A03) publishes out of sequence (it can — it batches by `created_at`), who owns the reordering: the producer, the topology, or the consumer? Answer for Ex3's Milestone 5 language.
4. Replay from the DLQ (A01) injects an old sequence. Your policy: absorb, gap-park, or reject? What does the choice say about how you treat the projection as a source of truth?
5. At what per-prescription event rate does single-active-consumer stop being viable, and what is the sharding story you'd tell before that happens?

## Handoff

- Next: A04 (`A04_inbox_exactly_once_effect.md`) if you skipped it — the inbox and the sequence check are two halves of the same consumer. Or A06 (`A06_saga_lite.md`) if you want order across *steps* of a workflow rather than events.
- Related showcase work: `../../pharmacy-fulfillment/exercise_03_production.md` **Milestone 5** (ordering rules) and **Milestone 8** (SSE replay relies on your sequence).
- Interview line: *"RabbitMQ preserves order only per queue on the happy path — redelivery, retry, and replay all reorder. I keep per-prescription events on a single-effective-consumer path, allocate a sequence per prescription at event creation, and enforce application order with a conditional update in the same transaction as the apply, so a gap or an old event is visible and never applied out of order."*

## Optional stretch

Measure the per-key throughput ceiling: run the ordered path at increasing per-prescription event rates (100, 500, 1000 events/sec across K keys) and record where single-active-consumer becomes the bottleneck for one prescription. Document the threshold and the sharding strategy you'd propose past it — one paragraph, no second project.
