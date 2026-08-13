# P07 Testcontainers Postgres — Code-Along Elective

## Objective

Prove one concurrency claim — the P03 double-approval race — against real PostgreSQL inside a Testcontainers container, with real migrations, real threads, and SQL assertions on committed state. One primary objective: install the boundary between fast in-memory fakes and authoritative real-database tests, and demonstrate why the sequential "race" test is not a race test.

## Time box

~2 hours, core.

## Prerequisites

- `P01_schema_and_migrations.md` — the migration files are what the container will run.
- `P03_approve_once_race.md` (or `P04_last_unit_inventory.md`) — you need one race claim and its repository method to prove inside the container.
- Docker (Testcontainers spins up `postgres:16-alpine`).
- Your P02 module plus the `org.testcontainers:postgresql` (and `junit-jupiter`) dependencies.
- Showcase position: before `../../pharmacy-fulfillment/exercise_02_optimization.md` (Milestones 1 and 7 demand real-PostgreSQL concurrency tests; this elective supplies the mechanism).

## Blog & curriculum links

- Primary: `posts/series-2-postgres/05-testing-postgresql.md` (the pyramid, the Testcontainers setup, seed/prune tooling, and the "assert against the database, not the mock" section).
- Secondary: `posts/series-2-postgres/06-showcase-concurrent-persistence.md` (the seven high-value tests; rows 1–2 are the ones you will implement).
- Coach-assessment gap this attacks: "Testing strategy: strong general instincts … make failure and concurrency tests concrete" — and it is the technical twin of the candidate's strongest behavioral story, the proprietary-database project (see the post's opening).

## Background & motivation

The proprietary-database story in the coach assessment is the whole thesis of this elective: a team of eight could not get the real database locally, so it split work into fast in-memory feedback and authoritative real-database validation — and never mistook one for the other. This kata flips the roles: PostgreSQL is *free* in Docker, so the authoritative side costs a container. The skill being rehearsed is the boundary itself: which claims need real PostgreSQL, which an in-memory fake can honestly support, and how deterministic seed/prune tooling keeps the authoritative suite stable.

The failure mode the kata attacks is subtler than "no tests": a test that calls `approveIfAwaiting` twice *sequentially* and asserts one winner passes even against code that loses races under concurrency. Sequential calls only resemble a race. The real test needs two threads, a start latch, and real PostgreSQL row locking — and the only way to see the difference is to run both versions.

It deliberately ignores RabbitMQ test containers (the R-track owns those, same discipline), failure injection at the network level (operational drill, A13), and any production scale claim (a 300-row container proves query shape, not capacity).

## Learning objectives

- Start a PostgreSQL Testcontainers container and enforce image-version parity with the Docker Compose stack.
- Run the P01 migrations against the container on startup — a fresh database every time.
- Build deterministic fixtures with fixed UUIDs and helpers like `awaitingPrescription(...)`.
- Write a latched two-thread race test and assert on committed state via a fresh `JdbcTemplate` query, not the repository's return value.
- Choose and implement a prune strategy (rollback vs `TRUNCATE ... RESTART IDENTITY CASCADE`) and prove suite repeatability.
- Say aloud which claims a fake repository can support and which it cannot — with this kata's evidence as the example.

## Warm-up

Read the "Testcontainers: Real PostgreSQL In CI" and "Deterministic Seed And Prune Tooling" sections of `posts/series-2-postgres/05-testing-postgresql.md` (about 5 minutes). Then check the version-parity rule you recorded in P01 Step 1: the compose file runs `postgres:16-alpine` (or whatever you pinned). Write the parity sentence in the test file header: *tests run the same image as the local stack* — a silent version gap is a real gap.

## System specification

**Scope in:** one Testcontainers setup, migrations-on-startup, deterministic fixtures, the double-approval race test with committed-state assertions, and a prune strategy.

**Scope out:** RabbitMQ containers (R-track), a full Spring Boot context (a `@SpringBootTest` is optional; a plain JUnit + repository wiring test is enough for the race claim), failure-injection drills, and load tests.

**Functional requirements (minimal):**

- The container starts with the compose-parity image; Flyway (or your P01 runner) applies the full migration set on startup.
- A fixture helper seeds a fixed-UUID prescription in `AWAITING_APPROVAL` and fixed inventory rows where needed.
- Two threads, synchronized with a latch, call the conditional update; the test asserts exactly one winner.
- The assertion reads committed state with a fresh connection: `count(*)` on history and/or the final `status`.
- The suite passes twice in a row on the same container (determinism evidence).

**Constraints:** real threads only; no sequential pseudo-races; no mocking of the database; assertions against committed rows, not repository self-reports.

## Step-by-step code-along

### Step 1 — Wire up the container

- **Do:** add the dependencies and a container fixture:
  ```kotlin
  @Testcontainers
  class ApprovalRaceIT {
      companion object {
          @Container
          @JvmStatic
          val postgres = PostgreSQLContainer("postgres:16-alpine")
      }
  }
  ```
  Point the datasource at `postgres.jdbcUrl` / username / password (with Spring: `@DynamicPropertySource`; without Spring: build the `DataSource` from the container directly).
