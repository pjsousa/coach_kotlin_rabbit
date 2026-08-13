# A09 Postgres Under Contention — Code-Along Elective

## Objective

Prove how PostgreSQL behaves when two transactions fight over rows: observe a real deadlock (SQLSTATE `40P01`), fix it with consistent lock ordering, and then build a small claim-queue that uses `SKIP LOCKED` so concurrent workers never block each other. You leave with one evidence folder of `pg_locks` snapshots, deadlock log lines, and row-count proofs, plus a rehearsable "deadlock or wait?" story for the interview.

## Time box

~2.5–3h. Core: steps 1–6. Optional: the three-way deadlock in "Try this" and the retry-loop experiment (step 7).

## Prerequisites

- `../postgres/P03_approve_once_race.md` (conditional `UPDATE`, affected rows) and `../postgres/P04_last_unit_inventory.md` (atomic decrement, `FOR UPDATE`).
- `../postgres/P06_index_and_explain.md` is a soft prerequisite — you will read plans once, and P06 taught the habit.
- Docker Compose with a pinned `postgres:16` (same image your Exercise 2 stack uses) or `../glue/X01_docker_compose_trio.md`.
- A tiny Kotlin JDBC harness: plain `DriverManager` is fine; reuse whatever repository pattern you settled on in P02.
- Position: **during Exercise 3** (before `showcase_projects/pharmacy-fulfillment/exercise_03_production.md` Milestone 5). A09 feeds the multi-line inventory and relay-claim concurrency decisions.

## Blog & curriculum links

- Primary: `posts/series-2-postgres/02-transactions-isolation.md` — the `FOR UPDATE` vs conditional-update section, lock order, and the six "prove the behavior" tests.
- Secondary: `posts/series-2-postgres/03-inventory-reservation.md` (multi-medication deadlock origin) and `posts/series-2-postgres/04-indexes-query-plans.md` (why a bitmap/seq scan changes who waits on what).
- Coach-assessment gap: PostgreSQL implementation gap (SQL Server → Postgres transfer), specifically "isolation, row locks, conditional updates, and query plans."

## Background & motivation

You already proved in P03 that one conditional `UPDATE` wins a race and in P04 that two reservation transactions serialize on one hot row. Both of those were *contention you wanted*. This kata is about the three behaviors that sit right next to them and are almost never taught together:

1. **Deadlocks happen locally.** Two transactions, two rows, opposite lock order — on a single Postgres instance, with no replicas and no cloud — is a two-minute demo. Most Java-background engineers have read about deadlocks and never seen the `40P01` log line. You will see it today.
2. **`SKIP LOCKED` is not a lock-hack, it is a work-claim primitive.** The relay you built in `../rabbit/R07_outbox_relay_mini.md` and the packaging claim in Exercise 3 both need "claim rows nobody else is claiming, don't wait." `SELECT ... FOR UPDATE SKIP LOCKED` is the Postgres idiom for exactly that, and it is also a very common senior-interview probe.
3. **The retry question is a design question.** "Deadlock: retry or fix?" is only answerable with evidence of *where* the deadlock came from. This kata gives you the evidence discipline to answer it.

What this kata deliberately ignores: connection pooling behavior (HikariCP settings), transaction timeout tuning, and any multi-node Postgres story. One local instance, two or three sessions, honest SQL.

## Learning objectives

- Produce and recognize a genuine Postgres deadlock: `pg_locks` snapshot, victim choice, `40P01` in the log.
- Explain what `READ COMMITTED` re-evaluation does to a blocked `UPDATE` vs what it does to a blocked `SELECT FOR UPDATE`.
- Make two-row locking deadlock-free with a stable lock order, and prove it with a repeatable test.
- Claim rows without waiting using `FOR UPDATE SKIP LOCKED`, and prove disjoint claims with row counts.
- Decide — with evidence — between retrying a deadlock and fixing the SQL.
- Read a real query plan to predict whether a statement locks rows in a safe order (`posts/series-2-postgres/04-indexes-query-plans.md`).

## Warm-up

Re-read the "When `SELECT FOR UPDATE` Is The Better Tool" section of `posts/series-2-postgres/02-transactions-isolation.md` and the multi-line reservation passage in `posts/series-2-postgres/03-inventory-reservation.md`. Then, in one `psql` session:

```sql
SELECT version();
SHOW lock_timeout;   -- default: 0 = wait forever
```

And `SHOW deadlock_timeout;` — that is how often Postgres checks for deadlock cycles. Note the value. You will watch it in action.

## System specification

