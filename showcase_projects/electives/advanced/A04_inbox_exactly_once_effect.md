# A04 Inbox Exactly-Once Effect — Code-Along Elective

## Objective

You already saw the inbox table and unique-constraint dedupe in R05. This elective proves the claim R05 argued: under **at-least-once delivery**, the inbox can produce an **exactly-once effect** — measured, not asserted. You will deliver the same event twice, three times, and concurrently, and show one durable business effect, one inbox row, and a clean duplicate ack every time. One primary objective: demonstrate that the inbox claim and the business effect are one atomic decision, with evidence, and state the guarantee language precisely — never "exactly-once delivery."

## Time box

- Core: 2 hours
- Optional: 0.5h for the concurrent-duplicate race test (two consumers, same event, latched)

## Prerequisites

- R05 (`../rabbit/R05_idempotent_consumer.md`) — you built the inbox and saw the check-then-act race. Now prove the claim.
- R07 (`../rabbit/R07_outbox_relay_mini.md`) — you know where duplicates come from (relay republish, commit-then-ack redelivery, retry bounces, DLQ replay).
- P03 (`../postgres/P03_approve_once_race.md`) — conditional writes; the constraint is the arbiter, the same muscle.
- Showcase position: **during Exercise 3** — dress rehearsal for Milestone 5 (`../../pharmacy-fulfillment/exercise_03_production.md`).

## Blog & curriculum links

- Primary: `posts/series-3-rabbitmq/05-idempotency-ordering.md`
- Secondary: `posts/series-3-rabbitmq/02-publisher-confirms-outbox.md` (where duplicates originate) and `posts/series-3-rabbitmq/06-operational-testing.md` (how to prove it)
- Coach-assessment gap: "at-least-once delivery, exactly-once effect" reasoning → reproducible evidence.

## Background & motivation

Every reliability layer in this program is *built* to create duplicates: the relay republishes after a lost confirm, the worker redelivers after a commit-then-ack crash, retries bounce, replays publish. R05 gave you the inbox pattern and warned about the check-then-act race. This kata makes the guarantee *measurable*: duplicate delivery must produce one effect — proven by row counts and constraint-violation statistics, not by reading code. It deliberately ignores ordering (A05 owns sequence/gap policy) so the dedupe transaction is the only thing on the table. And it exists to arm you with the sentence interviewers wait for: the inbox gives you at-most-once *effect* per event on top of at-least-once *delivery* — the combination is the closest legally-claimable "exactly-once" in a system with two durable stores that do not share a transaction.

## Learning objectives

- Rebuild the R05 inbox as a claim-and-effect single transaction with the unique constraint as arbiter (no `exists` check-then-act).
- Deliver the same event ID N times and show exactly one durable effect and N−1 duplicate acks.
- Simulate the commit-then-ack crash window and show the redelivered duplicate is absorbed.
- Race two consumers on the same event and show the constraint, not luck, decides the winner.
- Write the precise guarantee sentence with "delivery" and "effect" in the right places.

## Warm-up

Re-read the "A Duplicate Is Not An Anomaly" section of `posts/series-3-rabbitmq/05-idempotency-ordering.md` and inventory the four duplicate sources. Then, in the R05 code you wrote, find your inbox insert and ask: *what decides the winner if two copies of the same event arrive on two threads simultaneously — my code, or the database constraint?* If the answer is "my code," this elective is mandatory.

## System specification

**Scope in:** one logical consumer (e.g. the status projection or a fulfillment-effect worker), an `inbox_messages` table with `PRIMARY KEY (consumer_id, event_id)`, a `@Transactional` claim+effect method, a duplicate ack path, a small event producer that can deliver a chosen event ID multiple times (including from a replay tool), and the evidence test suite.

**Scope out:** ordering/sequence policy (A05), retry topology (A01), outbox relay (A03), exactly-once *delivery* (impossible here — say it out loud), multi-consumer inbox fan-out complexities beyond one composite key.

**Functional requirements:**
- Deliver event `E` (same ID, same payload) 5 times in sequence: one business effect, 5 ackable outcomes.
- Deliver `E` twice concurrently: one effect, one winner, one loser handled as duplicate.
- A failed effect (induced DB error) rolls back its inbox claim — the retry succeeds on the next delivery.
- Retention: inbox rows outlive the replay/retry window (no cleanup in this kata; document it).

