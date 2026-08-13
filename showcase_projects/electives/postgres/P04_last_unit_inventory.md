# P04 Last-Unit Inventory — Code-Along Elective

## Objective

Prove that two concurrent reservations for the last unit of a medication cannot both succeed: the atomic conditional decrement lets exactly one claim the stock, the loser gets a clean zero-row answer, and `available_quantity` never goes negative. One primary objective: demonstrate the lost-update failure and its fix with real two-session/two-thread evidence.

## Time box

~2 hours, core. The deadlock stretch adds ~30 minutes.

## Prerequisites

- `P01_schema_and_migrations.md` — `inventory`, `inventory_reservations`, and the seeded medications are required.
- `P03_approve_once_race.md` recommended first — same conditional-update family, and you will reuse its latched race harness. The blog sequence puts approval first for exactly this reason.
- `../glue/X01_docker_compose_trio.md` (local Postgres) and the P02 repository seam.
- Showcase position: before `../../pharmacy-fulfillment/exercise_02_optimization.md` (Milestone 3 is this race productized). Prepares `../advanced/A09_postgres_under_contention.md` on the deadlock side.

## Blog & curriculum links

- Primary: `posts/series-2-postgres/03-inventory-reservation.md` (the atomic decrement, the two-transaction timeline, the lifecycle walk).
- Secondary: `posts/series-2-postgres/02-transactions-isolation.md` (the reservation transaction shape and `RETURNING` discipline).
- Coach-assessment gap this attacks: "two simultaneous orders competing for the last medication unit" — the exact production-incident question the assessment names as an interview drill.

## Background & motivation

One unit of Amoxicillin remains. Two prescriptions arrive. What happens in the next milliseconds is the sharpest test of the persistence design, because a wrong answer is not a cosmetic race — it is a patient promise broken hours later at the fulfillment counter.

Three naive approaches fail in instructive ways, and the kata makes you watch all three fail before showing the fix:

1. **Read-check-write in the application** — both requests read `1`, both pass `if (available >= q)`, both write `0`. Two committed reservations, zero stock. `@Transactional` does not help; the decision was made from a stale value.
2. **Unconditional decrement** — `available_quantity = available_quantity - :q`. With one unit left, the second writer waits, then decrements the new row version to `-1` and the `CHECK (available_quantity >= 0)` constraint aborts it with SQLSTATE `23514`. The invariant survives but a routine business situation surfaces as a database exception.
3. **Application-level locks** (`synchronized`, `ReentrantLock`) — serializes threads in one JVM, does nothing against a second instance or a manual script. Any design whose correctness needs one application instance is fragile.

The fix is the atomic conditional decrement, where the predicate `available_quantity >= :q` and the arithmetic both live inside the write. PostgreSQL coordinates the race: writers to the same row serialize on the row lock; the loser, unblocked at `READ COMMITTED`, re-evaluates the predicate against the newest committed version and matches zero rows. Readers are never involved — MVCC means a patient polling status never waits behind a reservation, a real difference from SQL Server's locking `READ COMMITTED` default.

This kata deliberately ignores approval transitions (P03), history/outbox discipline (P05), deadlock machinery and `SKIP LOCKED` (A09), and broker-side retry semantics (R-track).

## Learning objectives

- Reproduce the read-check-write lost update and the constraint-abort path with real sessions, capturing both transcripts.
- Implement the atomic decrement with `RETURNING` and map "no row" to a clean insufficient-stock outcome.
- Run a last-unit race (two sessions and/or a latched two-thread test) with exactly one winner — evidence, not vibes.
- Build the reservation row and the all-or-nothing multi-line rule inside one transaction.
- Release a reservation exactly once, using a status predicate so a second release affects zero rows.
- Distinguish retryable database failures from definitive outcomes: insufficient stock is an answer, `40P01` is retryable.

## Warm-up

Read the "Implementations That Lose The Last Unit" and "The Atomic Decrement Under `READ COMMITTED`" sections of `posts/series-2-postgres/03-inventory-reservation.md` (about 5 minutes). Then, in `psql`, force the seed to a known state — one unit of Amoxicillin:

```sql
UPDATE inventory SET available_quantity = 1
WHERE medication_id = '00000000-0000-0000-0000-000000000001';
select medication_id, available_quantity from inventory where medication_id = '00000000-0000-0000-0000-000000000001';
```

Open a **second** session. From now on, every claim about this elective is a transcript.

