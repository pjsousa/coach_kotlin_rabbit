# P05 Status History Append — Code-Along Elective

## Objective

Build the status timeline as an append-only fact log: current status stays on `prescriptions`, history rows carry a per-prescription `sequence_number`, and history/outbox facts are written **only when the transition actually wins**. One primary objective: prove that two racing transitions cannot both append — the sequence allocation and the conditional update must be one discipline, demonstrated with evidence.

## Time box

~1.5–2 hours, core.

## Prerequisites

- `P01_schema_and_migrations.md` — `prescriptions` and `prescription_status_history` (with its lookup index) are required.
- `P03_approve_once_race.md` strongly recommended — this elective extends its "append nothing on a loss" guard into the full append discipline.
- `../glue/X01_docker_compose_trio.md` (local Postgres).
- Showcase position: before `../../pharmacy-fulfillment/exercise_02_optimization.md` (the patient timeline is part of the Foundation contract; append correctness is exercised in Milestone 2).

## Blog & curriculum links

- Primary: `posts/series-2-postgres/01-schema-design.md` (the "Status History Is An Append-Only Fact" section and the history index).
- Secondary: `posts/series-2-postgres/02-transactions-isolation.md` (history and outbox inserts committed with the state change they describe).
- Coach-assessment gap this attacks: PostgreSQL schema discipline and the future SSE ordering requirement — the per-prescription sequence is the seed of the `Last-Event-ID` replay design in the SSE posts.

## Background & motivation

The current status answers "where is the prescription now?"; history answers "what did the system say happened, and in what order?". Collapsing the two into one table forces a scan to reconstruct the timeline; duplicating current state in history makes history unreliable. The P01 schema already separates them: `prescriptions.status` is the fast authoritative projection, `prescription_status_history` is evidence.

The interesting failure is not the schema — it is the sequence number. Two concurrent approvals can both compute "the next sequence is 2", and then one append violates the composite primary key (`23505`), or worse, interleaves the stream. The allocation must happen inside the same transaction as the state change, from a value the transition itself owns. This kata deliberately ignores outbox *relay* mechanics (R-track), SSE itself (series-4 posts), and query performance on the timeline read (P06 — though the index is already in place).

There is also an honesty note the schema forces: nothing in the DDL stops a `DELETE` or `UPDATE` on history. Append-only is a discipline the application owns (plus, in production, privileges or triggers). Naming that gap is part of the interview answer — the blog says "it is safer to avoid destructive deletes and use test cleanup in a controlled database."

## Learning objectives

- Explain why current state and history are separate tables, and what each is authoritative for.
- Allocate the per-prescription `sequence_number` inside the same transaction as the state change, from a value the winning transition returns.
- Append history and outbox rows only when the conditional update won; prove the loser appends nothing.
- Read the timeline ordered by sequence number, not timestamps.
- Probe and document the append-only gap: nothing in the schema prevents `DELETE` — and that is a conscious, documented decision.
- Shape the sequence as the ordering key a future SSE replay will use.

## Warm-up

Read the "Status History Is An Append-Only Fact" section of `posts/series-2-postgres/01-schema-design.md` (about 3 minutes). Then in `psql`, confirm the table and index exist:

```sql
\d prescription_status_history
\di prescription_status_history*
```

Observe the composite PK `(prescription_id, sequence_number)` and the `(prescription_id, sequence_number DESC)` index. Those two lines are the whole ordering story: monotonic per-prescription keys, index-ready for a backward scan from the newest event.

## System specification

**Scope in:** the SUBMITTED history row at creation, the approval append (winner-only), the timeline read, the append-only probe, and the sequence-allocation race.