**Constraints:** local Docker Compose, pinned Postgres + Rabbit, one Spring Boot app, manual ack, Kotlin.

## Step-by-step code-along

1. **Do:** Recreate the R05 inbox table and the `handleEvent` shape: `@Transactional fun handle(event: PrescriptionEvent): EventResult` where the claim is an `INSERT ... ON CONFLICT DO NOTHING` (or an insert whose `DuplicateKeyException` you catch) and the effect (e.g. `status_history` append or inventory effect) happens after the successful claim — same transaction.
   **Run:** the R05 tests still pass. **Observe:** no `exists()` call anywhere in the path. **Decision:** `ON CONFLICT DO NOTHING` vs try/catch on `UniqueViolationException` — pick one; the constraint does the arbitration either way, but your Kotlin shape (sealed `EventResult { Applied, Duplicate, Failed }`) differs.

2. **Do:** Build a test that publishes the same event ID 5 times from the producer (a raw `RabbitTemplate` publish loop is fine).
   **Run:** consume all 5. **Observe:** `SELECT count(*) FROM status_history WHERE event_id = 'E'` = 1, `SELECT count(*) FROM inbox_messages` = 1, and the broker shows 5 deliveries with 5 acks. The ack of duplicates is not optional — duplicates are *successful* processing of a known-already-applied event. **Decision:** whether duplicate acks carry a distinguishable outcome in logs (nudge: yes — `EventResult.Duplicate` in structured logs is how you debug "why did my count look weird").

3. **Do:** The crash-window test. Publish `E`, and in the consumer, after the effect commits but before the ack, throw a `RuntimeException` that simulates a crash (or kill the app). Let the broker redeliver.
   **Run:** drain the redelivery. **Observe:** `redelivered` flag = true on the second delivery; the effect count stays 1; the second delivery is acked as a Duplicate. This is *the* commit-then-ack window of `posts/series-3-rabbitmq/03-ack-prefetch-concurrency.md` — and the inbox is its antidote.

4. **Do:** The concurrent race. Two consumers, same queue, prefetch 2. Publish `E` twice back-to-back. Add a latch in the handler so both threads reach the claim before either commits (a `CountDownLatch` in test-only code).
   **Run:** the race. **Observe:** exactly one `Applied` outcome; the loser gets `DuplicateKeyException` (or `ON CONFLICT` returns 0 rows), rolls back its effect transaction, and acks. The constraint — not timing — decided it. **Decision:** the latch lives in test code only; note in a comment why the test is meaningless without it.

5. **Do:** The rollback test. Inject a failure into the effect after the claim (e.g. a `CHECK` violation or a forced `RuntimeException` in a spy). Deliver `E` again.
   **Run:** the retry. **Observe:** the first attempt rolled back claim + effect together (inbox count still 0); the retry claims fresh and applies once. Claim and effect share a fate — that is the single transaction. Capture the two row counts as evidence.

6. **Do:** Wire into Ex3 (`../../pharmacy-fulfillment/exercise_03_production.md` Milestone 5): the packaging/fulfillment effect and the status projection each get `handleEvent` with their own `consumer_id` value.
   **Run:** Ex3's duplicate-injection test — deliver an old event with a new-but-duplicate ID. **Observe:** no double inventory decrement, no second state transition, and the duplicate ack. The inventory invariant (P04's no-negative rule) is the ultimate evidence: it would *fail* if the inbox failed.

7. **Do:** Write the guarantee paragraph in a `docs/guarantees.md` (or the elective notes) with exact wording: at-least-once delivery from the broker; at-most-once effect per (consumer, event) via the inbox; the combination is exactly-once *effect* — not exactly-once delivery, and not a distributed transaction.
   **Run:** read it out loud. **Observe:** every sentence survives the interviewer's "what if the relay republishes *and* the worker redelivers *and* someone replays from the DLQ" question — because each duplicate carries the same `event_id` and hits the same constraint.

## Try this