## System specification

**Scope in:** the single-medication reservation decision (atomic decrement + reservation row), the last-unit race, the all-or-nothing multi-line rule, and the release-once transition.

**Scope out:** approval/rejection workflows (P03/P05), fulfillment consume lifecycle (mention it, don't build it — consumption is a status change, not a second decrement), deadlock *detector* analysis (A09), outbox rows for reservation events (R-track), and reservation expiry (name it as a documented limitation, the blog does).

**Functional requirements (minimal):**

- `tryReserve(medicationId, quantity): ReservationResult?` — null iff the claim did not happen (unknown medication or insufficient stock).
- The reservation row and the decrement commit together.
- Multi-line reservations are all-or-nothing, processed in a stable order.
- Release uses `WHERE status = 'RESERVED'` and restores stock exactly once.
- Never a negative `available_quantity`, in any run.

**Constraints:** local Docker PostgreSQL; real concurrency (sessions or latched threads); evidence captured per step.

## Step-by-step code-along

### Step 1 — Watch the lost update happen

- **Do:** nothing to write. Session A: `select available_quantity from inventory where medication_id = '...001';` → `1`. Session B: same → `1`.
- **Run:** both sessions issue `UPDATE inventory SET available_quantity = 1 - 1 WHERE medication_id = '...001';` — wait, that is already wrong; use the value you *read*: session A `UPDATE ... SET available_quantity = 1 - 1 ...;`, session B `UPDATE ... SET available_quantity = 1 - 1 ...;`. Commit both.
- **Observe:** both report `UPDATE 1`. Final quantity is `0`, and *two* claims were made against one unit. That is the lost update, in the shape the blog's Kotlin example produces.
- **Paste both transcripts** with the final `select`. Label it **"read-check-write: both win"**.
- **Decision:** none — you just created the bug the whole elective removes.

### Step 2 — Watch the constraint catch the unconditional decrement

- **Do:** reset to one unit (`UPDATE inventory SET available_quantity = 1 ...`). Both sessions run the *unconditional* decrement `SET available_quantity = available_quantity - 1 WHERE medication_id = '...001'`.
- **Run:** session A first, then session B (it will wait), then commit A.
- **Observe:** B aborts: `ERROR: new row for relation "inventory" violates check constraint "inventory_available_quantity_ck"` — SQLSTATE `23514`. The invariant survived, but the loser got a database exception instead of a business outcome.
- **Paste the error.** Label it **"unconditional decrement: constraint as crash"**.
- **Decision:** none. Note the lesson for the interview: the CHECK is a safety net, not the mechanism.

### Step 3 — The atomic decrement

- **Do:** implement, in your P02 style:
  ```kotlin
  fun tryReserve(medicationId: UUID, quantity: Int): ReservationResult? =
      jdbcClient.sql("""
        UPDATE inventory
        SET available_quantity = available_quantity - :quantity, updated_at = CURRENT_TIMESTAMP
        WHERE medication_id = :medication_id AND available_quantity >= :quantity
        RETURNING medication_id, available_quantity
      """)
      .param("medication_id", medicationId)
      .param("quantity", quantity)
      .query(ReservationResult::class.java)
      .optional().orElse(null)
  ```
  The new value is computed from the **current row value**, and the predicate is evaluated against the row version actually being updated — never a value the app saw earlier.
- **Run:** reset Amoxicillin to 1. Call `tryReserve(med, 1)` → row with `available_quantity = 0`. Call `tryReserve(med, 1)` again → `null`.
- **Observe:** the second call is an *answer*, not an accident. Do not retry it; do not pre-read "to build a better error". If the API must distinguish unknown medication from insufficient stock, classify after the failed update (exactly as P03 Step 4 did).
- **Decision:** repository returns `ReservationResult?`. Nudge: `null` means "no claim happened" — the service maps it to a domain outcome like `InsufficientStock(medicationId)`, never a retry.

### Step 4 — The last-unit race

- **Do:** reset to one unit. Reuse the P03 latched harness: two threads, start latch, both call `tryReserve(med, 1)`.
- **Run:** the test 10 times.
- **Observe:** exactly one non-null result every run, final quantity `0`. Narrate the timeline while it runs: writer A locks the row and writes `0`; writer B waits; A commits; B re-evaluates `0 >= 1` against the newest committed version — false — and matches zero rows.
- **Paste the test output and the final `select`** into your `race-evidence.md`. Label it **"last unit: one winner"**.
- **Decision:** none. This is the canonical evidence for the interview.

### Step 5 — The reservation record, same transaction

- **Do:** wrap the decrement and `INSERT INTO inventory_reservations (prescription_id, medication_id, quantity, status) VALUES (..., 'RESERVED')` in one transaction. If the insert fails, the decrement rolls back.
- **Run:** one successful `tryReserve` via a service-level `@Transactional` method; then inspect `select * from inventory_reservations;` and the inventory row.
- **Observe:** the pair — one decremented quantity, one `RESERVED` row — committed together, and a crashed mid-transaction attempt leaves neither. Read the committed state with a fresh connection, not through the repository's own return value.
- **Decision:** reservation at submission vs at approval. Nudge: the Foundation showcase reserves at submission so a prescription waiting for pharmacist approval has a clear stock decision; a check-only submission cannot promise stock later. State the choice; do not call a check a reservation.

### Step 6 — All-or-nothing multi-line

- **Do:** implement a submission that reserves two lines in one transaction, processing medication IDs in **ascending order** (`sortedBy { it.medicationId }`). When any line returns null, throw/return a domain failure so the whole transaction rolls back.
- **Run:** seed Amoxicillin `1`, Ibuprofen `5`. Reserve `(Amox 1, Ibu 5)` → success. Reset; reserve `(Amox 1, Ibu 100)` → failure; inspect.
- **Observe:** on failure, `available_quantity` for Amoxicillin is back to `1` and `inventory_reservations` has **no rows** — the first line's decrement rolled back with the second line's failure. A partial reservation is recovery work the challenge never agreed to model.
- **Paste the before/after quantities.** Note the trap: a Kotlin `return` from a `@Transactional` method *commits* — escape with the domain exception or mark rollback-only, or the first line's decrement survives.
- **Decision:** none; but say why `sortedBy` is not cosmetic (see Try this).

### Step 7 — Release exactly once

- **Do:** implement the release used by rejection: in one transaction, `UPDATE inventory_reservations SET status = 'RELEASED' ... WHERE prescription_id = :id AND medication_id = :m AND status = 'RESERVED' RETURNING quantity`, then restore `inventory.available_quantity = available_quantity + :q`.
- **Run:** release once, then run the release statement again.
- **Observe:** first run: `UPDATE 1`, stock restored. Second run: `UPDATE 0` — the `AND status = 'RESERVED'` predicate made a double release affect zero rows, so stock is restored exactly once.
- **Paste the two `UPDATE` counts.** This is "exactly-once effect, built from affected-row discipline" — not from exactly-once delivery.
- **Decision:** none; consume-at-fulfillment works the same way (`RESERVED` → `CONSUMED`, **no** second inventory decrement — the units already left `available_quantity` at reservation time).

## Try this

**Engineer a real deadlock, then delete it.** Seed two medications (M1, M2). In session A begin a two-line reservation in order `(M1, M2)`; in session B begin the same two-line reservation in order `(M2, M1)`. Run each statement one line at a time without committing.

- **Expected:** A locks M1, B locks M2, then each waits on the other's lock. After ~1 second (`deadlock_timeout`), PostgreSQL's detector aborts one with `ERROR: deadlock detected ... SQLSTATE 40P01` and the full lock chain in the log. The other completes.
- **Paste the deadlock error.** This is why `sortedBy { it.medicationId }` is a global lock-order rule, not a formatting choice: with both transactions processing in ascending order, one simply waits — no cycle, no abort.
- **Second experiment:** reset Amoxicillin to 5 and fire twenty concurrent single-unit reservations (threaded harness). Assert exactly five non-null results and final quantity `0`, **never** negative, across repeated runs. Paste one run's output.

## Trade-off fork

**Option A — Atomic conditional update (chosen by the blog's workload):** no prior read, no lock beyond the statement, the predicate inside the write, losers get a definitive zero-row answer and never retry. Ideal when the new value derives from the current row value — arithmetic on quantity is the canonical case.

**Option B — `SELECT FOR UPDATE` (pessimistic):** lock the row, inspect it and related rows, decide, write. Right when the decision genuinely needs a stable multi-row view one statement cannot express (allocation logic inspecting several inventory rows before choosing which to claim). Costs: lock held for the whole decision, hotter contention on the row, wider deadlock surface, and no broker/HTTP call inside the lock.

**Option C — Version column with retry (classic optimistic):** read with version `N`, `UPDATE ... WHERE version = N`, retry on conflict. Fits whole-entity transitions where the app computes a new state from many fields; a poor fit for a hot inventory row, where under contention most attempts lose and re-read — the most round trips exactly when the row is busiest.

Choose one and write 3–5 lines justifying it for the inventory row, naming what the others buy and cost. The honest interview answer for this invariant is often "none of the classic patterns — a single atomic statement" — but only if you can say why the alternatives are heavier, not just "this one worked."

## Hints

**Hint 1 (mild):** the whole mechanism is `available_quantity >= :quantity` inside the same statement that decrements. If you find yourself writing `select ... for update` first "to be safe", you have chosen Option B — justify it, or remove it.

**Hint 2 (stronger):** in Step 1, resist writing `SET available_quantity = available_quantity - 1` — that is Step 2's trap, not Step 1's. Step 1 must use the value read into the session (`1 - 1`) to reproduce the *application-level* lost update, which is a different failure than the constraint abort. Also: reset inventory between experiments — a stale `0` will make every subsequent claim fail and the transcripts stop meaning anything.

## Checkpoint / success criteria

You may leave when:

- [ ] `race-evidence.md` contains: the lost-update transcript (both `UPDATE 1`, final `0`), the `23514` constraint error, the last-unit race (one winner × 10 runs), the all-or-nothing rollback evidence, and the release-once `UPDATE 1`/`UPDATE 0` pair.
- [ ] `tryReserve` returns a nullable result mapped to a domain outcome; nothing in the service retries an insufficient-stock answer.
- [ ] The reservation row and decrement are visibly in one transaction (crash between them leaves nothing).
- [ ] A real `40P01` deadlock error is captured, and the fix (stable order) is demonstrated.
- [ ] You can narrate the two-transaction timeline including what the loser observes and why readers never block.

## Bottleneck & reflection questions

- One unit remains, two requests arrive together. Walk through exactly what PostgreSQL does, statement by statement — where does the loser wait, and what does it see after the winner commits?
- Why does `@Transactional` around the read-check-write not fix the lost update?
- The `CHECK (available_quantity >= 0)` is still in the schema. If the conditional update is correct, what does the constraint add — and what does SQLSTATE `23514` mean as a *business* signal?
- Which database errors do you retry, and why is insufficient stock not one of them? Where does `40P01` sit on that list, and why is a bounded retry of a deadlocked *whole command* safe?
- Why does fulfillment not decrement inventory again? And what happens to stock that stays `RESERVED` forever — what would production add, and how would it coordinate with a concurrent consume?
- Where does "this design needs exactly one application instance" quietly sneak in? (Hint: any lock you put in Kotlin.)

## Handoff

- **Next electives:** `P05_status_history_append.md` (the reservation lifecycle's history rows get the same append discipline). `../advanced/A09_postgres_under_contention.md` is the natural next stop — you now have the race harness and the deadlock transcript it needs; it adds `SKIP LOCKED` and lock-wait analysis.
- **Showcase:** `../../pharmacy-fulfillment/exercise_02_optimization.md` Milestone 3 requires exactly this evidence — "one winner for the last unit, never a negative final quantity, no partial multi-line reservation, one release, no second fulfillment decrement." Your `race-evidence.md` is Milestone 3's starting proof.
- **Rabbit handoff:** `../rabbit/R01_topology_scratchpad.md` — the reservation you made atomic is a domain fact the R-track may later publish (submission/reservation events), and the release-once discipline here is the same affected-row discipline the idempotent consumers in R05/R07 will rely on.
- **Interview line you should be able to say aloud:** "The inventory decision is inside the write: `UPDATE inventory SET available_quantity = available_quantity - :q WHERE medication_id = :id AND available_quantity >= :q RETURNING ...` — zero rows means no claim happened, the loser re-evaluates the predicate against the newest committed row, and a status predicate on the reservation makes release and consume exactly-once effects."

## Optional stretch

Fulfillment consumption, done right: implement `RESERVED` → `CONSUMED` with `WHERE status = 'RESERVED'` and prove, with two concurrent consume attempts, that exactly one succeeds and inventory is **not** decremented again. Then write three sentences on the difference between "exactly-once effect" (affected rows + status predicates) and "exactly-once delivery" (a claim no broker can make) — that sentence pair is a guaranteed follow-up in the Product Engineer interview.