**Scope in**

- Two tables: `inventory` (medication_id, available_quantity) and `reservation_claims` (claim_id, medication_a_id, medication_b_id, worker_id, status) — or, if you prefer, reuse the Exercise 2 tables. The claim table is purpose-built so the kata does not become a second fulfillment product.
- A Kotlin harness that can open N JDBC connections and run parameterized SQL on each, printing per-connection timing.
- A `psql` terminal as the second and third session for the manual deadlock.
- Postgres server logs enabled locally so `40P01` appears.

**Scope out**

- No RabbitMQ, no Spring, no HTTP. P03 already proved conditional updates; this kata is purely about Postgres lock behavior.
- No `SERIALIZABLE` — the blog post already argued why it is machinery-without-benefit for this workload.
- No `pg_advisory_lock`, no pool tuning.

**Functional requirements (minimal)**

1. Two sessions can deadlock each other on two rows in opposite lock order, with the evidence captured.
2. The same workload, with a stable lock order, produces waits but never a deadlock.
3. A claim worker using `FOR UPDATE SKIP LOCKED` can claim its share of available rows while another worker holds the others, with no `40P01` and no duplicate claim.

**Constraints**

- Local Docker only; single Postgres instance.
- All evidence saved under `evidence/` in the kata folder (file names like `deadlock.log`, `locks-contention.csv`, `claims-by-worker.txt`).
- Kotlin where code is written; `psql` where manual session control is the point.

## Step-by-step code-along

### Step 1: Seed the lab

**Do:** Create `inventory` with ~100 rows (one row per medication, `available_quantity = 10`), and `reservation_claims` with two medication columns and a `worker_id text` + `status text` ('PENDING'/'CLAIMED'). Write the Kotlin harness skeleton: `openConnections(n: Int): List<Connection>` and a helper `runIn(conn, sql, params)` that prints elapsed ms and the `SQLState` on failure.

Kotlin note for the Java veteran: prefer `data class ClaimKey(val a: String, val b: String)` and pass the pair around instead of two loose strings — the claim's lock order is a value, and value classes make the ordering explicit.

**Run:** `psql -c "SELECT count(*) FROM inventory"` and a harness smoke run that opens two connections and does `SELECT 1`.

**Observe:** Both connections work; the log prints no errors.

**Decision:** Where does the harness live — a test under `src/test/kotlin` or a `main` you run by hand? Nudge: a JUnit test with `@Disabled`-style toggle for the deadlock cases keeps the evidence repeatable, and a test you can re-run is the artifact you show in the walkthrough.

### Step 2: Baseline — same-order locking is a wait, not a deadlock

**Do:** Two transactions, each locking two inventory rows *in the same ascending order* (`WHERE medication_id = 'm001'` then `'m002'`), via `SELECT ... FOR UPDATE`. Interleave them with a latch or by hand in two `psql` windows so txn A locks row 1 while txn B locks row 2, then both proceed to the second row.

**Run:** `psql` window A: `BEGIN; SELECT * FROM inventory WHERE medication_id='m001' FOR UPDATE;` then window B: `BEGIN; SELECT * FROM inventory WHERE medication_id='m002' FOR UPDATE;` then B: lock `m001` (it will hang), then A: lock `m002`.

**Observe:** A's second lock waits; once A commits, B completes. In a third window, while B waits, run:

```sql
SELECT pid, state, wait_event_type, wait_event
FROM pg_stat_activity
WHERE state = 'active' AND wait_event IS NOT NULL;
```

You should see one session with `wait_event_type = Lock` and `wait_event` naming the lock (`transactionid`). That is a *blocked* transaction — the healthy outcome. Save the snapshot.

**Decision:** `lock_timeout` — leave it at 0 for the lab, or set a few seconds so the wait is bounded? Nudge: keep 0 here so you see the true wait, then revisit in the retry step.

### Step 3: Produce the deadlock

**Do:** Change txn B to lock in *descending* order (`m002` then `m001`). Same interleaving: A locks `m001`, B locks `m002`, then each tries the other's row.

**Run:** Same as Step 2 with B's order flipped.

**Observe:** Within `deadlock_timeout` (default 1s), one session returns:

```
ERROR:  deadlock detected
DETAIL:  Process 1234 waits for ShareLock on transaction 567; blocked by process 567.
Process 567 waits for ShareLock on transaction 1234; blocked by process 1234.
```

Copy the full DETAIL block — that is the interview evidence. Then check the Postgres log file for the server-side `LOG: process ... detected deadlock`. Save both.