- **Run:** `./gradlew test` with a trivial `@Test` that prints `postgres.jdbcUrl` and selects `version()`.
- **Observe:** the container starts, the test connects, PostgreSQL 16 answers. Record the startup time once — the "Testcontainers is slow" objection usually dies at "a few seconds."
- **Decision:** shared container per module (one `companion object` container reused by all ITs) vs per-test. Nudge: one shared container per module keeps startup at seconds; concurrency across test classes then needs your prune strategy (Step 5), not more containers.

### Step 2 — Migrations on startup

- **Do:** run the P01 migrations against the container before tests execute — programmatic Flyway (`Flyway.configure().dataSource(...).load().migrate()`) in the fixture, or Spring Boot's auto-configuration once a module exists.
- **Run:** the smoke test again, then `\dt` equivalent (`select tablename from pg_tables where schemaname = 'public' order by tablename;`).
- **Observe:** all seven tables, seed present — the same migration set the compose stack uses. A migration that breaks production breaks this suite; that is the point.
- **Decision:** none — the blog's rule is absolute here: no `ddl-auto`, no hand-rolled schema, migrations are the schema.

### Step 3 — Deterministic fixtures

- **Do:** write fixture helpers, mirroring the post's shape:
  ```kotlin
  fun awaitingPrescription(repository: PrescriptionRepository, id: UUID = UUID.randomUUID()): UUID
  fun seedInventory(medicationId: UUID, quantity: Int)
  ```
  with fixed UUIDs for the fixtures you assert against, and the P01 seed as the baseline.
- **Run:** a test that seeds one awaiting prescription, approves it once, and asserts the returned `AppliedTransition` is present.
- **Observe:** every run starts from a known state — the shared `inventory` row in particular must be seeded by the test that asserts about it, never assumed from a previous test (the blog calls this the one row that must be pruned rigorously).
- **Decision:** fixed IDs for everything assertable vs random with returned handles. Nudge: fixed IDs make the assertion SQL readable (`WHERE prescription_id = '0000...'`); random IDs make parallel runs collision-proof. For a shared-container suite, the prune strategy decides.

### Step 4 — The sequential "race" (write the anti-pattern first)

- **Do:** deliberately write the wrong test:
  ```kotlin
  @Test
  fun `sequential approve calls produce one winner`() {
      val id = awaitingPrescription(repo)
      assertNotNull(repo.approveIfAwaiting(id))
      assertNull(repo.approveIfAwaiting(id))
  }
  ```
- **Run:** it — and watch it pass.
- **Observe:** it passes *even if the conditional update had no predicate*, because the second call observes the committed first result. It is not a race test; it is a happy-path test in disguise.
- **Paste its output** with the label **"sequential: always passes"**. You will reuse this contrast in the Try-this.
- **Decision:** none — you wrote it to catch the difference, not to keep it.

### Step 5 — The real race test

- **Do:** write the latched version (reuse your P03 harness):
  ```kotlin
  @Test
  fun `only one approval wins the conditional update race`() {
      val id = awaitingPrescription(repo)
      val start = CountDownLatch(1)
      val results = listOf(
          executor.submit { start.await(); repo.approveIfAwaiting(id) },
          executor.submit { start.await(); repo.approveIfAwaiting(id) }
      )
      start.countDown()
      val winners = results.map { it.get() }.count { it != null }
      assertEquals(1, winners)
  }
  ```
- **Run:** it 10 times.
- **Observe:** exactly one winner, every run. Then extend the assertion to committed state — via a fresh `JdbcTemplate` connection *outside* the test transaction:
  ```kotlin
  val historyCount = jdbc.queryForObject(
      "SELECT count(*) FROM prescription_status_history WHERE prescription_id = ?",
      id, Int::class.java)
  assertEquals(1, historyCount)
  ```
  The fresh read is what a concurrent reader would see — the repository's own return value cannot be trusted to report committed facts.
- **Paste the 10-run output and the history count.**
- **Decision:** assert through direct SQL vs through the repository. Nudge: direct SQL — the repository could hide a bug in the assertion path.

### Step 6 — Prune strategy

- **Do:** pick a prune strategy for the shared container:
  - **Rollback-based isolation:** each test runs inside a transaction rolled back at the end. Fast, but cannot exercise commit, and concurrent tests cannot share state.
  - **`TRUNCATE ... RESTART IDENTITY CASCADE`:** between test classes, wipe and reset. Slower, honest about committed state, parallel-safe.
- **Run:** the full IT suite twice back-to-back.
- **Observe:** run two produces identical results — determinism is the success criterion, not the mechanism. (For this race test the flush-between-classes choice is the honest one, because the race test genuinely commits.)
- **Decision:** documented in the file header, with the one-line reason.

### Step 7 — Evidence ledger

- **Do:** collect `testcontainers-evidence.md`: version-parity sentence, migration-on-startup proof, the sequential-vs-latched contrast, the 10-run output, and the history-count assertion.
- **Run:** nothing new.
- **Observe:** you can now answer "which of these claims are covered by tests against real PostgreSQL?" with a file — the blog's final checklist question.

