# P06 Index and EXPLAIN — Code-Along Elective

## Objective

Turn one deliberately slow queue read into an indexed read, capturing the `EXPLAIN ANALYZE` plan **before** and **after** as evidence. One primary objective: make "an index is an answer to a written-down query shape, verified against the real plan" a demonstrated habit — including the failure modes (stale statistics, wrong leading column, missing sort).

## Time box

~1.5 hours, core. The keyset stretch adds ~30 minutes.

## Prerequisites

- `P01_schema_and_migrations.md` — the `prescriptions` table, history table, and outbox partial index are required.
- `P02_persistence_style_kata.md` recommended — you need the real query shapes your repository produces (the blog's discipline is *query-shape-first*).
- `../glue/X01_docker_compose_trio.md` (local Postgres).
- Showcase position: before `../../pharmacy-fulfillment/exercise_02_optimization.md` (Milestones 5–6 are exactly this evidence).

## Blog & curriculum links

- Primary: `posts/series-2-postgres/04-indexes-query-plans.md` (the four query shapes, the partial-index walkthrough, the plan-reading discipline, keyset pagination).
- Secondary: `posts/series-2-postgres/02-transactions-isolation.md` (why the hot writes — conditional status updates — make index cost a real decision: PostgreSQL writes a new row version per update, and non-HOT updates touch every index on the table).
- Coach-assessment gap this attacks: "PostgreSQL DDL, indexes, … and query plans" — the transfer from SQL Server's clustered-index instincts to PostgreSQL heaps and partial indexes.

## Background & motivation

In SQL Server, the clustered-index instinct is strong: "make the patient list fast by clustering on `patient_id`." PostgreSQL tables are heaps — there is no maintained clustered order, and `CLUSTER` is a one-time rewrite the next writes undo. The transfer that *does* work is the B-tree model and the leading-column rule; the transfer that does not is treating `Seq Scan` as an error message. On a small local table a sequential scan is often the right plan, and the plan is information, not failure.

The bigger trap is ordering the work wrong. The blog's migration order says "add indexes after confirming the first query shapes" — and the kata enforces it: you write the query first, seed real data, capture the *unindexed* plan, then add the index and re-capture. Premature indexing is a write tax: every status transition writes a new row version, and non-HOT updates touch every index on `prescriptions` — which is the exact table P03 made hot.

This kata deliberately ignores connection pooling (the blog covers it; the challenge doesn't need tuning it), N+1 elimination (name it, Exercise 2 measures it), and scale claims. Its output is four saved plans and the sentences that explain them.

## Learning objectives

- Write down the system's real query shapes before touching DDL, and mark which already have primary-key coverage.
- Seed enough data to make plans meaningful, then control statistics with `ANALYZE`.
- Capture a baseline `EXPLAIN (ANALYZE, BUFFERS)` plan and identify `Seq Scan` + `Filter` + `Sort` nodes and estimate-vs-actual row mismatches.
- Add a partial index for a queue read (SQL Server: filtered index) and prove the plan loses the filter and the sort.
- Apply the leading-column rule and demonstrate a wrong index being ignored.
- Decide deliberately between composite and partial indexes, and between offset and keyset pagination.

## Warm-up

Read the "Name The Reads Before The Indexes" and "What An Index Actually Buys You" sections of `posts/series-2-postgres/04-indexes-query-plans.md` (about 5 minutes). Then confirm the P01 schema's shipped indexes and the current row count:

```sql
\di
select count(*) from prescriptions;
```

Observe: the history lookup index and the outbox partial index already exist; `prescriptions` has **no** index beyond the primary key. That is the board your kata starts from.

## System specification

**Scope in:** four query shapes (patient status by id, patient list, pharmacist queue, history timeline), realistic seeding, baseline + after plans, one partial index, one composite patient index, stale-statistics evidence.

**Scope out:** pooling tuning, N+1 elimination (deferred to Exercise 2), keyset pagination implementation (stretch), the packaging queue's own index (same pattern, name it), and any index with no query shape behind it.

**Functional requirements (minimal):**

- A `queries.md` file listing each read with predicate, ordering, and frequency.
- Seeded data large enough that plans differ meaningfully (a few thousand prescriptions; a small fraction in `AWAITING_APPROVAL`).
- Baseline and after-`ANALYZE` and after-index plans, all pasted.
- Justification per index: query shape, benefit, write cost.

**Constraints:** local Docker PostgreSQL; `EXPLAIN (ANALYZE, BUFFERS)` only against the real database — no in-memory or mocked plans (P07's testing post makes this a rule); migration files forward-only.

## Step-by-step code-along

### Step 1 — Write the query shapes down

- **Do:** create `queries.md` and list the reads the Foundation product actually runs:
  1. `WHERE id = :id` — patient status (primary key, nothing to do).
  2. `WHERE patient_id = :pid ORDER BY created_at DESC LIMIT 20` — patient list.
  3. `WHERE status = 'AWAITING_APPROVAL' ORDER BY created_at, id LIMIT 25` — pharmacist queue.
  4. `WHERE prescription_id = :id ORDER BY sequence_number` — status timeline (index shipped in P01).
  5. `WHERE published_at IS NULL AND available_at <= NOW() ORDER BY available_at, occurred_at LIMIT :batch` — outbox poll (partial index shipped in P01).
- **Run:** nothing — the point is the *list precedes the DDL*.
- **Observe:** shapes 1, 4, and 5 are already served. That leaves exactly two decisions for this kata: the patient list and the pharmacist queue.
- **Decision:** none — but mark in `queries.md` which reads are "patient-facing, high frequency" vs "worker-facing" — the blog's frequency argument drives the queue index's partial-index choice.

### Step 2 — Seed data that makes plans matter

- **Do:** load a few thousand prescriptions with `generate_series`, a handful of distinct `patient_id` values, and a small slice left in `AWAITING_APPROVAL`. A plausible mix: ~40,000 prescriptions, ~1,500 awaiting approval, ~200 distinct patients.
- **Run:** `select status, count(*) from prescriptions group by status;` and — deliberately — **skip** `ANALYZE` for now.
- **Observe:** the distribution looks right. Do not judge any plan yet.
- **Decision:** seed as SQL here vs through the repository. Nudge: raw `generate_series` SQL is disposable lab data; the repository is for product fixtures. Keep the seed script in your notes for Exercise 2's load harness.

### Step 3 — The baseline plan (this is the "slow query")

- **Do:** run the pharmacist queue read unindexed:
  ```sql
  EXPLAIN (ANALYZE, BUFFERS) SELECT id, status, created_at
  FROM prescriptions
  WHERE status = 'AWAITING_APPROVAL'
  ORDER BY created_at, id
  LIMIT 25;
  ```
- **Run:** it, twice (first run pays cold-cache costs; judge the second).
- **Observe:** a `Parallel Seq Scan` (or plain `Seq Scan`) with a `Filter: (status = 'AWAITING_APPROVAL'::text)`, then a `Sort` node, and — the signature symptom — estimated `rows` wildly below actual `rows`. Work proportional to the whole table, on a table that only grows.
- **Paste the plan** into `plans.md` with one annotation per node.
- **Decision:** none yet — you have not earned an index until the next step.

### Step 4 — The stale-statistics trap

- **Do:** run `ANALYZE prescriptions;` then re-run the same `EXPLAIN`.
- **Run:** compare the two plans side by side.
- **Observe:** the estimated `rows` moved toward actual — a plan judged before `ANALYZE` was judged on stale numbers. Autovacuum handles production; a fresh Docker volume holds whatever the seed left behind.
- **Paste both estimate lines.** This is the plan-reading discipline from the blog: "I guessed 1000 and found 32543" is a statistics story before it is an index story.
- **Decision:** none.

### Step 5 — The partial index for the queue

- **Do:** add the migration:
  ```sql
  CREATE INDEX prescriptions_approval_queue_idx
      ON prescriptions (created_at, id)
      WHERE status = 'AWAITING_APPROVAL';
  ```
  Not the "natural" composite `(status, created_at, id)` — think about what that would carry (every prescription that ever existed) versus this one (the queue itself, in order).
- **Run:** `EXPLAIN (ANALYZE, BUFFERS)` the queue read again.
- **Observe:** one `Index Scan using prescriptions_approval_queue_idx`, **no filter node** (the predicate is implied by the index), **no sort** (the index delivers the order), `rows` matching reality. Plan cost drops from table-proportional to queue-sized.
- **Paste the plan** next to the baseline and write two sentences on the trade: every transition out of `AWAITING_APPROVAL` removes the row from this index (the new row version fails the predicate), so approvals write the index on the hot path — a good trade when queue reads vastly outnumber transitions. Say that sentence aloud; it is the interview answer to "what does the partial index cost?"
- **Decision:** single partial index vs two partial indexes (a future `'APPROVED'` packaging queue gets its own — never one index serving two queues "to save a file").

### Step 6 — The patient list index (and the wrong index)

- **Do:** write the patient list query first, then the index for it as written: equality column first, then the ordering column:
  ```sql
  CREATE INDEX prescriptions_patient_created_idx ON prescriptions (patient_id, created_at DESC);
  ```
- **Run:** `EXPLAIN (ANALYZE, BUFFERS) SELECT id, status, created_at FROM prescriptions WHERE patient_id = :pid ORDER BY created_at DESC LIMIT 20;`
- **Observe:** an `Index Scan` in `created_at DESC` order, no sort.
- **Then the trap:** create `(created_at, patient_id)` on a scratch schema copy and re-plan the same query.
- **Observe:** the planner ignores it — the leading column does not match the equality predicate, so the index cannot be applied to the filter without scanning in time order. This is the leading-column rule, demonstrated instead of asserted.
- **Paste both plans**, labeled right/wrong.
- **Decision:** `DESC` on `created_at` — deliberate, once. A second "both directions" index is a reflex, not a decision.

### Step 7 — The history timeline: index-only scan check

- **Do:** `EXPLAIN (ANALYZE, BUFFERS)` the P05 timeline read against the shipped history index.
- **Run:** it with a prescription that has several history rows.
- **Observe:** an `Index Scan` using `prescription_status_history_lookup_idx`; if the plan shows an index-only scan, note the surprise and why it is (all needed columns in the index) — then **do not** add `INCLUDE` columns until a plan shows index fetches dominating.
- **Paste** the plan with one line of interpretation.
- **Decision:** none.

## Try this

**The wrong-leading-column experiment, as a test, not a story.** On a scratch schema (copy P01, do not touch the main one), create `prescriptions_wrong_idx ON prescriptions (created_at, patient_id)` and run the patient list query. Capture the plan where the planner ignores the index and either scans or picks your correct index.

- **Expected:** `prescriptions_wrong_idx` never appears in the plan (or appears only via a scan the planner rejects). Paste it.
- **Second experiment:** after Step 5, run the queue query with `OFFSET 5000 LIMIT 25` and `EXPLAIN ANALYZE` it, then the same with a keyset-style predicate `AND (created_at, id) > (:c, :i) ORDER BY created_at, id LIMIT 25`. Compare the rows each plan actually visits.
- **Observe:** offset re-walks the first 5,000 rows every page — and, correctness note, new rows inserted between page requests can duplicate or skip rows across pages. Keyset bounds the work per page and is stable under new submissions. Paste both plans and write the one-paragraph justification for the pharmacist queue.

## Trade-off fork

**Option A — composite index `(status, created_at, id)` for the queue:** one index for the status filter, works for *any* status — the packaging queue reuses it later. Costs: it carries every prescription that ever existed, and it stays huge as history grows, while only ~2% of rows are ever read by any single queue.

**Option B — partial index `(created_at, id) WHERE status = 'AWAITING_APPROVAL'`:** as small as the queue itself, ordered as the queue reads, and its churn is bounded to queue memberships. Costs: a second queue (say `'APPROVED'` for packaging) needs its own partial index, and every transition into/out of the status writes this index on the hot approval path.

Choose one and write 3–5 lines justifying it for *this* workload, naming what the other buys. The blog leans partial for a queue whose read frequency dwarfs its transition frequency — but a product where queue membership flips constantly might prefer the composite. The fork is a workload argument, and the interview wants to hear you make it, not memorize it.

## Hints

**Hint 1 (mild):** the four plans you need for evidence are: baseline (Seq Scan + Filter + Sort + bad estimate), post-`ANALYZE` (estimate fixed), post-partial-index (Index Scan, no filter, no sort), and the wrong-index ignore. Capture each with `(ANALYZE, BUFFERS)` and annotate the nodes in one sentence each.

**Hint 2 (stronger):** if your baseline plan on 40k rows is *not* a seq scan — if the planner already picks an index or estimates perfectly — your seed is too small or too uniform. Queue shape needs the skewed distribution (few `AWAITING_APPROVAL`, many historical rows) that the real product has; a 300-row table with warm cache proves nothing, and the blog says so.

## Checkpoint / success criteria

You may leave when:

- [ ] `queries.md` lists the four real query shapes and marks which are already primary-key or shipped-index served.
- [ ] `plans.md` contains the annotated baseline, stale-statistics, partial-index, patient-list (right and wrong index), and timeline plans — all `EXPLAIN (ANALYZE, BUFFERS)` against real PostgreSQL.
- [ ] Every index added has a written query shape, benefit, and write cost; at least one tempting index is listed with the reason it was not added.
- [ ] You can read a plan aloud: what a `Seq Scan` + `Filter` says, what a `Sort` node says about an index, and what an estimate-vs-actual mismatch means.

## Bottleneck & reflection questions

- Why is an index a response to a query, not to a table name — and which of this system's reads would you refuse to index, and why?
- The approval transition from P03 writes this table. How does the partial queue index tax that hot path, and why is the trade still good?
- A `Seq Scan` on a 300-row table with warm cache is a non-event. How does that sentence keep you from over-indexing in the challenge — and when does the same sentence stop being true?
- What does the `(status, created_at, id)` vs partial index fork reveal about the workload the candidate assumes? Which assumption, if wrong, flips the answer?
- Your plan says `Index Scan` and the query is "fast". What does a plan **not** prove about production? (The blog's list — scale, hardware, pool, concurrency — is the interview answer.)

## Handoff

- **Next electives:** `P07_testcontainers_postgres.md` — the plan discipline becomes a testing rule ("plans from an in-memory database are fiction"). The outbox poll plan you touched in `queries.md` is the shape `../advanced/A09_postgres_under_contention.md` pairs with `FOR UPDATE SKIP LOCKED`.
- **Showcase:** `../../pharmacy-fulfillment/exercise_02_optimization.md` Milestones 5–6 require exactly this evidence: query-shape inventory, migration diff, before/after plans, omitted-index rationale, and a statement of what a local plan cannot prove. Your `queries.md` and `plans.md` are those milestones' starting artifacts.
- **Rabbit handoff:** `../rabbit/R01_topology_scratchpad.md` — the outbox partial index you verified here is what keeps the R-track relay's poll narrow while it claims rows; keyset pagination is the same ordering discipline the relay's `LIMIT :batch` polls use.
- **Interview line you should be able to say aloud:** "I write the query shapes down before touching DDL — the patient list gets `(patient_id, created_at DESC)`, the pharmacist queue gets a partial index that mirrors the queue itself, and everything else is validated with `EXPLAIN ANALYZE` against real data: no full-table filter, no sort the index should have removed, estimates that match reality."

## Optional stretch

Implement keyset pagination for the pharmacist queue in your P02 style using the row-comparison tuple `(created_at, id) > (:cursor_created_at, :cursor_id)` (the blog's repository sketch shows the shape). Verify against the seeded data: page through the whole `AWAITING_APPROVAL` set with no duplicates and no misses while another session inserts new prescriptions mid-pagination. Paste the pagination evidence and write three sentences on when offset pagination is still the right call (a patient history page with page numbers, where the queue-stability argument does not apply).
