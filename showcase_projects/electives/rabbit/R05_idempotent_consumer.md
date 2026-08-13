# R05 Idempotent Consumer — Code-Along Elective

## Objective

Make duplicate delivery harmless: an inbox table keyed on `(consumer_id, event_id)`, a claim insert inside the same transaction as the business effect, and a duplicate acked as success. Primary objective: prove with row counts that two deliveries produce one effect — at-least-once delivery, at-most-once effect.

## Time box

~2 hours. Core — the layer that makes every duplicate from R03/R04/R07 cost nothing.

## Prerequisites

- `R03_manual_ack_consumer.md` — ack-after-commit is the crash window this kata neutralizes.
- `R04_poison_to_dlq.md` (useful but optional — the retry topology is a generous duplicate generator for your experiments).
- PostgreSQL: the inbox is a real table. Use `../glue/X01_docker_compose_trio.md` (app + Postgres + Rabbit), or `../postgres/P01_schema_and_migrations.md` first if you want the schema discipline.
- `../postgres/P07_testcontainers_postgres.md` is recommended after this kata to turn the race evidence into a test.
- Showcase position: before `../../pharmacy-fulfillment/exercise_03_production.md` Milestone 5 (idempotent consumers and ordering rules).

## Blog & curriculum links

- Primary: `../../../posts/series-3-rabbitmq/05-idempotency-ordering.md` — the inbox table, the claim-transaction rule, the four duplicate sources, ordering.
- Secondary: `../../../posts/series-3-rabbitmq/02-publisher-confirms-outbox.md` — why the relay republishes the same `eventId` (the duplicate you are about to neutralize).
- Coach-assessment gap: "stable event IDs, inbox/idempotency" was instinct; here it becomes a constraint you can race.

## Background & motivation

Every layer you built so far *manufactures duplicates on purpose*: the retry bounces, the requeue-after-channel-death, and — in R07 — the relay republishing after an uncertain confirm. R03 and R04 taught you to steer crashes toward the duplicate side of the trade; this kata is the defense that makes the duplicate side safe. The trick is subtle and the interview question is exact: a check-then-act inbox lookup races — two concurrent deliveries both read "absent." Only a unique constraint acting inside the same transaction as the effect can arbitrate. This kata deliberately ignores ordering (that is A05), Postgres concurrency tuning (A09), and the outbox side (R07) — it is the consumer-side contract, in isolation.

## Learning objectives

- Design an `inbox_messages` table whose primary key is the composite `(consumer_id, event_id)`.
- Claim with `INSERT ... ON CONFLICT DO NOTHING RETURNING` inside the same transaction as the effect.
- Ack duplicates as success — never nack them into the retry topology.
- Race two concurrent deliveries of the same event and watch the constraint decide.
- State the at-least-once / at-most-once pairing precisely, without ever saying exactly-once.

## Warm-up (3 min)

Read "The Inbox Table" and "Deduplicate Inside The Transaction" in `../../../posts/series-3-rabbitmq/05-idempotency-ordering.md`. Then re-read the four-source duplicate table at the top of that post and tick off which sources your R03/R04 experiments already reproduced.

## System specification

- **Scope in:** `inbox_messages` table; a `packaging_run` (or effect) table your worker writes; a `@Transactional` worker handler that claims then effects; a duplicate-delivery experiment; evidence (effect counts, inbox rows, logs).
- **Scope out:** per-prescription ordering/sequence numbers (A05), single active consumer, the relay side, SSE.
- **Functional requirements:** two deliveries of one `eventId` → exactly one effect row; a claim that succeeds but an effect that fails rolls back together (so a redelivery re-runs the effect fresh); a duplicate is acked.
- **Constraints:** local Docker PostgreSQL + RabbitMQ; one module; the claim and the effect share one Spring-managed transaction; inbox rows are never pruned in this kata.

## Step-by-step code-along

1. **Do:** Create the schema (Flyway migration or a plain `schema.sql`): `inbox_messages(consumer_id text, event_id uuid, prescription_id uuid, received_at timestamptz default now(), primary key (consumer_id, event_id))` and an effect table — e.g. `packaging_run(id bigserial primary key, prescription_id uuid not null, event_id uuid not null)`. The composite PK is the whole point: deduplication is per logical consumer.
   **Run:** start the stack; verify both tables exist (`\d inbox_messages`). **Observe:** nothing else yet — the schema is the contract.
   **Decision:** one global inbox table vs one per consumer. Nudge: the composite key keeps a single table while each subscriber's memory stays private.

2. **Do:** Write the claim repository method as a native query: `INSERT INTO inbox_messages (consumer_id, event_id, prescription_id) VALUES (:consumerId, :eventId, :prescriptionId) ON CONFLICT DO NOTHING RETURNING event_id` returning a nullable `UUID?`. Kotlin idiom: a nullable return *is* the two-outcome result — `null` means "already claimed," and your `when` on it is exhaustive.
   **Run:** compile. **Observe:** nothing yet; the DB, not your code, does the arbitration.

3. **Do:** Rewrite the worker handler as one `@Transactional` method: `claim("packaging", event)?.let { effect } ?: AlreadyProcessed` — the claim insert and the effect insert share the transaction. In the listener, ack after the method returns, regardless of which outcome it produced. Log the outcome (`Processed` vs `AlreadyProcessed`) with the `eventId`.
   **Run:** publish one message; check logs. **Observe:** `Processed`, one `packaging_run` row, one inbox row. Publish the *same event* a second time (same `eventId`, same payload): `AlreadyProcessed`, still one effect row, two inbox... no — still one inbox row, and the message was acked. Evidence: `select count(*) from packaging_run` stays 1 while the queue drained.
   **Decision:** ack or nack the duplicate? Nudge: nacking sends an already-done job through the retry topology to burn budget; the requirement is at-most-once *effect*, and "already done" is success.