## Try this

**Break the conditional update and watch the two tests disagree.** Temporarily remove the status predicate from `approveIfAwaiting` (back to `WHERE id = :id`). Run the suite:

- **Expected:** the sequential test from Step 4 **still passes** (second call sees the committed first). The latched test **fails**: both threads return a transition, and the history count is 2.
- **Paste the failing latched output** next to the passing sequential output. This pair of outputs is the answer to "why do real-threaded, real-database tests matter?" — one screenshot proves sequential calls only resemble races.
- **Re-add the predicate and re-run** until green.

## Trade-off fork

**Option A — rollback-based isolation:** each test wraps in a transaction and rolls back. Fastest inner loop; no commit semantics exercised; shared-state tests (like the race, which must commit) cannot run inside it.

**Option B — `TRUNCATE ... RESTART IDENTITY CASCADE` between test classes:** honest committed state, parallel-safe, the only honest home for the race test; costs a slower tear-down and the discipline of seeding every scenario from scratch.

Choose one and write 3–5 lines justifying it for *this* suite (which contains a real commit), naming what the other bought. The blog treats both as defensible; the race test's need to commit is the fact that tips the analysis — name it.

## Hints

**Hint 1 (mild):** if the container won't start, check Docker is running and the image name matches something you can `docker pull` locally. If `count(*)` assertions fail, the test's connection is probably sharing the production datasource rather than the container's — make sure the fixture wires the *container* URL.

**Hint 2 (stronger):** the sequential test in Step 4 is not a throwaway — keep it in the suite, clearly named (`sequential approve calls produce one winner`) or commented as the anti-pattern contrast. It is evidence, not clutter: an interviewer can read the two tests side by side faster than any explanation. If the latched test flakes, check the latch wiring (both tasks must `await` *before* calling the repository) rather than weakening the assertion.

## Checkpoint / success criteria

You may leave when:

- [ ] The container runs the P01 migrations on startup; the smoke test connects and lists all seven tables.
- [ ] The latched race test passes 10 consecutive runs with exactly one winner, and the committed-state `count(*)` assertion is part of the test.
- [ ] `testcontainers-evidence.md` contains: the parity sentence, the sequential-vs-latched contrast (with the broken-predicate failure output from Try-this), and the 10-run output.
- [ ] The prune strategy is chosen, documented, and the suite passes twice back-to-back.
- [ ] You can state the boundary aloud: which of this system's claims need real PostgreSQL (concurrency, constraints, `RETURNING`, plans) and which a fake can honestly support (domain transitions, orchestration, error mapping).

## Bottleneck & reflection questions

- Which claims in this system are proven only by real PostgreSQL, and which can an in-memory fake honestly support? Give one example of each from this elective.
- Why does the sequential test pass against the broken code, and what does that tell you about every "concurrency test" that calls methods one after another?
- The image in your container must match the compose stack. What breaks silently if it does not, and what makes the gap invisible in CI?
- Why assert via a direct SQL query on committed state instead of the repository's return value — and when would the repository be the *only* acceptable probe?
- The proprietary-database story split work into in-memory speed and real-database authority. Which two artifacts in this elective map onto those two halves, and what would you tell your team of eight about the boundary?

## Handoff

- **Next electives:** `../advanced/A09_postgres_under_contention.md` — your containerized race harness is exactly what A09 needs to drive deadlocks and `SKIP LOCKED` claims without touching the local stack. `../rabbit/R01_topology_scratchpad.md` — the R-track applies the same Testcontainers discipline to RabbitMQ, and R-track tests will reuse your P01 migrations for their inbox/outbox assertions.
- **Showcase:** `../../pharmacy-fulfillment/exercise_02_optimization.md` — Milestone 1 ("reproduce and measure Foundation bottlenecks") and Milestone 7 ("run concurrency and load tests against the target") both require real-PostgreSQL tests in containers; your `testcontainers-evidence.md` is the mechanism and proof for those milestones, and the seed/prune discipline here is what keeps the load harness deterministic.
- **Interview line you should be able to say aloud:** "The suite is split by claim, not by taste — domain logic gets fast in-memory tests, and anything about the database — conditional updates under real threads, constraints, `RETURNING`, query plans — runs against a Testcontainers PostgreSQL with the same migrations and the same image as the compose stack, with assertions on committed state read through a fresh connection. That boundary is the same one I used in the proprietary-database project: fast local feedback, authoritative real-engine validation, and seed/prune tooling that keeps both deterministic."

## Optional stretch

Add the P04 last-unit race as a second IT: seed one unit, fire two latched `tryReserve` calls, assert one winner and `available_quantity = 0` via a fresh read. Then add a constraint test: insert a negative quantity directly and assert the `23514` violation surfaces as the expected exception. These two tests — a race and a constraint — cover the two halves of "what the database actually does" better than any single scenario, and they are the exact rows Exercise 2's model table expects to see proven.
