# P03 Approve-Once Race — Code-Along Elective

## Objective

Prove that exactly one of two concurrent pharmacist approvals wins, using a conditional `UPDATE` whose predicate contains the expected state, and that the loser observes zero affected rows and appends nothing. One primary objective: make "the predicate lives in the write" a demonstrated fact with real two-session and two-thread evidence — never a vibe.

## Time box

~2 hours, core. The `REPEATABLE READ` stretch adds ~20 minutes.

## Prerequisites

- `P01_schema_and_migrations.md` — you need the `prescriptions` table and a seeded fixture.
- `P02_persistence_style_kata.md` strongly recommended — this elective is where its zero-row seam becomes the outcome.
- `../glue/X01_docker_compose_trio.md` (local Postgres).
- Two `psql` sessions (or a Kotlin executor for the threaded step).
- Showcase position: before `../../pharmacy-fulfillment/exercise_02_optimization.md` (Milestone 2 is this race, productized). Do this before P04 — both are the conditional-update family and P04 reuses your harness. This elective explicitly prepares `../advanced/A09_postgres_under_contention.md`.

## Blog & curriculum links

- Primary: `posts/series-2-postgres/02-transactions-isolation.md` (the `READ COMMITTED` recheck, the approval SQL, the zero-rows classification section).
- Secondary: `posts/series-2-postgres/06-showcase-concurrent-persistence.md` (the double-approval row of the model table and its test).
- Coach-assessment gap this attacks: "PostgreSQL-specific behavior is not yet familiar … isolation, row locks, conditional updates, `RETURNING`" — the highest-risk persistence gap on the list.

## Background & motivation

The read-check-write approval — `read status; if AWAITING_APPROVAL; set APPROVED; save` — looks safe from top to bottom and is not, even inside `@Transactional`. Two requests can both read `AWAITING_APPROVAL`; both then write; the second overwrites the first without ever noticing. The fix is not more application discipline; it is moving the decision into the database statement.

PostgreSQL defaults to `READ COMMITTED`. When a conditional `UPDATE` finds its target row locked by a concurrent writer, it waits; when the writer commits, PostgreSQL **re-evaluates the `WHERE` predicate against the newest committed row version** before deciding whether to apply the change. The losing transaction therefore does not apply a stale decision — it matches zero rows. SQL Server's locking model also re-checks after a lock wait, so the *concept* transfers; what does not transfer automatically is that PostgreSQL additionally never blocks plain readers on row locks (MVCC), which is why the patient status `GET` never queues behind a pharmacist's approval.

This kata deliberately ignores inventory (P04), history/outbox append discipline (P05), deadlocks and `SKIP LOCKED` (A09), and broker retries (R-track). It isolates one idea: the state predicate is part of the write, and affected rows are the business outcome.

## Learning objectives

- Write a conditional status `UPDATE` with `RETURNING` and interpret rows = 1 vs rows = 0.
- Demonstrate, live, PostgreSQL's `READ COMMITTED` predicate re-evaluation with two real sessions.
- Classify zero rows: missing prescription vs already-transitioned, using a follow-up read *only* for error wording.
- Build a latched two-thread test against real PostgreSQL and assert exactly one winner.
- Map the outcome in Kotlin: `AppliedTransition?` → sealed domain result, never a bare `int`.
- Say aloud why the loser must not append history, outbox events, or anything else.

## Warm-up

Read the "PostgreSQL `READ COMMITTED`" and "Approval As A Conditional State Change" sections of `posts/series-2-postgres/02-transactions-isolation.md` (about 4 minutes). Then, in one `psql` session, insert an `AWAITING_APPROVAL` fixture:

```sql
INSERT INTO prescriptions (id, patient_id, status, status_version)
VALUES ('a0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000001', 'AWAITING_APPROVAL', 0);
```

and confirm `select id, status, status_version from prescriptions where id = '...'`. Open a **second** `psql` session now — you will need both.

## System specification

**Scope in:** one prescription fixture, one transition (`AWAITING_APPROVAL` → `APPROVED`), the race, outcome classification, and evidence.