4. **Do:** Race it for real. Publish the same event twice back-to-back (or have two threads publish simultaneously), with a small `Thread.sleep(100)` inside the effect to widen the window. Run several repetitions and count effects per `eventId`.
   **Run:** publish 10 events, each duplicated, concurrently. **Observe:** `select prescription_id, event_id, count(*) from packaging_run group by 1,2` returns all counts = 1, while `deliveries` (log lines or a counter) shows 20. The constraint decided, not a check. Screenshot the query result — this is the race evidence the coach-assessment demands.

5. **Do:** Prove the rollback half of the contract: make the effect *fail* (e.g. insert a row that violates a NOT NULL constraint) after the claim succeeds. Let the broker redeliver the message (manual ack never sent because the method threw), and watch the second attempt.
   **Run:** publish once; wait; check tables. **Observe:** no inbox row and no effect row survived (rollback took both), and the redelivery ran the effect fresh — the claim succeeded again on attempt two. That pair of observations is the atomicity proof: claim and effect roll back together, or they commit together.

## Try this

The identity trap: take your duplicate experiment and publish the *same logical message with a regenerated `eventId`*. Observe the inbox treats it as a brand-new event and the effect runs twice — deduplication only recognizes identical IDs. This is exactly what a sloppy DLQ replay or a relay that re-rolls IDs does. Write the sentence it proves: *the stable event ID is the contract; regenerate it and every layer above the transaction stops defending you.*

## Trade-off fork

**Option A — dedicated inbox table** (what you built): a small table whose only job is deduplication, composite-keyed per consumer.
**Option B — unique constraint on the effect row itself** (e.g. `UNIQUE (prescription_id, event_id)` on `packaging_run`): no separate table; the effect's natural key is the dedup record.

Pick one and write 3–5 lines: what does B save (a table, a join, one less write in the transaction) and what does it lose (per-consumer dedup when two consumers write different effect rows for the same event — the status projection and the packaging worker cannot share B's constraint)? Which option survives the R07 relay's duplicate publications and a future second subscriber? The curriculum builds the inbox; your job is to name what the alternative gives up.

## Hints

- **Hint 1:** If two concurrent deliveries both produce effects, check that the claim and the effect are truly one `@Transactional` method on a *Spring bean* — self-invocation (calling one of your own methods from inside the same class) bypasses the proxy and runs the claim in a separate transaction. Log a transaction marker or call both repositories from the same method.
- **Hint 2:** `ON CONFLICT DO NOTHING RETURNING` returns zero rows on conflict, not an exception — that is the whole design. If you see constraint-violation errors instead, your INSERT is probably missing the `RETURNING` (or you are using an ORM upsert that throws on conflict).

## Checkpoint / success criteria

You may leave when:

- A duplicated `eventId` produced exactly one effect row (query evidence).
- A concurrent race of 10 duplicated events produced 10 effect rows and 20 deliveries (query evidence + logs).
- A claim-then-failed-effect left *no* inbox row and *no* effect row, and the redelivery succeeded.
- Duplicates were acked as `AlreadyProcessed`, never nacked (log evidence).
- You can say the pairing aloud: delivery at-least-once, effect at-most-once, deduplication owned by a database constraint inside the effect's transaction — never the broker.

## Bottleneck & reflection questions

1. Two copies of one event arrive 6 hours apart (a replayed DLQ message). What does your inbox need to still recognize the second copy — and what deletes that protection?
2. Your effect now has three subscribers. Where does the composite key save you from breaking all three at once?
3. A duplicate is nacked by mistake. Trace what happens to the patient's packaging timeline and the queue's health.
4. Where does this pattern *stop* being enough — what does it not fix about ordering when a delayed event arrives after its successors? (That is `../advanced/A05_ordering_keys.md`.)
5. Is the inbox's existence a claim that RabbitMQ delivered exactly once? Answer in one precise sentence.

## Handoff

- Next: `R07_outbox_relay_mini.md` (the relay whose duplicate publications this kata already neutralizes) and, for depth, `../advanced/A04_inbox_exactly_once_effect.md` (exactly-once effect under stress) and `../advanced/A05_ordering_keys.md` (per-prescription order).
- Related showcase: `../../pharmacy-fulfillment/exercise_03_production.md` Milestone 5 — inbox uniqueness, same-transaction claim, duplicate-ack behavior.
- Interview line to say aloud: *"My consumer claims each event with an INSERT ... ON CONFLICT DO NOTHING RETURNING inside the same transaction as the business effect; the unique constraint arbitrates concurrent duplicates, rollback takes claim and effect together, and a duplicate is acknowledged as success — so delivery is at-least-once, the effect is at-most-once, and the broker never has to be exactly-once."*

## Optional stretch

Add a `consumer_id`-keyed duplicate-count metric (or a log aggregation) and run the R04 retry-topology experiment with the inbox in place: let a retryable failure exhaust its budget, then *replay* the DLQ message — effect count stays 1 across the replay. That one run proves the R04 replay procedure is only safe *because* of this kata, and it is the exact test `../../../posts/series-3-rabbitmq/06-operational-testing.md` names as the centerpiece of the suite.