**Scope out:** rejection/release history variants (same pattern, P04's lifecycle), outbox *relay* (R-track), SSE streaming (series-4), and any schema change to forbid `DELETE` (document it instead).

**Functional requirements (minimal):**

- Every prescription starts with a `SUBMITTED` history row (sequence 1) committed with creation.
- An approval appends an `APPROVED` row with the next sequence **iff** the conditional update won; a lost race appends nothing.
- `SELECT ... WHERE prescription_id = :id ORDER BY sequence_number` returns the exact committed timeline.
- A concurrent-append experiment produces evidence: either a `23505` collision (naive allocation) or one clean append per winning transition.

**Constraints:** local Docker PostgreSQL; real concurrency for the race; evidence captured per experiment; no triggers, no stored procedures.

## Step-by-step code-along

### Step 1 — First append: SUBMITTED at creation

- **Do:** in your P02 repository, write `insertSubmitted(...)` so the prescription insert and the first history row (`sequence_number = 1`, `actor_type = 'PATIENT'`, `status = 'SUBMITTED'`) commit in one transaction.
- **Run:** insert one prescription; then `select prescription_id, sequence_number, status, actor_type from prescription_status_history order by sequence_number;`.
- **Observe:** exactly one history row, sequence 1. Say aloud why sequence 1 is **not** a global identity: it is scoped to the prescription, so the patient stream is ordered without trusting timestamps.
- **Decision:** history write inside the same transaction as the insert vs a separate call. Nudge: a patient's first status is as much a fact as the row itself; separate transactions make "submitted with no history" a legal state — the failure matrix P03 rehearsed.

### Step 2 — The sequence-allocation decision

- **Do:** list your options before coding:
  - **(a)** `sequence_number = status_version` from the transition's `RETURNING` — the state change owns the counter, no extra query.
  - **(b)** `INSERT ... SELECT COALESCE(MAX(sequence_number),0) + 1 FROM prescription_status_history WHERE prescription_id = :id` inside the same transaction.
  - **(c)** a separate database identity / global sequence — monotonic globally, but not scoped to a prescription, and ordering across prescriptions becomes timestamps again.
- **Run:** nothing; write your choice and one reason in the kata notes.
- **Observe:** (a) and (b) both preserve per-prescription ordering; they differ in what they make atomic. The Try-this section will show what happens when the MAX+1 query runs *outside* the winning transaction.
- **Decision:** pick (a) or (b) now. Nudge: (a) is the smallest — the `RETURNING status_version` from P03 already hands you the next sequence; (b) is more general (any fact type can allocate its own) at the cost of an extra statement that must stay inside the transaction.

### Step 3 — Append on the winning path only

- **Do:** extend your P03 `approveIfAwaiting` service method:
  ```kotlin
  val transition = prescriptions.approveIfAwaiting(id)
      ?: return ApprovalOutcome.Conflict          // nothing appended
  history.append(transition, status = "APPROVED", actorType = "PHARMACIST")  // sequence = transition.version
  outbox.insertApproved(transition)               // same transaction, P01's table
  return ApprovalOutcome.Approved(transition)
  ```
  The `?: return` before any append is the entire correctness argument: history and outbox rows exist **only** for the winner.
- **Run:** approve a fresh fixture once; inspect history (should be `SUBMITTED` + `APPROVED`) and `outbox_events` (one row, `published_at IS NULL`).
- **Observe:** the current status column, the history row, and the outbox row describe the same fact and committed together. If the outbox insert fails, the history row and the status change roll back with it — P07 will test that claim for real.
- **Decision:** none — this shape is the blog's own.

### Step 4 — The timeline read

- **Do:** implement `timeline(prescriptionId): List<HistoryRow>` with `ORDER BY sequence_number` (no `ORDER BY occurred_at` — timestamps are for humans, not for ordering).
- **Run:** approve twice on two fixtures; print both timelines.
- **Observe:** `SUBMITTED, APPROVED` in sequence order, stable regardless of wall-clock jitter between inserts. This exact query is what P06 will index-check and what the SSE replay in the R-track/series-4 will paginate.
- **Decision:** include `reason` (nullable) and `actor_type` in the timeline row or not. Nudge: the patient-safe timeline in the Foundation contract needs at least status + time; `actor_type` is staff-internal — decide what the patient-facing projection shows, and note it.

### Step 5 — Probe the append-only gap

- **Do:** attempt `DELETE FROM prescription_status_history WHERE prescription_id = :id AND sequence_number = 2;` on a two-row timeline.
- **Run:** it.
- **Observe:** it succeeds. Nothing in the DDL stopped it. Write two sentences: what production would add (restricted privileges, triggers, or simply the discipline of never shipping destructive code paths), and why the challenge documents the limitation instead of building the mechanism.
- **Decision:** none — this is a "name the gap" step, not a build step.

## Try this

**Race the naive allocation.** Use a fresh prescription fixture. In two `psql` sessions (or two threads with your P03 harness), have both run the naive append:

```sql
-- session A and B, before the transition even wins:
INSERT INTO prescription_status_history (prescription_id, sequence_number, status, reason, actor_type)
SELECT 'a0000000-0000-0000-0000-00000000000f',
       COALESCE(MAX(sequence_number), 0) + 1, 'APPROVED', NULL, 'PHARMACIST'
FROM prescription_status_history WHERE prescription_id = 'a0000000-0000-0000-0000-00000000000f';
```

- **Expected:** session A computes sequence 2 and commits; session B, unblocked, computes `MAX + 1` **again** — because under `READ COMMITTED` its statement snapshot saw the same pre-commit data, or it waited and then re-evaluated into a fresh `MAX` — and either way one of two things happens: the loser aborts with `duplicate key value violates unique constraint` (SQLSTATE `23505`), or both interleave if the snapshots happened to differ.
- **Paste the `23505` (or the interleaving) evidence.** Then switch to the winning-transition-owned sequence (P03's `RETURNING status_version`) and re-run the same race: the loser never computes a sequence at all, because it never won the transition.
- **Interpretation for the interview:** the sequence must be allocated by the transaction that owns the state change, not by a reader of the table it is appending to. `23505` is the constraint catching an allocation that escaped its transaction — the same role `23514` played in P04's inventory.

## Trade-off fork

**Option A — sequence from the winning transition (`RETURNING status_version`):** one statement, no extra read, the state change and the history counter are the same fact; costs: history sequence is *tied* to status transitions, so a non-status event (say, a support note) needs a different allocation strategy.

**Option B — `INSERT ... SELECT MAX(sequence_number) + 1` inside the same transaction:** general, orderable for any fact type, and safe *while it stays in the winning transaction*; costs: an extra statement on the hot path, and it silently breaks if someone moves the append outside the transaction — which the Try-this just demonstrated with a `23505`.

Choose one and write 3–5 lines justifying it, naming what the other lost. (Option C — global identity — is worth one line only: it abandons per-prescription monotonicity, which is the entire point of the column.)

## Hints

**Hint 1 (mild):** your P03 `AppliedTransition` already carries `version` — that *is* the next sequence number under Option A. If you chose Option B, the `INSERT ... SELECT` must appear after the conditional update won, inside the same transaction, and the losing path must never reach it.

**Hint 2 (stronger):** if the Try-this race gives you an "unexpected" second behavior — both appends succeeding with different sequences — you have discovered interleaving: the loser's snapshot was taken before the winner's commit but its insert waited on the PK, then... no, the PK is what stops it. The two outcomes are `23505` (lost the collision) or success-after-wait (timing made the second snapshot fresher). Both are the same lesson: allocation outside the winning transaction is a race, and the constraint is the crash pad. Capture whichever you got; the lesson does not depend on which.

## Checkpoint / success criteria

You may leave when:

- [ ] A prescription created through your repository always starts with one `SUBMITTED` history row (sequence 1) committed with the insert.
- [ ] Two concurrent approvals produced exactly one `APPROVED` history row and one outbox row; the loser appended nothing (evidence saved, `count(*)` included).
- [ ] The naive-allocation race evidence (`23505` or interleaving) and the fixed version's clean outcome are both in your notes.
- [ ] The timeline query returns `SUBMITTED, APPROVED` in sequence order for every fixture.
- [ ] You have written the two-sentence "why we don't forbid DELETE" note.

## Bottleneck & reflection questions

- The patient `GET /prescriptions/{id}` must return current status and a timeline. Which table answers which part, and why must the timeline never be *derived* by scanning history for current status?
- Why is the sequence scoped to a prescription instead of globally unique? What does that buy the future SSE replay — and what ordering guarantee does it *not* give across prescriptions?
- The outbox row and the history row both describe the approval. Why are they two rows, and what does each protect that the other does not?
- Your service writes history only when the transition wins. Where does a "lost response then retry" call land — and why is it safe (or not) given this discipline?
- Nothing in the schema stops `DELETE` on history. If an interviewer asks "how is append-only enforced?", what is the honest answer for a 2–5 hour challenge, and what is the production answer?

## Handoff

- **Next electives:** `P06_index_and_explain.md` (the timeline index is one of its query shapes). `P07_testcontainers_postgres.md` will let you prove "the loser appends nothing" against a real container instead of the local database. `../advanced/A09_postgres_under_contention.md` builds on the race discipline you applied here.
- **Showcase:** `../../pharmacy-fulfillment/exercise_02_optimization.md` — Milestone 2's exit criteria are literally "a losing race cannot append a second history or downstream database effect"; your Try-this evidence is that milestone's proof, and the Foundation timeline contract lives in `../../pharmacy-fulfillment/exercise_01_foundation.md`.
- **Rabbit handoff:** `../rabbit/R01_topology_scratchpad.md` — the per-prescription sequence is the ordering key the status-event stream (and later SSE `Last-Event-ID` replay) will use; the outbox row you append is what the R-track relay will publish.
- **Interview line you should be able to say aloud:** "Current status is a read-optimized projection on the prescription row; history is append-only evidence with a per-prescription sequence allocated inside the same transaction as the state change — so the losing race appends nothing, and the stream is ordered without trusting timestamps."

## Optional stretch

Write the replay query the future SSE consumer will need, without building SSE: `SELECT ... FROM prescription_status_history WHERE prescription_id = :id AND sequence_number > :last_seen ORDER BY sequence_number LIMIT :batch`. Run it with `:last_seen = 1` on a three-row timeline, then with `:last_seen = 2`. Confirm it returns exactly the missing rows, in order. Add one paragraph on why `last_seen` from a client is a resume point, not an authority — a duplicated delivery still needs the R-track's idempotency story.