**Triple duplicate storm.** Publish the same event ID 10 times with a different payload *on the last copy* (payload says "approved", last copy says "rejected"). Watch: the effect fires once on the first copy; all 9 later copies — including the payload-mutated one — are acked as Duplicate. The inbox dedupes on **identity, not content**. Now answer: is that correct or dangerous for a state-machine consumer? (Nudge: for facts, first-write-wins is usually right; for commands, a mutated payload is a production bug worth logging loudly.)

## Trade-off fork

Pick one pair, implement it, justify in 3–5 lines:

- **Dedupe in a DB inbox (what you built) vs idempotent writes only:** the inbox is explicit, inspectable, and survives app restarts, but costs a table and a row per event. Pure idempotent writes (e.g. `ON CONFLICT` on the *effect* table itself) need no inbox but force every effect to be expressible as a conflict — and you must argue why an old duplicate's conflict is indistinguishable from a genuine re-write.
- **One composite-key inbox vs per-consumer tables:** one table with `(consumer_id, event_id)` is easy to query globally but couples consumers in migrations; separate tables isolate consumers but multiply DDL. Name what you lose with the choice you didn't make.

## Hints

- **Hint 1:** The unique constraint is the arbiter only if the effect is in the same transaction. If you see "two effects happened," you likely claimed outside the transaction or used an `exists` pre-check. R05's warning is the exact bug — look for the `if (exists)` shape.
- **Hint 2:** `ON CONFLICT DO NOTHING` returns 0 rows on a conflict — that's your `Duplicate` signal without exceptions. If you catch `DuplicateKeyException` instead, make sure the *transaction* is still usable after the catch (a failed statement can poison the transaction in some drivers; `ON CONFLICT` avoids that class of bug entirely).

## Checkpoint / success criteria

Done when:

- 5 sequential copies → 1 effect, 1 inbox row, 5 acks (row-count evidence).
- Commit-then-ack crash → redelivery absorbed, effect count unchanged (redelivered-flag evidence).
- Latched concurrent race → exactly one `Applied`, loser acks as Duplicate (outcome log evidence).
- Failed effect rolls back claim + effect; retry applies once (count evidence).
- `docs/guarantees.md` contains the exact at-least-once / at-most-once-effect / exactly-once-effect sentence.

## Bottleneck & reflection questions

1. The inbox is unbounded in this kata. What happens to dedupe when you start pruning rows — and what retention length is actually safe given A03's relay and A01's replay can republish events months later?
2. Your winner was decided by a DB constraint. What does that imply about which *consumer* threads are allowed to call `handleEvent` — can two app instances safely share one inbox table?
3. A duplicate with a *different* event ID defeats the inbox. Where in Ex3 could an event ID be regenerated, and what contract (R07/A03) prevents it?
4. Exactly-once effect is per (consumer, event). If two different consumers both apply the same event to *their own* stores, is that a violation? (This is the multi-consumer fan-out question from `posts/series-3-rabbitmq/01-amqp-topology.md`.)
5. In a 2-hour submission, you can ship the outbox but skip the inbox. Which failure does that leave open — and what do you tell the interviewer you deliberately deferred?

## Handoff

- Next: A05 (`A05_ordering_keys.md`) — the inbox says "which copies to skip"; ordering keys say "which order to apply." Or A01 (`A01_poison_and_parking_lot.md`) if retries are your gap.
- Related showcase work: `../../pharmacy-fulfillment/exercise_03_production.md` **Milestone 5** — your duplicate/race/rollback evidence is its verification section.
- Interview line: *"Delivery is at-least-once by construction, so duplicates are guaranteed; the inbox makes the effect exactly-once by claiming the event and applying it in one transaction with a unique constraint as the arbiter — duplicates are acknowledged as already-applied, not retried, and what I can never claim is exactly-once delivery, because no amount of topology removes the at-least-once reality of two stores that don't share a transaction."*

## Optional stretch

Add inbox retention with a documented window: a scheduled cleanup that deletes rows older than the max replay horizon (A03 relay republish + A01 replay + operator window). Write the test that replays an event from *after* the retention cutoff and show the effect fires again — then argue whether that is a bug or a documented property.