**Scope out:** inventory effects (P04), status-history and outbox inserts beyond the minimal "append only on a win" check (P05), `SELECT FOR UPDATE` (weigh it in the fork, don't build it), deadlocks (A09), and any broker interaction.

**Functional requirements (minimal):**

- `approveIfAwaiting(id): AppliedTransition?` — null iff the predicate matched no row.
- The losing path appends **nothing** and returns a deliberate conflict outcome.
- Two real concurrent calls yield exactly one winner, demonstrated and captured.

**Constraints:** local Docker PostgreSQL, real concurrency only (two sessions or two threads with a latch); no mocks; no sequential calls masquerading as races; evidence pasted into a `race-evidence.md` note.

## Step-by-step code-along

### Step 1 — The manual race, two sessions

- **Do:** nothing to write yet. In session A: `BEGIN;` then run the conditional update — but **do not commit**:
  ```sql
  UPDATE prescriptions
  SET status = 'APPROVED', status_version = status_version + 1, updated_at = CURRENT_TIMESTAMP
  WHERE id = 'a0000000-0000-0000-0000-000000000001' AND status = 'AWAITING_APPROVAL'
  RETURNING id, status, status_version;
  ```
  In session B, run the same statement.
- **Run:** watch session B. It prints `UPDATE 0`? No — it **hangs**. It is waiting on A's row lock.
- **Observe:** session B's prompt sits at `UPDATE 1`-pending. Now `COMMIT;` in session A, and watch session B immediately complete with `UPDATE 0` and **no returned row**. That is the `READ COMMITTED` re-evaluation, observed live: B waited, the winner committed, B re-checked the predicate against the newest row version (`APPROVED` ≠ `AWAITING_APPROVAL`), and matched zero rows.
- **Paste both session transcripts** into `race-evidence.md`, annotated with which statement blocked.
- **Decision:** none. This is the canonical demo; keep the transcript exactly as-is.

### Step 2 — What the losers must not do

- **Do:** in one session, re-run the now-losing conditional update, and imagine the service code that follows it with `history.append(...)`. 
- **Run:** `select count(*) from prescription_status_history where prescription_id = 'a0000000-...';` (empty table — insert one row first if you want a real count).
- **Observe:** the losing statement is where the guard must sit: if the service appends history *after* an update that affected zero rows, the double-approval becomes a duplicate-history bug. The predicate decided the race; the affected-row check decides whether the side effects run.
- **Decision:** in Kotlin, will you check `rows == 1` or use the `RETURNING` row's presence? Nudge: `RETURNING` gives you the authoritative `status_version` for the winner, which P05 will use as the history sequence — prefer it.

### Step 3 — The repository method

- **Do:** implement in your P02 style:
  ```kotlin
  data class AppliedTransition(val id: UUID, val patientId: UUID, val status: String, val version: Long)

  fun approveIfAwaiting(id: UUID): AppliedTransition? =
      jdbcClient.sql("""
        UPDATE prescriptions
        SET status = 'APPROVED', status_version = status_version + 1, updated_at = CURRENT_TIMESTAMP
        WHERE id = :id AND status = 'AWAITING_APPROVAL'
        RETURNING id, patient_id, status, status_version
      """)
      .param("id", id)
      .query(AppliedTransition::class.java)
      .optional().orElse(null)
  ```
  Fill the gaps for your style (jOOQ: `fetchOptional`; JPA: decide whether a native `@Modifying` query can return the row, or whether an `int` is the honest result here).
- **Run:** seed a second fixture; call `approveIfAwaiting` twice sequentially.
- **Observe:** first call returns `AppliedTransition(version = 1)`; second returns `null`. Sequential null is not proof of race safety — Step 5 is.
- **Decision:** repository returns `AppliedTransition?`. Nudge: keep the database vocabulary out of the service — the service should never see "rows affected" as an integer.

### Step 4 — Classify zero rows

- **Do:** add a service-level outcome type — sealed is idiomatic: `ApprovalOutcome.Approved(id, version)` vs `ApprovalOutcome.Conflict` vs `ApprovalOutcome.NotFound`. On a `null` from the repository, run **one** classification read `select status from prescriptions where id = :id` to pick `NotFound` (no row) vs `Conflict` (row exists, already transitioned).
- **Run:** drive all three: unknown UUID → NotFound; already-approved fixture → Conflict; fresh fixture → Approved.
- **Observe:** the classification read is for *error wording only*. The decision was already made by the conditional update. Adding the pre-read before the update "to be safe" is the exact stale-read trap the blog warns about.
- **Decision:** none; just record the three outcomes.

### Step 5 — The latched race test (the evidence that counts)

- **Do:** write a test against the real local database — plain JUnit + two `ExecutorService` tasks and a `CountDownLatch` is enough; you do not need Testcontainers yet (P07 moves it there):
  ```kotlin
  @Test
  fun `only one approval wins the race`() {
      val id = fixture.awaitingApproval()
      val start = CountDownLatch(1)
      val results = listOf(
          executor.submit { start.await(); repository.approveIfAwaiting(id) },
          executor.submit { start.await(); repository.approveIfAwaiting(id) }
      )
      start.countDown()
      val outcomes = results.map { it.get() }.filterNotNull()
      assertEquals(1, outcomes.size)
  }
  ```
  Use **real threads** and a start latch. Run it several times.
- **Run:** `./gradlew test` (or the equivalent), then run it 10 times.
- **Observe:** exactly one non-null result, every run. If your style's method did not use the predicate, this test would fail — that is the test's entire value.
- **Paste the test output** (and a `count(*)` from history after the race, if you appended history on wins) into `race-evidence.md`.

### Step 6 — Evidence ledger

- **Do:** assemble `race-evidence.md` with: the two-session transcript (Step 1), the sequential null (Step 3), the three outcome classifications (Step 4), and the repeated latched-test output (Step 5).
- **Run:** nothing new.
- **Observe:** you can now answer "how do you know only one approval wins?" with a file, not a belief. This file is the seed of Exercise 2's proof ledger.

## Try this

**Remove the predicate and watch the race become real.** Change the update to `WHERE id = :id` (no status predicate) and re-run Step 1's two-session race:

- **Expected:** session A commits `APPROVED`; session B, after waiting, also updates the row — `UPDATE 1`, both "wins". If the service then appends history on every affected row, you get **two** approval records for one prescription.
- **Paste both transcripts and the double-approval evidence.** Then re-add the predicate and re-run — the difference between the two transcripts is the entire blog post in 60 seconds.
- **Second variant (optional):** repeat Step 5's latched test against the predicate-less version and watch it fail with two winners.

This is the experiment that converts "conditional updates are good practice" into "I have watched both versions race."

## Trade-off fork

**Option A — Conditional UPDATE (predicate in the write):** the smallest statement that expresses the invariant; no lock held beyond the statement; losers get a definitive zero-row answer and never retry.

**Option B — `SELECT ... FOR UPDATE` then update:** lock the row, inspect it and related rows, decide, write. The right tool when the decision genuinely needs a stable multi-row view one statement cannot express — for example, an approval that must also inspect reservation state before choosing.

Choose one and write 3–5 lines justifying it for this exact transition, naming what the other buys. The blog leans conditional for a single-row transition, but that is a workload argument, not a rule: if you choose the row lock, you must name its costs (lock held for the whole decision, more contention on a hot prescription, deadlock surface grows with every additional locked row, and nothing slow — no HTTP, no broker — may happen inside the lock). Either answer is defensible; "I use locks because they feel safer" is not.

## Hints

**Hint 1 (mild):** the entire mechanism is the `WHERE status = 'AWAITING_APPROVAL'` clause inside the update. If your service reads the status first and then updates "if it was awaiting", you have rebuilt the race — put the check in the SQL.

**Hint 2 (stronger):** if session B in Step 1 does not block, you are probably on two different rows — check the fixture ID, and confirm both sessions point at the same database. If the second session blocks but then errors with "could not serialize access", you are on `REPEATABLE READ` or `SERIALIZABLE` — at the default `READ COMMITTED` it re-evaluates instead (the Optional stretch explores this difference deliberately).

## Checkpoint / success criteria

You may leave when:

- [ ] `race-evidence.md` contains the two-session transcript showing the loser's `UPDATE 0` after the winner's commit.
- [ ] The predicate-less variant produced a double-approval transcript (Try this), and the predicate version produced exactly one winner.
- [ ] `approveIfAwaiting` returns `AppliedTransition?` and the service maps null → sealed `Conflict`/`NotFound` without a pre-read deciding anything.
- [ ] The latched two-thread test passed 10 consecutive runs, and the output is saved.
- [ ] You can narrate the `READ COMMITTED` re-evaluation in one breath: *wait for the lock, re-check the predicate against the newest committed row, match zero rows.*

## Bottleneck & reflection questions

- What exactly does `UPDATE 0` mean, and how does the service distinguish "never existed" from "someone else got there first" without a pre-read?
- The patient's status `GET` polls while approvals race. Why does that read never block behind the approval's row lock — and how is that different from SQL Server's default `READ COMMITTED`?
- Two approvals race, the loser gets zero rows, but the HTTP response to the *winner* is lost. What does the winner's retry observe, and why is the design still safe?
- Your repository returns `AppliedTransition?`. Where does the sealed-outcome mapping live, and why does letting an `int` leak into the service weaken the design?
- Spring's `@Transactional` is proxy-based. Where would a self-invocation silently lose the transaction boundary — and does the conditional update survive even if the transaction is lost?

## Handoff

- **Next electives:** `P04_last_unit_inventory.md` (the same conditional-update family, applied to stock — do it next while the pattern is warm). `P05_status_history_append.md` turns your "append only on a win" guard into the history/outbox discipline. `../advanced/A09_postgres_under_contention.md` is explicitly prepared by this elective: you now own a real race harness, and A09 will drive it into deadlocks and `SKIP LOCKED`.
- **Showcase:** `../../pharmacy-fulfillment/exercise_02_optimization.md` Milestone 2 requires exactly this evidence: "Two real concurrent approvals produce one winning transition and one conflict, with one history record committed." Your `race-evidence.md` is that milestone's starting proof.
- **Rabbit handoff:** `../rabbit/R01_topology_scratchpad.md` — the approval you made atomic is the event the outbox row (P01) will carry to the packaging queue. Note in your ledger that "exactly one approval" is a *database* guarantee; the message derived from it still needs the R-track's outbox discipline to be durable.
- **Interview line you should be able to say aloud:** "The approval predicate lives in the UPDATE itself — under READ COMMITTED the losing transaction waits for the winner, re-evaluates its WHERE against the newest committed row, and matches zero rows, so it appends no history and no event. Affected rows are the business outcome."

## Optional stretch

Re-run Step 1 with `SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;` in both sessions before the update. **Expected:** the loser no longer re-evaluates — it aborts with `ERROR: could not serialize access due to concurrent update` (SQLSTATE `40001`), and the application must retry. Paste that error next to the `READ COMMITTED` transcript and write two sentences: what `REPEATABLE READ` bought you, and why this workload does not need it. That comparison is a favorite interviewer follow-up to the exact demo you just ran.
