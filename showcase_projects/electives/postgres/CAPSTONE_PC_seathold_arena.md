# PC Seathold Arena — Track Capstone (Code-Along)

## Objective

Build a small general-admission concert ticketing backend from zero in a new project under `showcase_projects/electives/projects/seathold-arena/`: a promoter creates events with `N` standing seats, patrons place holds, a hold is confirmed exactly once into a ticket, expired or released holds return their seats, and history is an append-only timeline. Everything Track C taught you has to be re-enacted here, in a deliberately non-pharmacy domain: the last seat on the night of the show is the last medication unit in the pharmacy, and you must prove, not assert, that two patrons cannot both get it. One primary objective: transfer every P01–P07 skill to a new domain with real evidence — race transcripts, row counts, and `EXPLAIN` plans — never vibes.

## Time box

~6–8 hours total across four milestones (M1 ~2h, M2 ~2.5h, M3 ~1.5h, M4 ~1.5h). Each milestone has a hard exit gate; the evidence artifacts are the deliverable, not the code.

## Prerequisites

**Track electives that should be complete** (the gate rule: finish these before starting, or knowingly skip one and write a waiver in the checklist below):

| ID | Title |
|---|---|
| P01 | [Schema and migrations](P01_schema_and_migrations.md) |
| P02 | [Persistence style kata](P02_persistence_style_kata.md) |
| P03 | [Approve-once race](P03_approve_once_race.md) |
| P04 | [Last-unit inventory](P04_last_unit_inventory.md) |
| P05 | [Status history append](P05_status_history_append.md) |
| P06 | [Index and EXPLAIN](P06_index_and_explain.md) |
| P07 | [Testcontainers Postgres](P07_testcontainers_postgres.md) |

**Tools:** JDK 17+ (Spring Boot 3 requires it), Docker (the compose stack from `../glue/X01_docker_compose_trio.md` or a bare `postgres:16-alpine` container), `psql`, Gradle (any recent 8.x). No cloud, no managed database, no frontend.

**Position vs showcase:** before `../../pharmacy-fulfillment/exercise_02_optimization.md`. This capstone's evidence folder is the dry run for Exercise 2's proof ledger — Milestones 2–3 and 5–7 of the exercise demand exactly the transcripts, plans, and containerized race tests you will produce here, just against the pharmacy schema.

## Blog & curriculum links