Also capture `pg_locks` *during* the deadlock wait (before Postgres resolves it) — query it from a third session inside the 1s window, or raise `deadlock_timeout` to `5s` for the lab run so you can snapshot it at leisure. Note that `mode = ShareLock` on the other transaction's id is the "waiting on txn" artifact.

**Decision:** Did you flip the order in the SQL or in the caller? Nudge: fixing it in one place (the SQL) is the stable-order discipline; fixing it in callers is how it regresses.

### Step 4: Prove the fix — stable lock order everywhere

**Do:** Sort the two medication ids before locking, in both transactions (`val (first, second) = listOf(a, b).sorted()`), so all lock acquisition is ascending. Re-run the Step 3 interleaving.

**Run:** The same interleaving, same windows.

**Observe:** No `40P01`; the second transaction waits on the first transaction's row lock and proceeds after commit. The `pg_locks` snapshot shows only `tuple`/`transactionid` waits — never a cycle. This is the exact fix the blog post names ("use a consistent row order when locking multiple rows") and the one you will state for Exercise 3's multi-line reservation.

**Decision:** Is the sort in the service layer or in SQL (`ORDER BY`-driven)? Nudge: keep it in the repository/statement layer so every future caller inherits the order — that is the "owned invariant" phrasing for the walkthrough.

### Step 5: `FOR UPDATE SKIP LOCKED` — the claim queue

**Do:** Write a worker loop that claims rows: on each iteration, `BEGIN; UPDATE reservation_claims SET status='CLAIMED', worker_id=:me WHERE claim_id IN (SELECT claim_id FROM reservation_claims WHERE status='PENDING' ORDER BY claim_id LIMIT 5 FOR UPDATE SKIP LOCKED) RETURNING claim_id; COMMIT;` — or the two-statement `SELECT ... FOR UPDATE SKIP LOCKED` then `UPDATE` if you want to see the locked rows first. Seed 100 PENDING claims. Run three workers from the harness, each with its own connection.

**Run:** The harness, workers 1–3, printing each claim and its worker. Then `SELECT worker_id, count(*) FROM reservation_claims GROUP BY worker_id`.

**Observe:** Every row claimed by exactly one worker; total = 100. No `40P01`, and — the important part — a worker that finds no PENDING rows returns zero rows immediately instead of blocking on another worker's lock. Contrast with plain `FOR UPDATE` (no `SKIP LOCKED`): a worker in a full queue *waits* for the lock owner, which serializes the claim.

Save `claims-by-worker.txt`. If you want the plan: `EXPLAIN SELECT ... FOR UPDATE SKIP LOCKED` and confirm the `LockRows` node.

**Decision:** Batch size — 5 here; the relay in Exercise 3 will need its own. Nudge: batch is throughput-vs-claim-fairness; note which one you optimized.

### Step 6: Apply the same thinking to the relay claim

**Do:** Port the claim loop to the outbox-claim shape you know from `../rabbit/R07_outbox_relay_mini.md`: claim pending outbox rows with `FOR UPDATE SKIP LOCKED`, but now add two workers against the *same* table and prove disjoint claims.

**Run:** Two relay workers, 40 pending rows, run to exhaustion.

**Observe:** Total published rows = 40, each row handled once, no waits logged. This is the "claim without contention" pattern Exercise 3 Milestone 2 asks you to defend — you now have it as proven evidence instead of a paragraph.

### Step 7: Deadlock retry — policy or fix? (optional)

**Do:** Make the Step 3 deadlock reappear *behind a service method*: a `retryWithJitter { sql }` wrapper that catches SQLState `40P01`, waits 20–100ms, retries up to 5 times. Instrument it: count retries, log the SQLState.

**Run:** Re-run the deadlock workload through the wrapper 50 times.

**Observe:** Retries recover most cases — and a failure count > 0 remains. That residual is the point: retry hides the symptom, stable lock order removes the cause. Record `deadlock-retries.csv`.

## Try this

**Three-way deadlock.** Three transactions, three rows, cyclic lock order (A: 1→2, B: 2→3, C: 3→1), acquired in lockstep with three `psql` windows. Observe: Postgres detects the cycle and chooses a victim (usually the lowest cost or latest to acquire), aborts it with `40P01`, and the remaining two transactions proceed. Note *which* transaction Postgres killed and why the log names the two waiters in the DETAIL line. This is the strongest possible "I have seen this in the wild" evidence for an interview, and it takes five minutes.

