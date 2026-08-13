# A10 Read Models / Projections — Code-Along Elective

## Objective

Build a small status projection on top of the append-only history you already have, apply events to it in per-prescription order, prove you can delete the projection and rebuild it from the log with identical row counts, and then deliberately break the apply order to see the projection's gap behavior. You leave with a rebuild script, a lag measurement, and the exact vocabulary to defend "the projection is a derived read model, never a second source of truth."

## Time box

~2.5–3h. Core: steps 1–5. Optional: the async projection variant in step 6 and the concurrent-rebuild race in "Try this".

## Prerequisites

- `../postgres/P05_status_history_append.md` — you built the append-only status history there; this kata consumes it as the log.
- `../glue/X03_sse_toy.md` — the SSE consumption side you proved there reads the projection this kata defines.
- `../postgres/P01_schema_and_migrations.md` for the migrations discipline; `../postgres/P06_index_and_explain.md` for the replay-query plan habit.
- Position: **during Exercise 3** (before `showcase_projects/pharmacy-fulfillment/exercise_03_production.md` Milestone 5/7 — the projection is the load-bearing piece for both the ordered consumer and the SSE milestone).

## Blog & curriculum links

- Primary: `posts/series-4-product-sse/02-sse-correctness.md` — the `status_projection` table, per-prescription sequence numbers, "latest wins is forbidden," and the two-reader invariant (SSE and GET read the same store).
- Secondary: `posts/series-2-postgres/01-schema-design.md` (where the history/current-state split came from) and `posts/series-4-product-sse/03-testing-realtime.md` (projection-level tests as the fast, deterministic layer).
- Coach-assessment gap: SSE/realtime design — "reconnects, `Last-Event-ID`, event ordering, replay," which all reduce to "the projection is durable and rebuildable."

## Background & motivation

In P05 you proved an append-only history table records every transition in sequence. In X03 you proved an SSE toy can push rows to one patient. What neither of them proved is that the *derived* view — the projection the SSE endpoint actually reads — can be trusted after failure. This kata exists because the projection is where realtime systems quietly become a second source of truth:

- The current-state table (`prescriptions.status`) answers "what is the status now?" It cannot answer "which events happened, in what order?" — that is the log.
- The projection answers "what would this patient's timeline look like right now?" It is derived, which means it can be *rebuilt* — and rebuildability is the property that makes `Last-Event-ID` replay honest (the log survives, the projection does not need to).
- If you treat the projection as the source of truth, a corrupted or lagged projection corrupts the patient's view and you have no way back. If you treat the log as truth and the projection as a cache you can drop, a corrupted projection is a ten-minute rebuild, not an incident.