- Primary: `../../../posts/series-2-postgres/01-schema-design.md` (schema discipline, migration rules, "status history is an append-only fact").
- `../../../posts/series-2-postgres/02-transactions-isolation.md` (conditional updates, `RETURNING`, the `READ COMMITTED` re-evaluation).
- `../../../posts/series-2-postgres/03-inventory-reservation.md` (the atomic decrement — this capstone's seat count is its inventory row).
- `../../../posts/series-2-postgres/04-indexes-query-plans.md` (query-shape-first indexing, partial indexes, plan reading).
- `../../../posts/series-2-postgres/05-testing-postgresql.md` (Testcontainers, deterministic fixtures, seed/prune discipline).
- Secondary: `../../../posts/series-2-postgres/06-showcase-concurrent-persistence.md` — the model table's "double approval" and "last unit" rows are the exact races you will re-prove as "double confirm" and "last seat" here.

## Background & motivation

You spent seven labs inside the pharmacy. The vocabulary conditioned your answers: you know `AWAITING_APPROVAL` → `APPROVED` and `available_quantity` better than you know Postgres itself. That is a good start and a real risk — interviewers hear "I learned conditional updates on a prescription table" and file it as a homework story. A second domain, built from zero, converts a lab list into a portable skill: *"I build inventory-like invariants, and I've done it in two domains."*

Concert holds are inventory management with a countdown. Every seat is a unit; a hold is a reservation with a TTL; a confirm is a status transition that must win exactly once; the box office's "list active holds" screen is a queue read that deserves an index and an `EXPLAIN`. The analogies you will lean on in the interview are direct:

- **Last seat ≈ last medication unit.** The moment two patrons race for the final seat is the moment two prescriptions race for the final box of medication. If your design says "well, `@Transactional` handles it," the lost update says otherwise — you will watch it happen, in `psql`, in the first twenty minutes of M2.
- **Oversell ≈ broken stock promise.** A patron turned away at the door is a patient told "we reserved it, but it's gone." Both are promises the persistence layer made, and both are made before any HTTP response is written.
- **Hold expiry ≈ reservation expiry.** Seats that never confirm must come back to the pool, exactly once, or the show sells half-empty while the system says full.

The whole capstone is one discipline from P03/P04 restated: the decision lives inside the write, losers observe zero rows and append nothing, and every claim is backed by a transcript, a `count(*)`, or a plan.

## Skill checklist (mandatory)

Every row from the Track C lab matrix must be forced by a concrete capstone behavior. Mark each one **pass** or **skip + waiver** (one sentence on why) in your README before moving to the next milestone.

| ID | Skill the capstone must force (verbatim from the matrix) | Concrete capstone behavior | Status |
|---|---|---|---|
| P01 | events/holds/tickets/hold_history schema as versioned migrations with deterministic seed (event with N seats); CHECKs/FKs express invariants; reproduce from an empty database in one clean pass | M1: `V1`–`V5` migrations for the four tables plus a seed with a 400-seat event and a 1-seat event (the last-seat fixture); clean-room rebuild transcript | pass / skip + waiver |
| P02 | Pick-one persistence style (JDBC or jOOQ or JPA) at M1 and bind for the entire capstone; read path rows→data classes with correct nullability; write path with INSERT ... RETURNING; state how the style represents "no row" for a conditional update; written kata-notes paragraph | M1: fork decided before any service code; `notes/kata-notes.md` paragraph on the style and its "no row" representation; style switch forbidden afterward | pass / skip + waiver |
| P03 | Confirm-once: conditional UPDATE whose predicate contains expected state (hold = ACTIVE); loser observes zero affected rows and appends nothing; two-session transcript + latched two-thread evidence (10 runs) | M2: `confirmHold` with `status = 'ACTIVE' AND expires_at > now()` predicate; two-session blocking transcript; latched double-confirm ×10 in `notes/race-evidence.md` | pass / skip + waiver |
| P04 | Last-seat race: atomic conditional decrement on available_seats; two threads claiming the final seat → exactly one winner; available_seats never negative; lost-update transcript + rollback evidence | M2: `placeHold` atomic decrement on `events.available_seats`; lost-update transcript; latched last-seat race ×10; never-negative assertion | pass / skip + waiver |
| P05 | Hold/ticket history append: current state on holds, per-hold sequence_number on history rows, history written only when the transition actually wins; racing transitions cannot double-append (23505 evidence in notes) | M3: `HOLD_PLACED` sequence 1 at creation; `CONFIRMED`/`EXPIRED` appended only on a win with sequence from `RETURNING status_version`; naive `MAX+1` race → `23505` evidence | pass / skip + waiver |
| P06 | "List active holds by event" query shape written down; EXPLAIN (ANALYZE, BUFFERS) before/after the index in notes/explains/; each index justified with query shape, benefit, write cost; at least one tempting index rejected with reason | M3: query shape in `notes/queries.md`; baseline and post-index plans saved as files in `notes/explains/`; rejected-index note | pass / skip + waiver |
| P07 | Testcontainers integration tests proving confirm-once and last-seat on real PostgreSQL: migrations on startup, latched race ×10 with committed-state count(*) assertions, sequential-vs-latched contrast documented | M4: container with compose-parity image; both races as latched ITs ×10 with fresh-connection `count(*)`; sequential anti-pattern test kept as contrast | pass / skip + waiver |

## Learning objectives

- Stand up a new Gradle + Spring Boot + Flyway + Testcontainers project without a solution repo, reproducing the whole P01 discipline in a fresh domain.
- Re-decide the persistence-style fork against a *new* schema and bind to it for the rest of the capstone.
- Re-prove the conditional-update family (confirm-once, last-seat, release-once) with two-session and latched-thread evidence, from scratch.
- Apply the append-only discipline with per-hold sequences and demonstrate the naive-allocation failure (`23505`).
- Write query shapes first, capture `EXPLAIN (ANALYZE, BUFFERS)` before/after an index, and justify or reject every index on this schema.
- Prove both races against a Testcontainers PostgreSQL and consolidate the evidence folder an interviewer can read in one pass.

## Warm-up

(2–5 minutes.) Re-read the "Name The Reads Before The Indexes" section of `../../../posts/series-2-postgres/04-indexes-query-plans.md` and the model-table rows for double-approval and last-unit in `../../../posts/series-2-postgres/06-showcase-concurrent-persistence.md`. Then one probe: create the capstone database and confirm your baseline the way P01 Step 1 taught you.

```bash
createdb -h localhost -U postgres seathold
psql -h localhost -U postgres -d seathold -c 'select current_setting(''server_version''), current_setting(''transaction_isolation'');'
```

Observe: PostgreSQL 16.x, `READ COMMITTED` — the same defaults the whole track built on, now in a new database that owns nothing from the pharmacy. Record the exact image version; M4 will enforce parity with it.

## Project bootstrap

**Directory (exact):** `showcase_projects/electives/projects/seathold-arena/` — candidate-owned code; optionally add `showcase_projects/electives/projects/` to `.gitignore`.

- **Do:** from that directory, run `gradle init --type kotlin-application --dsl kotlin --test-framework junit-jupiter` (or hand-write `settings.gradle.kts` + `build.gradle.kts` if you prefer). Add the dependencies you know you need and nothing more: the PostgreSQL driver, Flyway, your chosen persistence style's starter, and (for M4) Testcontainers JUnit Jupiter + `postgresql` modules. Configure `application.properties` (or a small `DataSource` bean) for a `seathold` database on `localhost:5432`.
- **Run:** `./gradlew run` with a trivial `main()` that connects and prints `SELECT version();`.
- **Observe:** connect, print `PostgreSQL 16.x`, exit cleanly. This is your only infrastructure step until M4's container.
- **Do:** create a README skeleton with these sections and fill them as you go: `What this is`, `How to run`, `Evidence folder` (the file tree from M4), `Decision log` (every fork from this capstone, with 3–5 lines each), `What I would change before production` (hold expiry scheduler, payment, seat inventory at venue scale — you will name these honestly in M2 and M4).

## System specification

**Product fantasy.** A small venue — *Seathold Arena* — sells general-admission standing shows. A promoter creates an event with a name, venue, door time, and a fixed number of seats. Patrons (via the box office terminal, kiosk, or a script — your call, no frontend) place a **hold**: "reserve my spot for the next 15 minutes." While the hold is `ACTIVE`, its seats are off the market. Confirming the hold before it expires issues a ticket. Holds that are never confirmed **expire** (or are released by staff) and their seats return to the pool. The box office stares at a **"list active holds"** screen all night; the promoter trusts the audit history.

**Actors:** promoter (creates events), patron (places holds, confirms them into tickets), box office (views active holds, releases stuck ones).

**Scope in:** `events`, `holds`, `tickets`, `hold_history`; versioned forward-only migrations with a deterministic seed; `placeHold`, `confirmHold`, `releaseHold` (and `expireHold` invoked the same way); append-only history; the "list active holds by event" read with before/after `EXPLAIN`; Testcontainers ITs proving confirm-once and last-seat on real PostgreSQL.

**Scope out:** payments, patron identity/accounts (a `patron_id` column is enough), seat numbers (general admission is a single pool), refunds beyond release, any frontend or API framework ceremony (a `main()` harness and JUnit are the UI), a *scheduled* expiry sweeper — expiry is an invoked transition here; naming that gap is part of the interview answer, exactly as P04 named reservation expiry.

**Functional requirements (minimal):**

- `createEvent(name, venue, doorTime, seats)` — `total_seats = seats`, `available_seats = seats`.
- `placeHold(eventId, patronId, quantity): HoldResult?` — null iff the claim did not happen (unknown event or not enough seats). The decrement and the hold insert commit together.
- `confirmHold(holdId): ConfirmResult?` — null iff the hold is not currently confirmable (`status = 'ACTIVE'` **and** unexpired). A winning confirm inserts exactly `quantity` tickets, in the same transaction.
- `releaseHold(holdId)` / `expireHold(holdId)` — status predicate `status = 'ACTIVE'`; seats restored exactly once; a second attempt affects zero rows.
- Every transition appends one `hold_history` row with the next per-hold `sequence_number`, winner-only.
- `listActiveHolds(eventId)` — the box office screen.
- `available_seats` never negative in any run, and never above `total_seats`.

**Non-functional / evidence requirements:** every race claim ships with evidence — two-session transcripts, latched ×10 test output, `count(*)` from committed state, and `EXPLAIN (ANALYZE, BUFFERS)` plans as files. No evidence, no "done". Sequential calls are never presented as races.

**Constraints:** one Gradle module; one persistence style, chosen at M1 and not switched; local Docker PostgreSQL 16 only (compose or bare container); Flyway (or your P01-chosen runner) as the only schema mechanism; no `CREATE TABLE IF NOT EXISTS` anywhere in app code; tests against Testcontainers, not the local database; Kotlin `val` + immutable data classes at the persistence boundary.

## Milestones (code-along)

### M1 — Bootstrap, schema, seed, and the persistence bind (P01, P02) — ~2h

**Step 1 — The migration set.**

- **Do:** write `db/migration/V1__events.sql` through `V4__hold_history.sql`, plus `V5__seed.sql`. The shapes, with the load-bearing constraints:

```sql
CREATE TABLE events (
    id uuid PRIMARY KEY,
    name text NOT NULL,
    venue text NOT NULL,
    door_time timestamptz NOT NULL,
    total_seats integer NOT NULL CHECK (total_seats > 0),
    available_seats integer NOT NULL CHECK (available_seats >= 0 AND available_seats <= total_seats),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- holds: id, event_id FK, patron_id, quantity CHECK (quantity > 0),
--        status CHECK (status IN ('ACTIVE','CONFIRMED','EXPIRED','RELEASED')),
--        status_version bigint NOT NULL DEFAULT 0, expires_at timestamptz NOT NULL,
--        confirmed_at timestamptz, created_at/updated_at timestamptz

-- tickets: id uuid PK, hold_id uuid NOT NULL UNIQUE REFERENCES holds(id),
--          event_id uuid NOT NULL REFERENCES events(id), patron_id uuid NOT NULL,
--          issued_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP

-- hold_history: PRIMARY KEY (hold_id, sequence_number), CHECK (sequence_number > 0),
--               status, actor_type, reason (nullable), occurred_at timestamptz
```

- **Run:** migrate, then `\d holds` and `\d tickets`.
- **Observe:** the CHECKs, FKs, and composite PKs are all visible in `\d`. Note which invariants are database-owned and which are app-owned — `tickets.hold_id UNIQUE` is the safety net under confirm-once (P03's `23505` crash-pad role), and `available_seats <= total_seats` catches an over-restore on a buggy release the same way P04's `>= 0` CHECK caught the unconditional decrement.
- **Decision (mini fork):** client-generated `uuid` vs `GENERATED ALWAYS AS IDENTITY`. Nudge: P01 chose client-generated so the API knows the ID before the first insert — the same argument holds here (a patron's retry after a lost response reuses the hold ID); identity is defensible if you name what you give up. Two lines in the README decision log.

**Step 2 — The seed.**

- **Do:** `V5__seed.sql` with fixed, readable UUIDs: `e0000000-0000-0000-0000-000000000001` *"Bolt Thrower at the Harbor Pavilion"* — 400 seats; `e0000000-0000-0000-0000-000000000002` *"The Straddle Sessions: Finale"* — **1 seat**, your last-seat fixture. A one-seat show is absurd on purpose: the race is trivially reproducible and the fixture is self-documenting.
- **Run:** drop, recreate, migrate, `select name, total_seats, available_seats from events;` — save the clean-room rebuild transcript.
- **Observe:** from an empty database, one pass, deterministic state. That transcript is the P01 demo in a new domain.

**Step 3 — The persistence bind (the fork you cannot take back).**

- **Do:** choose **JDBC (`JdbcClient`) / jOOQ / JPA** and write the P02 decision paragraph into `notes/kata-notes.md`: *why this style for this schema, what it hides from me, and exactly how it represents "no row" for a conditional update.* This paragraph is M1's exit gate.
- **Run:** one read and one write in your style: `findEvent(id): EventRow?` and `createEvent(...)` ending in `RETURNING id, total_seats, available_seats`.
- **Observe:** the read maps `timestamptz` honestly (consistent `Instant`/`OffsetDateTime`), the write returns its own facts without a second `SELECT`, and a bogus event id is a nullable, not an exception.
- **Forbidden:** switching styles after this milestone. The whole capstone runs on this choice; switching is a skip-with-waiver on P02.

**M1 exit gate:** clean-room rebuild transcript saved; `notes/kata-notes.md` paragraph written; README skeleton filled with the project's "how to run" and the decision log entry for identity + style. **Easy-to-skip risk:** the kata-notes paragraph — M2's transcripts are unreadable without the "no row means no claim" sentence your style documents. Force it before moving on.

### M2 — Place-hold, confirm-once, and the last-seat race (P03, P04) — ~2.5h

**Step 1 — Watch the lost update happen (before writing any code).**

- **Do:** in `psql`, reset the 1-seat event to `available_seats = 1`. Open a second session. Session A: `select available_seats ...;` → `1`. Session B: same → `1`.
- **Run:** both sessions `UPDATE events SET available_seats = 1 - 1 WHERE id = 'e0000000-...002';` — using the value each **read**, exactly like P04 Step 1 — then commit both.
- **Observe:** both report `UPDATE 1`. Final `available_seats` is `0`, and two holds were logically claimable against one seat. That is the read-check-write lost update; paste both transcripts labeled **"read-check-write: both win"** into `notes/race-evidence.md`.
- **Decision:** none. You just built the bug M2 removes.

**Step 2 — The constraint as crash pad, not mechanism.**

- **Do:** reset to 1; both sessions run the *unconditional* decrement `SET available_seats = available_seats - 1`.
- **Run:** session A first, then B (waits), then commit A.
- **Observe:** B aborts with SQLSTATE `23514` — the invariant survived but the loser got a database exception instead of a business outcome. Paste the error labeled **"unconditional decrement: constraint as crash"**. Say the P04 sentence aloud: the CHECK is a safety net, never the mechanism.

**Step 3 — `placeHold`: the atomic decrement.**

- **Do:** implement in your bound style, in one transaction:

```kotlin
fun placeHold(eventId: UUID, patronId: UUID, quantity: Int): HoldPlaced? =
    jdbcClient.sql("""
        UPDATE events
        SET available_seats = available_seats - :quantity, updated_at = CURRENT_TIMESTAMP
        WHERE id = :event_id AND available_seats >= :quantity
        RETURNING id, available_seats
    """)
    // ...param("quantity", quantity).optional().orElse(null)
    // if (row == null) return null        // no claim happened: not a retry, an answer
    // else: INSERT INTO holds (..., status = 'ACTIVE', expires_at = now() + interval '15 minutes')
    //       in the same transaction
```

- **Run:** `placeHold` for 2 seats on the 400-seat event → success with `available_seats = 398`; `placeHold` for 500 → null; classify `SoldOut` vs `NotFound` with one follow-up read (P03 Step 4 discipline).
- **Observe:** the predicate and the arithmetic live inside the same statement; the new value comes from the row version actually being updated, never a value the app saw earlier. Reset the 1-seat event; `placeHold(..., 1)` succeeds once, then returns null — an answer, not an accident.

**Step 4 — The last-seat race (latched threads, ×10).**

- **Do:** reuse your P03 harness shape — two `ExecutorService` tasks, one `CountDownLatch`, both calling `placeHold` on the 1-seat event.
- **Run:** 10 times; also capture `select available_seats from events where id = 'e...002';` after each run.
- **Observe:** exactly one non-null result every run, final quantity `0`, never negative. Narrate the timeline while it runs: winner locks the row and writes `0`; loser waits; winner commits; loser re-evaluates `0 >= 1` against the newest committed row and matches zero rows. Paste the output labeled **"last seat: one winner"** into `notes/race-evidence.md`.

**Step 5 — `confirmHold`: the confirm-once transition.**

- **Do:** implement, with the state predicate *inside* the write:

```kotlin
fun confirmHold(holdId: UUID): Confirmed? =
    // UPDATE holds
    // SET status = 'CONFIRMED', status_version = status_version + 1,
    //     confirmed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
    // WHERE id = :hold_id AND status = 'ACTIVE' AND expires_at > CURRENT_TIMESTAMP
    // RETURNING id, event_id, patron_id, quantity, status_version
    // if row is null -> null (loser path: append NOTHING)
    // else -> INSERT INTO tickets (..., hold_id, event_id, patron_id) x quantity
    //         in the same transaction
```

- **Run:** the two-session demo from P03 Step 1: session A begins the confirm and does not commit; session B runs the same statement and **blocks**; commit A; B completes with `UPDATE 0` and no returned row. Paste both transcripts with an annotation on where B blocked.
- **Observe:** the `READ COMMITTED` re-evaluation, live, in the new domain. Then run the sequential check: confirm once → `Confirmed`; confirm again → null. Sequential null is not proof — Step 6 is.
- **Decision:** the expiry predicate `expires_at > CURRENT_TIMESTAMP` is part of the expected state — say why a confirm of an expired hold must be a zero-row outcome, not a second read.

**Step 6 — Release and expire exactly once.**

- **Do:** `releaseHold(holdId)`: conditional `UPDATE holds ... SET status = 'RELEASED' ... WHERE id = :id AND status = 'ACTIVE' RETURNING quantity, event_id`; on a win only, restore `events.available_seats = available_seats + :quantity` in the same transaction. `expireHold` is the identical shape with `'EXPIRED'`.
- **Run:** release once (`UPDATE 1`, seats restored), then run the release again (`UPDATE 0`, no double-restore). Also try to release a `CONFIRMED` hold — zero rows again.
- **Observe:** exactly-once seat restoration, built from affected-row discipline. Paste the `UPDATE 1`/`UPDATE 0` pair. Note honestly in the README: nothing here *schedules* expiry — a real venue needs a sweeper (that is the "before production" note, and `../advanced/A09_postgres_under_contention.md`'s `SKIP LOCKED` is how it would avoid double-expiry).

**M2 exit gate:** `notes/race-evidence.md` holds the lost-update transcript, the `23514` error, the last-seat ×10 output, the blocking two-session confirm transcript, and the release `UPDATE 1`/`UPDATE 0` pair. **Easy-to-skip risk:** running the races without a latch (sequential calls "race") or skipping the two-session blocking demo — both make P03/P04 unfalsifiable. Force both.

### M3 — History append discipline and the box-office index (P05, P06) — ~1.5h

**Step 1 — History at creation and on every winning transition.**

- **Do:** `placeHold` inserts the `HOLD_PLACED` history row (`sequence_number = 1`, `actor_type = 'PATRON'`) in the same transaction as the hold. `confirmHold` appends `CONFIRMED` with `sequence_number = status_version` from the `RETURNING`; `releaseHold`/`expireHold` append `RELEASED`/`EXPIRED` the same way. The losing path never reaches an append — P05's `?: return` before any write.
- **Run:** place → confirm → expire a second hold; `select hold_id, sequence_number, status from hold_history order by hold_id, sequence_number;`.
- **Observe:** the timeline reads `HOLD_PLACED, CONFIRMED` in sequence order, never timestamp order. Sequence is per-hold, not global — it is the ordering key a future audit stream would replay.

**Step 2 — Race the naive allocation (the `23505` evidence).**

- **Do:** with a fresh hold, run P05's Try-this in two `psql` sessions: an append using `COALESCE(MAX(sequence_number), 0) + 1` computed *outside* any winning transition, both sessions racing.
- **Run:** it.
- **Observe:** `duplicate key value violates unique constraint "hold_history_pkey"` — SQLSTATE `23505` — or an interleaved stream. Paste whichever you got, then re-run the race with the transition-owned sequence: the loser never computes a sequence because it never won the transition. Write the P05 sentence in `notes/race-evidence.md`: sequence allocation must belong to the transaction that owns the state change.

**Step 3 — Write the query shapes down first.**

- **Do:** create `notes/queries.md` listing the reads this system actually runs, with predicate, ordering, and frequency:
  1. `WHERE id = :id` — event by id (PK, nothing to do).
  2. `WHERE event_id = :event_id AND status = 'ACTIVE' ORDER BY created_at, id` — **list active holds** (the box office screen; M3's target).
  3. `WHERE hold_id = :id ORDER BY sequence_number` — hold timeline (history index shipped in V4).
  4. `WHERE hold_id = :id` — one ticket by hold (unique, covered by `tickets.hold_id`).
- **Run:** nothing — the list precedes the DDL.
- **Observe:** shapes 1, 3, and 4 are already served. Shape 2 is the only decision this milestone makes.

**Step 4 — Baseline plan, then `ANALYZE`, then the index.**

- **Do:** make shape 2 slow and honest first — seed a few thousand holds across events with `generate_series` (raw disposable SQL, not repository code), a handful of `ACTIVE` ones on your box-office event. Then capture the baseline:

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, patron_id, quantity, status_version, expires_at
FROM holds
WHERE event_id = 'e0000000-0000-0000-0000-000000000001' AND status = 'ACTIVE'
ORDER BY created_at, id;
```

- **Run:** twice (judge the second); save it as `notes/explains/holds-active-before.txt`.
- **Observe:** `Seq Scan` + `Filter: (status = 'ACTIVE'::text)` + `Sort`, and — the signature symptom — estimated `rows` badly off from actual. Then `ANALYZE holds;` and re-capture: estimates move toward reality. Then add the index as a forward-only migration (`V6__holds_active_by_event_idx.sql`):

```sql
CREATE INDEX holds_active_by_event_idx ON holds (event_id, created_at, id) WHERE status = 'ACTIVE';
```

- **Run:** re-capture; save as `notes/explains/holds-active-after.txt`.
- **Observe:** one `Index Scan`, no filter node, no sort, estimates matching reality. Write the justification in `notes/queries.md`: query shape (equality on `event_id`, order by `created_at, id`), benefit (box-office screen is queue-sized, not table-sized), write cost (every confirm/expire/release removes the row's new version from this partial index — a good trade when the screen reads vastly outnumber transitions). Then the rejected index: `(patron_id, status)` — tempting as "list my holds," but no read shape demands it yet; write the one-line rejection.

**M3 exit gate:** `notes/explains/holds-active-before.txt` and `notes/explains/holds-active-after.txt` both exist with annotations; the `23505` (or interleaving) evidence and the fixed version's clean outcome are in `notes/race-evidence.md`. **Easy-to-skip risk:** the before/after EXPLAIN files — without them P06 is unfalsifiable and M3 is not done. Also: do not back-port the index into V2; the whole milestone is the discipline of adding it *after* the shape and the baseline exist.

### M4 — Testcontainers proof and the consolidated evidence folder (P07) — ~1.5h

**Step 1 — The container.**

- **Do:** add Testcontainers (`org.testcontainers:postgresql`, `junit-jupiter`, and the Testcontainers JUnit Jupiter extension) and wire a `PostgreSQLContainer("postgres:16-alpine")` as a shared `@Container` for the IT suite. Run the Flyway migrations programmatically against the container before tests. Write the version-parity sentence in the test file header: *tests run the same image as the local stack.*
- **Run:** a smoke test that connects and prints `version()` and lists the four tables.
- **Observe:** container up, migrations applied, seed present — a fresh database every run.

**Step 2 — The sequential anti-pattern, written down and kept.**

- **Do:** deliberately write the wrong test first: confirm the same hold twice *sequentially* and assert one ticket.
- **Run:** it — and watch it pass.
- **Observe:** it passes even if `confirmHold` had no predicate, because the second call sees the committed first. Keep it, clearly named, as the contrast row in `notes/testcontainers-evidence.md`.

**Step 3 — The latched race tests with committed-state assertions.**

- **Do:** write the real tests — P07's shapes, with fixtures seeded by fixed-UUID helpers (`oneSeatEvent()`, `activeHold(eventId, patronId)`), a start latch, and assertions through a **fresh** `JdbcTemplate` connection, not the repository's return values:

```kotlin
@Test fun `only one confirm wins`() {
    val holdId = activeHold(oneSeatEvent(), "p0000000-0000-0000-0000-000000000001")
    // two latched tasks call repo.confirmHold(holdId); start.countDown()
    // assert exactly one non-null result
    // then via a fresh connection:
    //   SELECT count(*) FROM tickets WHERE hold_id = ?  -> 1
    //   SELECT count(*) FROM hold_history WHERE hold_id = ? -> 2  (HOLD_PLACED + CONFIRMED)
}

@Test fun `only one claim of the final seat wins`() {
    // oneSeatEvent(), two latched placeHold(quantity = 1)
    // assert one winner; fresh read: SELECT available_seats ... = 0, never negative
}
```

- **Run:** each 10 times; also run the suite twice back-to-back.
- **Observe:** exactly one winner every run; the history counts prove the loser appended nothing; determinism across two back-to-back runs proves the prune strategy holds.
- **Decision:** prune strategy — the race tests genuinely commit, so choose `TRUNCATE ... RESTART IDENTITY CASCADE` between classes (or your chosen P07 strategy), and write the one-line reason in the file header.

**Step 4 — Consolidate the evidence folder.**

- **Do:** assemble the final tree under `seathold-arena/`:

```
notes/
├── kata-notes.md                  # M1: style paragraph + "no row" representation
├── race-evidence.md               # M2/M3: transcripts, ×10 outputs, 23505/23514
├── queries.md                     # M3: shapes + index justification + rejected index
├── explains/
│   ├── holds-active-before.txt    # M3: Seq Scan + Filter + Sort
│   └── holds-active-after.txt     # M3: Index Scan, no filter/sort
└── testcontainers-evidence.md     # M4: parity sentence, sequential-vs-latched, ×10 runs
```

- **Run:** `./gradlew test` twice back-to-back; update the README's `Evidence folder` section to match the real tree.
- **Observe:** an interviewer can now answer every Track C question from files, not stories. **Easy-to-skip risk:** the containerized last-seat test — P07's whole point is that the last-seat claim is proven on real PostgreSQL, in a container, with committed-state assertions. Skipping it is a skipped P07.

## Try this

1. **Two threads, one final seat.** Reset the 1-seat event, fire twenty latched single-seat `placeHold` calls (one hold each), and assert exactly one winner, `available_seats = 0`, never negative, across 10 runs. Paste one run's output. This is P04's twenty-concurrent-reservations experiment in the new domain.
2. **Double-confirm the same hold.** Two latched threads call `confirmHold` on the same `ACTIVE` hold. Expected: exactly one ticket row and exactly one `CONFIRMED` history row; the loser appended nothing. Assert with `count(*)` through a fresh connection — and then deliberately remove the status predicate from `confirmHold` and watch the latched test fail (two tickets), while the sequential test still passes. Paste the failing pair; that contrast is the entire P07 lesson.
3. **Sequential scan before, index scan after.** Re-run the box-office `EXPLAIN (ANALYZE, BUFFERS)` with the index dropped on a scratch copy of the database (never on the migrated one). Compare `Seq Scan` + `Filter` + `Sort` against the indexed plan, and annotate the node that disappeared. Paste both side by side.

## Trade-off forks

**Fork 1 — Seat claim: `FOR UPDATE` vs the atomic conditional UPDATE.** Lock the event row (`SELECT ... FOR UPDATE`), read `available_seats`, decide in the app, write — versus the one-statement `UPDATE ... WHERE available_seats >= :q RETURNING ...` that lets the database arbitrate. The conditional update wins this workload because the new value derives from the current row value and no multi-row decision is involved; the lock buys a stable view that a hold that must also inspect *other* events (the stretch) would genuinely need, at the cost of holding a hot row's lock across the whole decision. Pick one, name what the other buys, and write 3–5 lines in the decision log. Note the variant P04 raised: if you reach for `FOR UPDATE` "to be safe," you have picked it — justify it or delete it.

**Fork 2 — Isolation level: `READ COMMITTED` vs `REPEATABLE READ`.** The track's entire evidence corpus is built on the default: losers wait, re-evaluate the predicate against the newest committed row, and match zero rows. `REPEATABLE READ` would abort the loser with `40001` instead, moving the retry decision into the application — strictly more machinery for a workload whose losing path is a clean business outcome. State the choice and the one sentence that justifies it (the 40001 contrast from P03's stretch is your evidence if you want to capture it). The interview follow-up you are prepping for is exactly "and when *would* you pick `REPEATABLE READ`?" — name the multi-read invariants that would push you there.

**Fork 3 — Persistence style: JDBC vs jOOQ vs JPA (bound at M1, non-negotiable afterward).** The full argument is in P02 and your `notes/kata-notes.md`. The capstone-specific twist: this domain's correctness lives in conditional updates, `RETURNING`, and affected rows *twice as often* as the pharmacy did (every seat claim, every confirm, every release is one). Whatever you chose, the fork is already decided — this entry is the record that you re-decided it against a fresh schema and bound it for the entire project.

## Hints

**Hint 1 (mild):** every race in this capstone is the same sentence from P03/P04: the predicate lives in the write. If any step finds you reading a row "to decide" before writing, you have rebuilt the read-check-write bug — stop and move the condition into the `WHERE`.

**Hint 2 (stronger):** if the two-session confirm demo does not block, check both sessions are on the `seathold` database and the same hold UUID — a typo silently makes the second session a different row and the demo meaningless. If a session errors with `could not serialize access`, you are on `REPEATABLE READ`; reset to the default before judging any transcript.

**Hint 3 (stronger):** for the last-seat ×10 runs, reset `available_seats` to 1 between runs (a stale `0` makes every subsequent claim fail and the evidence stops meaning anything — the exact P04 trap). For the history assertions, remember `RETURNING status_version` *is* the next sequence number; do not invent a second counter.

**Hint 4 (strongest, only if stuck):** if `placeHold` returns null on the very first call, your fixture is wrong, not your SQL — confirm `select available_seats from events where id = 'e0000000-...002';` shows `1` before blaming the statement. And if the M4 container will not start, Docker is the usual suspect; the parity sentence in the test header exists because a silent image gap is a real gap.

## Checkpoint / success criteria

You may leave when:

- [ ] A dropped-and-recreated `seathold` database reaches all tables plus the two-event seed via migrations alone (transcript saved).
- [ ] `notes/kata-notes.md` names the bound persistence style and its "no row" representation; no style switch anywhere in the project.
- [ ] `notes/race-evidence.md` contains: the lost-update transcript, the `23514` error, the last-seat ×10 output, the blocking confirm transcript, the release `UPDATE 1`/`UPDATE 0` pair, and the `23505` naive-allocation evidence.
- [ ] `notes/queries.md` lists the query shapes with index justification and one rejected index with reason.
- [ ] `notes/explains/holds-active-before.txt` and `notes/explains/holds-active-after.txt` exist and are annotated.
- [ ] The Testcontainers suite proves confirm-once and last-seat ×10 each with committed-state `count(*)` assertions, plus the sequential-vs-latched contrast; suite passes twice back-to-back.
- [ ] The Skill checklist above is marked pass or skip + waiver for all seven rows, and the README decision log covers every fork.

## Bottleneck & reflection questions

- Two patrons race for the last seat at the same instant the box office refreshes its screen. Walk through the timeline, statement by statement — where does the loser wait, what does it observe after the winner commits, and why does the box office's read never block? (This is the patient-polling-status question, re-asked by a venue.)
- A patron's confirm response is lost and they retry. What does the retry observe, and why is the design still safe — what does `tickets.hold_id UNIQUE` protect that the conditional update does not?
- `available_seats` never goes negative and never exceeds `total_seats`. Which of those two CHECKs is a business rule, which is a crash pad, and what happens to the system if you delete the crash pad? (Last seat ≈ last medication unit — which constraint would you defend to a skeptical venue owner?)
- The box office screen reads vastly more than holds transition. What does the partial index cost on the confirm/release hot path, and why is the trade still good? Where would the trade flip?
- Nothing schedules hold expiry in this capstone. What would production add, and how would a concurrent sweeper and a confirm racing on the same hold resolve — which statement wins, and what does the loser observe?
- Which of this system's claims does an in-memory fake *honestly* support, and which require real PostgreSQL? Name one of each from this capstone, and say which artifact proves the boundary.

## Handoff

- **Next showcase exercise:** `../../pharmacy-fulfillment/exercise_02_optimization.md` — Milestones 2–3 (conditional transitions, atomic reservation) and 5–7 (indexes, plans, containerized race tests) are this capstone re-run against the pharmacy schema. Your evidence folder is the dry-run ledger those milestones demand; port the *discipline*, not the SQL.
- **Next electives:** `../advanced/A09_postgres_under_contention.md` — your latched harness and release-once evidence are exactly what it needs to drive deadlocks and `SKIP LOCKED` (a real expiry sweeper uses it). `../rabbit/R01_topology_scratchpad.md` — the per-hold sequence and the append-only history are the ordering keys a future audit/event stream replays.
- **Interview line you should be able to say aloud:** "I prove inventory-like invariants with real database races and plans, not in-memory hope — the last seat claims itself through a conditional decrement with the predicate in the write, confirms win once or not at all, releases restore seats exactly once, and the box office queue is verified with `EXPLAIN ANALYZE` before and after its partial index, with every claim backed by containerized tests against real PostgreSQL."

## Optional stretch

- **Multi-event holds and lock order:** a patron places one hold covering two events inside a single transaction. Process event ids in ascending order, then race two such holds in opposite orders and capture the `40P01` deadlock — the P04 `sortedBy` lesson, re-earned in the new domain.
- **A real expiry sweeper:** `FOR UPDATE SKIP LOCKED` over `holds WHERE status = 'ACTIVE' AND expires_at < now()`, invoking your `expireHold` path, with evidence that two concurrent sweepers never double-expire.
- **Keyset pagination** for the box office screen (`(created_at, id) > (:c, :i)`) with the offset-vs-keyset plan comparison from P06's Try-this, on the seeded holds.
- **Rollback-isolation variant:** one IT that runs inside a rolled-back transaction to contrast with the committed-state race tests, with the one-sentence boundary statement in `notes/testcontainers-evidence.md`.