**SKIP LOCKED starvation probe.** One worker with `SKIP LOCKED` and a producer that continuously inserts PENDING rows with a short sleep. Observe the worker never blocks and never sees a row twice — and consider whether a heavily contended queue can starve a row that is perpetually held-and-released.

## Trade-off fork

Pick **one**, write 3–5 lines justifying it, and name the lost benefit. Bring the note to the interview.

- **A: `FOR UPDATE SKIP LOCKED` for outbox/claim rows vs B: plain `FOR UPDATE` with `lock_timeout` + retry.** SKIP LOCKED never waits, so a worker starves the queue's back edge; plain FOR UPDATE guarantees every row is eventually claimed by whoever waited, at the cost of head-of-line blocking and retry loops on timeout.
- **A: consistent lock order (sort before lock) vs B: per-row ordering driven by the query plan.** The sort is explicit and survives plan changes; relying on the plan's chosen scan order is free but breaks the moment an index changes the access path.
- **A: deadlock retry loop vs B: design fix (stable order / single-statement predicates).** Retry is small and covers multi-statement commands you cannot order; it is also the classic "hide it" smell if the true fix is one `ORDER BY` away.

## Hints

**Hint 1:** A deadlock requires the *interleaving*, not just opposite SQL. If the race does not fire, use the `deadlock_timeout` bump to `5s` and a third session to prove both transactions hold their first lock before either proceeds. The `pg_stat_activity` + `pg_locks` pair in one window is your ground truth.

**Hint 2:** For Step 5, if you see duplicate claims or a `40P01`, check the `LIMIT` inside the `SELECT ... FOR UPDATE SKIP LOCKED` — it must be inside the subquery, not applied after the lock. If you see zero rows claimed by a worker while rows are still PENDING, check your `ORDER BY` is on a stable key so concurrent claims do not re-scan the same tail. If Step 6's two workers still contend, confirm both use the *same* `SKIP LOCKED` subquery shape — one worker with plain `FOR UPDATE` silently reintroduces waiting.

## Checkpoint / success criteria

You may leave when:

- `evidence/deadlock.log` contains the `40P01` DETAIL block and the server-side `LOG:` line.
- The same-interleaving run after the stable-order fix completes with zero deadlocks, 20/20 times.
- `claims-by-worker.txt` shows 100 rows, each claimed exactly once, split across 3 workers.
- `pg_locks` snapshots exist for the blocked-wait (step 2) and the deadlock (step 3) cases, and you can read `wait_event` off them without a tutorial.
- You can state aloud, with the evidence in hand: "a deadlock is a lock-order bug with a victim; a blocked wait is a serialization point with a retry option."

## Bottleneck & reflection questions

1. When two updates contend on one row under `READ COMMITTED`, Postgres re-checks the `WHERE` predicate after the blocker commits — but a `SELECT FOR UPDATE` that then decides in Kotlin does not. Which of your Exercise 3 transitions must never be re-checked post-wait, and what does that mean for the lock choice?
2. The relay claims rows with SKIP LOCKED, then marks `published_at` after broker confirms. Where in that flow would a *new* lock-order interaction with the outbox table's `published_at` index appear?
3. A deadlock victim's transaction is rolled back by the server. What does that imply for `@Transactional` methods that catch `DataIntegrityViolationException` and continue?
4. You raised `deadlock_timeout` for the lab. What operational downside does a high value create in production, and what would you set it to on the Exercise 3 stack?
5. If two workers both `SKIP LOCKED` a bounded batch, one can monopolize the queue. What does that mean for the "fairness" of your outbox claim in Exercise 3 Milestone 2?

## Handoff

- **Next elective:** `A13_chaos_drill_script.md` (reuses the claim pattern under kill/restart) or `A03_outbox_at_scale_local.md` (batch relay, stuck rows).
- **Related showcase exercise:** `../../pharmacy-fulfillment/exercise_03_production.md`, Milestones 2 and 5 — the relay claim (Milestone 2) and the multi-line reservation lock-order defense (Milestone 5).
- **Interview line:** "I proved a two-row deadlock under opposite lock orders, fixed it with a stable ascending lock order inside the repository, and then moved outbox claiming to `FOR UPDATE SKIP LOCKED` so concurrent relay workers claim disjoint batches without waiting — I have the `pg_locks` snapshots and row counts."

## Optional stretch

One harder twist: replace the fixed lock order with `pg_advisory_xact_lock` keyed on the sorted pair, and measure the same 100-claim workload. Compare contention and deadlock-freedom vs row locking, and write two sentences on when advisory locks are the honest tool (multi-row business objects, not per-row inventory).