What this kata deliberately ignores: the RabbitMQ consumer that feeds the projection (you proved inbox + ordering in `../rabbit/R05_idempotent_consumer.md` and `../rabbit/R07_outbox_relay_mini.md`; A11 re-attaches the feed), and the SSE layer itself (X03's toy plus `A11_sse_hard_edges.md` harden it). Here, the log is just a table and the projection is just a table — the point is the *relationship* between them.

## Learning objectives

- Define a projection schema whose rows are fully derived from the log (same columns, computed, no original data).
- Apply log events incrementally in per-prescription order and detect a missing sequence before it corrupts the view.
- Rebuild the projection from the log with a script, and prove equivalence with row counts and checksums — not vibes.
- Measure projection lag (log tail vs applied sequence) and decide what "behind" means operationally.
- State the two-reader invariant: current state is authoritative; the projection serves reads and replay; neither silently becomes truth.

## Warm-up

Re-read the projection section of `posts/series-4-product-sse/02-sse-correctness.md` (the `status_projection` DDL and the `projections.after(prescriptionId, from)` replay). Then, in your P05 database:

```sql
SELECT prescription_id, count(*) FROM prescription_status_history GROUP BY prescription_id ORDER BY 2 DESC LIMIT 5;
```

You should have at least one prescription with 3+ history rows. That is your log material for this kata.

## System specification

**Scope in**

- A `status_projection` table: `(patient_id, prescription_id, sequence_no, status, occurred_at)`, PK `(prescription_id, sequence_no)`, index on `(patient_id, prescription_id, sequence_no)` — mirror the blog post's shape exactly so the SSE replay queries work later.
- An applier: reads the log in per-prescription order, inserts projection rows, and refuses to apply a non-consecutive sequence (records a gap, does not write).
- A rebuild: truncate the projection, replay the whole log, verify row counts and checksums per prescription.
- A lag view: per-prescription `max(sequence_no)` in the log vs `max(sequence_no)` in the projection.
- Kotlin, single module, JDBC (whatever you chose in P02), migrations via the P01 pattern.

**Scope out**

- No RabbitMQ, no SSE endpoint, no worker topology — the feed is simulated by inserting into the log.
- No change to `prescriptions` (current state) — it stays authoritative for the GET; you are only adding the derived view.
- No global sequence numbers — the blog post already argues per-prescription, and this kata enforces it.

**Functional requirements (minimal)**

1. Applying the log to an empty projection yields one projection row per history row.
2. A second apply of the same log is a no-op (idempotent rebuild).
3. Applying an event with `sequence_no` not equal to `max(applied) + 1` is rejected and recorded as a gap, never applied.
4. Truncate + rebuild reproduces the identical projection.

**Constraints**

- Local Docker Postgres only.
- Evidence folder for `rebuild-rows.txt`, `gap-run.txt`, `lag-report.txt`.
- No claim that the projection is the source of truth — the README of the kata says the opposite in one sentence.

## Step-by-step code-along

### Step 1: Projection schema and the lag view

**Do:** Migration: create `status_projection` per the blog DDL plus `applied_at timestamptz not null default now()`. Then a view or query that shows lag:

```sql
SELECT h.prescription_id,
       max(h.sequence_no)        AS log_tail,
       max(p.sequence_no)        AS projection_head,
       max(h.sequence_no) - max(p.sequence_no) AS behind
FROM prescription_status_history h
LEFT JOIN status_projection p USING (prescription_id)
GROUP BY h.prescription_id;
```

**Run:** Apply the migration; run the lag query with an empty projection.

**Observe:** `log_tail` is populated, `projection_head` is NULL, `behind` = the whole history. That is the definition of "lag" you will measure for the rest of the kata and reuse as a signal in `A12_observability_slice.md`.

**Decision:** Should the projection carry `patient_id` (denormalized) or join to `prescriptions` for it? Nudge: the SSE isolation tests in the blog post query by patient; a denormalized copy is the price of a clean replay query, and it is a *derived* copy, so denormalization is not a consistency risk.

### Step 2: The applier — ordered, idempotent, gap-aware

**Do:** Kotlin `ProjectionApplier` with:

```kotlin
sealed interface ApplyResult {
    data class Applied(val prescriptionId: UUID, val sequence: Long) : ApplyResult
    data class Duplicate(val prescriptionId: UUID, val sequence: Long) : ApplyResult
    data class Gap(val prescriptionId: UUID, val expected: Long, val got: Long) : ApplyResult
}
fun apply(event: StatusEvent): ApplyResult
```

The apply logic: read `max(sequence_no)` for the prescription *and insert* in one transaction — you saw the check-then-act trap in `../rabbit/R05_idempotent_consumer.md`; the uniqueness constraint on `(prescription_id, sequence_no)` is the arbiter, exactly as the blog post requires. On a duplicate insert, return `Duplicate` and do nothing else.

**Run:** Insert 5 history rows for one prescription, apply them one at a time, print each `ApplyResult`.

**Observe:** `Applied` for 1..5, in order. Re-apply event 3: `Duplicate`, and the projection is unchanged. That single behavior is the whole idempotent-rebuild story.

**Decision:** Where does the "expected next sequence" live — computed in Kotlin before the insert, or left to the constraint? Nudge: the constraint is the guarantee; the Kotlin check is only there to produce a friendlier `Gap` result. Do not skip the constraint in code that "checked first."

### Step 3: Prove the rebuild

**Do:** A `rebuild.sh` (or a `RebuildProjection` main) that does: `TRUNCATE status_projection`, then replays `prescription_status_history ORDER BY prescription_id, sequence_no` through the applier. Add a checksum step:

```sql
SELECT count(*), sum(sequence_no) FROM status_projection;
SELECT count(*), sum(sequence_no) FROM prescription_status_history;
```

**Run:** `./rebuild.sh` twice in a row; capture output to `evidence/rebuild-rows.txt`.

**Observe:** Both counts and both sums match after each run, and the second run is a no-op (`Duplicate` everywhere). Record the elapsed time for the full replay — that number is your "rebuild from log" story for the interview ("the projection is a derived cache; a full rebuild is seconds on local data").

**Decision:** Truncate-and-rebuild vs apply-the-missing-tail as the recovery default? Nudge: for the exercise, truncate-and-rebuild is the *proof*; in production you would apply-the-missing-tail first and escalate to full rebuild. Note that ordering in one line of the README.

### Step 4: Break the order — the gap experiment

**Do:** With one prescription's events applied up to 4, hand-insert a history row with `sequence_no = 6` (skip 5) via SQL, and call `apply` on it.

**Run:** The applier against the row; then the lag query.

**Observe:** `Gap(expected = 5, got = 6)`, no projection row written, and the lag view still shows head=4. This is the exact behavior `posts/series-4-product-sse/02-sse-correctness.md` demands of the ordered consumer ("stop, log loudly, and dead-letter — never apply, never broadcast a status that jumps"). The `SequenceGap` sealed outcome you already defined in Exercise 3 maps onto `Gap` here.

**Decision:** Gap policy — quarantine the event, retry after a delay, or alert-and-hold? Nudge: for the kata, hold-and-alert is fine and you should write the one-sentence policy; Exercise 3 Milestone 5 makes you pick for the real consumer.

### Step 5: The two-reader invariant, proven

**Do:** For every prescription, compare projection rows to history rows (join on `(prescription_id, sequence_no, status)`), then compare the *current* state in `prescriptions` to the projection's max row.

**Run:** One assertion query; print mismatches (expect: zero).

**Observe:** The projection agrees with the log at every sequence, and the projection's last row agrees with `prescriptions.status`. Save the output. This is the query that will become an integration-test assertion in Exercise 3 Milestone 7 — SSE can never be ahead of the GET because both read committed projection/current rows.

**Decision:** Do you run this check on a schedule or in a test? Nudge: both, but the test is the one you can show in a walkthrough; the scheduled check is the operational promise.

### Step 6: Async apply — what lag actually looks like (optional)

**Do:** Make the applier run on a single-thread executor (one worker, like the ordered projection consumer must be), and fire 200 log inserts at it as fast as possible. Record the lag view while it catches up.

**Run:** Seed + async apply; poll the lag query every 200ms.

**Observe:** `behind` climbs, then drains to 0. You now have a real lag curve, which is the signal A12 will expose as a metric and the reason Exercise 3 keeps exactly one effective consumer per ordered stream.

## Try this

**Rebuild during a live burst.** Start inserting history rows continuously (a loop with 50ms sleeps). Mid-burst, run `rebuild.sh`. Then wait for the burst to end and check: the projection matches the log exactly, and the tail applied after truncation did not duplicate or vanish. The mechanism you should be able to name: the rebuild read a snapshot in log order, and late inserts are ordinary `sequence = max + 1` appends — no window can lose a row because there is no "latest wins" anywhere in the design.

**Second experiment — concurrent rebuilds.** Run two rebuilds at the same time. Expect exactly one of two outcomes, and write down which: either the unique constraint makes one fail cleanly (`Duplicate` storm), or your applier is missing the "single writer" discipline and you get constraint errors. Either outcome is evidence for the interview.

## Trade-off fork

Pick **one**, write 3–5 lines justifying it, and name the lost benefit.

- **A: projection table as a separate read model vs B: read the history table directly and derive the timeline in SQL.** A derived table keeps replay queries trivial and SSE isolation indexed by patient — at the cost of a writer, a lag, and rebuild machinery. Direct reads have zero lag and one fewer moving part — at the cost of a scan-and-group on every stream connect and no fast `after(seq)` index.
- **A: rebuild-from-log as the recovery path vs B: incremental apply as the only path.** Full rebuild is simple to prove and self-healing — it is also `O(entire log)` and blocks writes while it runs. Incremental apply is fast — and it silently trusts the log tail, so a corrupted tail is never discovered.
- **A: synchronous apply inside the business transaction vs B: async single-consumer apply.** Sync is trivially correct and has zero lag — it moves projection work into the critical path of every transition. Async keeps the GET fast — and introduces the lag signal, the gap policy, and the ordered-consumer discipline this whole kata is about.

## Hints

**Hint 1:** If the rebuild shows count mismatches, the first suspect is ordering: replay must be `ORDER BY prescription_id, sequence_no`, not global `sequence_no` — per-prescription sequences restart at 1, and a global sort will interleave two prescriptions and trip your own gap check. If `sum(sequence_no)` matches but rows differ, check for a `Duplicate` path that returned `Applied` — the `ApplyResult` sealed type exists precisely so this cannot be silent.

**Hint 2:** For the gap experiment, remember that `prescription_status_history` may have a `CHECK` or trigger you wrote in P05 — if the manual `sequence_no = 6` insert fails, the constraint is doing its job and you should insert via the same repository method you used for real transitions. For the live-burst rebuild, keep the insert loop and the rebuild in different sessions (or threads) — same database, different connections — so the race is real and not serialized by one JDBC connection.

## Checkpoint / success criteria

You may leave when:

- `evidence/rebuild-rows.txt` shows matching counts and sums across two consecutive rebuilds.
- The gap experiment produced `Gap(expected=5, got=6)` and left the projection untouched.
- The invariant query from Step 5 prints zero mismatches, including current-state vs projection head.
- The lag view works and you used it to watch a burst drain in step 6 (or documented why you skipped it).
- You can say aloud, pointing at the rebuild script: "the projection is a derived read model — I can drop it and rebuild it from the log, which is exactly why SSE replay can trust `Last-Event-ID`."

## Bottleneck & reflection questions

1. Your applier rejects a non-consecutive sequence. In Exercise 3, the same event is coming from RabbitMQ with inbox dedup in front — which of the two (inbox unique key or sequence check) actually prevents a duplicated *projection* row, and what does each one catch that the other misses?
2. The blog post says "latest wins is forbidden for status." What breaks in your rebuild script if someone adds a `DELETE ... WHERE sequence_no < x` cleanup job to `prescription_status_history`?
3. A lagging projection makes SSE stale but never wrong — which patient-visible failure mode does a lagging *current-state* table cause instead?
4. You denormalized `patient_id` into the projection. What query does that make fast, and what consistency rule must hold so it never disagrees with `prescriptions.patient_id`?
5. The rebuild is seconds on local data. What assumption makes it seconds, and what would change it to minutes — and what does the answer imply for Exercise 3's retention policy?

## Handoff

- **Next elective:** `A11_sse_hard_edges.md` (this projection is the read model the SSE layer must isolate, heartbeat, and authorize), then `A12_observability_slice.md` (projection lag becomes a signal).
- **Related showcase exercise:** `../../pharmacy-fulfillment/exercise_03_production.md`, Milestones 5 and 7 — the ordered projection consumer and the SSE path that "reads committed projection rows," with the rebuild as the recovery procedure.
- **Interview line:** "The projection is a derived read model over the append-only log — it is rebuildable in seconds, its rows match the log row-for-row, and a missing sequence is rejected rather than applied, which is what makes `Last-Event-ID` replay honest instead of approximate."

## Optional stretch

One harder twist: add a *second* consumer shape — two applier workers sharded by `prescription_id % 2` — and prove that per-prescription ordering survives while the two shards advance in parallel. Measure the burst drain time against the single worker, and write three sentences on when sharding a projection is justified and what it costs the gap policy.
