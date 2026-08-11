# Testing PostgreSQL Behavior for Real

The most valuable testing lesson in this preparation is not a PostgreSQL feature at all. It comes from the proprietary-database project: a required database had no local or container option, weak documentation, and delayed access. The team of eight needed to stay productive anyway, so the work split into two halves. An in-memory engine plus deterministic seed and prune tooling gave everyone fast local and CI feedback. And the service was still validated against the real database, first through REST, later through ODBC, before production.

That project failed to deliver the answers in one sense: in-memory tests cannot be the last word on a database. But the story's real lesson is about the boundary between two kinds of tests — the ones that give fast feedback and the ones that prove behavior — and why the fast ones must never be mistaken for the authoritative ones.

The pharmacy challenge is the same problem with the roles reversed. PostgreSQL is available in Docker, so the authoritative side costs almost nothing: a `Testcontainers` container with the same version, real migrations, and real concurrency. The interview-relevant skill is deciding which claims must be tested against real PostgreSQL, which claims an in-memory fake can honestly support, and how deterministic seed and prune tooling keeps the authoritative suite stable in CI.

## The Division Of Labor From The Story

The proprietary-database project is worth rehearsing in exactly this framing, because it is the strongest evidence that the testing strategy here is not textbook talk:

- **Situation:** the required database had no local or container option, weak documentation, and delayed access for the team.
- **Task:** keep seven or eight developers productive while reducing the risk of discovering data-layer problems late.
- **Action:** the team defined database-agnostic contracts, built an in-memory engine and deterministic seed/prune tooling, enabled local, UI, and CI testing, and validated the service against the real database through REST and later ODBC.
- **Result:** eight developers were productive after the first sprint, the system reached production for roughly 50 users at about 100,000 event writes per hour, with testing planned for roughly 500,000.

The interview answer is the boundary: the in-memory engine made iteration possible at team scale; the real-database validation made the data-layer claims trustworthy; and the seed/prune tooling made both sides deterministic. The candidate chose what to fake, what to validate, and how to keep the two honest. That is exactly the decision this post rehearses for PostgreSQL.

## When An In-Memory Fake Is Useful

An in-memory test double earns its place when the claim under test is not "the database behaved correctly" but "the application logic is correct given database behavior." The typical targets:

- **State-machine logic.** A prescription can only move through legal transitions, a rejected prescription cannot return to `AWAITING_APPROVAL`, and the service maps "no row updated" to the right outcome. The domain rules live in Kotlin and deserve fast tests with no database at all.
- **Service orchestration.** Approval must call the repository transition, append history, and insert an outbox record in order, and must not call the RabbitMQ publisher. A fake repository verifies the call sequence and the failure branches.
- **Validation and error mapping.** Blank medication IDs, negative quantities, and unknown prescription IDs map to the API contract.
- **The fast inner loop.** While implementing, a test that runs in milliseconds is the tool for TDD and refactoring. The Kotlin testing post already covered test data builders and behavior-focused tests; those habits apply unchanged.

The disciplined version of the fake is a real Kotlin in-memory store, not a mock that returns canned rows. The repository interface is implemented against maps, enforcing the same status transitions the domain requires. That is what the proprietary-database project did with its in-memory engine, and it is why the team could develop against it for weeks.

The rule to state in an interview: the fake is useful only as long as it never has to answer a question only the database can answer. The moment a test asserts affected-row counts, constraint violations, lock contention, or transaction behavior, the fake is fiction.

## When Real PostgreSQL Is Authoritative

The pharmacy challenge puts several behaviors under test that no in-memory fake reproduces honestly. The previous posts already claimed them; the tests are where the claims are proven:

**1. Conditional updates and affected rows.** The approval transition from the transactions post wins exactly once because PostgreSQL re-evaluates the `WHERE` predicate after waiting for a concurrent writer. A fake cannot reproduce the `READ COMMITTED` recheck or the zero-row result under two real transactions. Test it with two connections and real rows.

**2. Constraints as the last line of defense.** The `CHECK (available_quantity >= 0)` constraint, the unique inbox key, and the foreign keys are database behavior. A fake repository that implements the interface by contract may "enforce" them, but the real test is that a conflicting insert raises a constraint violation and that the transaction rolls back — including the part where PostgreSQL marks the transaction failed and any further statement in it aborts.

**3. `INSERT ... ON CONFLICT` idempotency.** The inbox pattern depends on the primary key `(consumer_name, event_id)` rejecting a duplicate. Only real PostgreSQL proves that two deliveries of the same event yield one processed row and no duplicate effect.

**4. `RETURNING` and transaction rollback.** "The outbox insert fails during approval, and the prescription stays `AWAITING_APPROVAL` with no history row" is a claim about `RETURNING` results and atomic rollback. Assert it against the database.

**5. Locking, deadlocks, and `SKIP LOCKED`.** Deadlock errors are raised by PostgreSQL's lock manager, with a specific `SQLState` the application must handle. Whether the outbox relay claims rows without double-publishing depends on `FOR UPDATE SKIP LOCKED` under real concurrency. None of this exists in a fake.

**6. Migrations.** The real migrations must apply cleanly to a fresh database, because that is what the production Docker Compose stack does. The Flyway run against a Testcontainers instance is the migration test.

**7. Query plans.** The index post said index choices are validated with `EXPLAIN ANALYZE` against real PostgreSQL. A plan generated against a fake database is about a database that does not exist. The authoritative plan comes from the real engine, seeded with realistic row counts.

**8. Type and serialization behavior.** `timestamptz` instants, `uuid`, `jsonb` payloads, and identity allocation behave in specific ways. A test that stores an outbox payload as `jsonb` and reads it back proves the application and the driver agree.

The one-line summary for an interview: real PostgreSQL is authoritative whenever the claim is about what the database does — concurrency, constraints, transactions, or plans. An in-memory fake is a fast approximation of the application, not a model of the database.

## The Testing Pyramid For This Challenge

The layered suite, from cheapest to most expensive, each layer with a clear claim:

1. **Unit tests** — domain transitions, validation, error mapping. No database. Milliseconds.
2. **Repository integration tests** — one conditional update wins a race, `ON CONFLICT` deduplicates, constraints roll back, `RETURNING` returns the winning row. Testcontainers PostgreSQL, real migrations, seeded rows. This is the layer where most PostgreSQL claims are proven.
3. **Service integration tests** — `@Transactional` boundaries: approval, history, and outbox insert commit together; the failure path rolls back together. Same Testcontainers database, full Spring context or a slice.
4. **End-to-end test** — the submitted prescription ends up visible in the patient status endpoint with the right history, running the real application against the Docker Compose stack. This is the demo-proof test for a two-hour submission.

The balance matters for the time budget. The previous post listed six high-value integration tests for transaction behavior; implement those first. They are fewer than a dozen tests and they are the ones that would catch the real failure modes — the double-approval, the oversell, the orphaned reservation. Everything else is supporting cast.

## Testcontainers: Real PostgreSQL In CI

Testcontainers gives the suite the authoritative engine with the cost of a Docker container. The Kotlin setup for a repository integration test:

```kotlin
@Testcontainers
@SpringBootTest
class PrescriptionRepositoryIT {

    @Container
    @JvmStatic
    private val postgres = PostgreSQLContainer("postgres:16-alpine")

    @DynamicPropertySource
    @JvmStatic
    fun postgresProperties(registry: DynamicPropertyRegistry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl)
        registry.add("spring.datasource.username", postgres::getUsername)
        registry.add("spring.datasource.password", postgres::getPassword)
    }

    @Test
    fun `only one approval wins the conditional update race`() {
        fixture.awaitingPrescription(id)

        val first = executor.submit { repository.approveIfAwaiting(id) }
        val second = executor.submit { repository.approveIfAwaiting(id) }

        assertEquals(
            listOf(true, false).sorted(),
            listOf(first.get(), second.get()).sorted()
        )
    }
}
```

The choices inside this snippet are deliberate:

- **The image version matches the Docker Compose version.** Testing against `postgres:16-alpine` while the challenge runs `postgres:15` is a silent version gap. State the parity rule in the README.
- **Flyway runs on startup.** The test database is created by the same migrations as production, not by JPA `ddl-auto` or a hand-rolled schema. A migration that breaks production breaks the suite.
- **Repository integration tests are the ones written against the real database.** Service tests use the same container. The count of containers matters less than the count of containers reused; one shared container per module keeps startup cost at seconds, not minutes.
- **Concurrency tests use real threads.** Two executor tasks submitting the same conditional update is the whole point. A sequential test that calls `approveIfAwaiting` twice proves nothing about races.

Two anti-patterns to name in an interview: skipping Testcontainers "because it is slow" and testing the database itself. Startup is a few seconds for a challenge-sized suite, and nobody needs a test that PostgreSQL returns `1 + 1`. The suite tests that the application's SQL, migrations, and boundaries behave against the real engine.

## Deterministic Seed And Prune Tooling

The proprietary-database story's seed and prune tooling was the piece that made both the fast and the authoritative sides usable at team scale. The pharmacy challenge needs the same discipline on a smaller budget. Deterministic means the same test run produces the same database state every time, regardless of order or parallelism.

**Seed as code, with fixed IDs.** Every test scenario starts from a helper that inserts known rows with explicit UUIDs — a known patient, a known medication, a prescription in a chosen state. Fixed IDs make assertions readable:

```kotlin
object Fixtures {
    val patientId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val medicationId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
}

fun awaitingPrescription(repository: PrescriptionRepository, id: UUID = UUID.randomUUID()): UUID {
    repository.insert(
        PrescriptionEntity(
            id = id,
            patientId = Fixtures.patientId,
            status = "AWAITING_APPROVAL"
        )
    )
    return id
}
```

**Prune to a known boundary, not to zero randomness.** Two patterns dominate, and both are defensible:

- **Transactional rollback.** The test runs inside a transaction that is rolled back at the end, leaving the seed data untouched for the next test. Fast, but it cannot exercise commit, and concurrent tests cannot share the database state.
- **`TRUNCATE ... RESTART IDENTITY CASCADE`.** Between test classes, wipe the schema's rows and reset identity counters. Slower than rollback but honest about committed state and parallel-safe.

The inventory fixture is the one that must be pruned rigorously. A test that asserts "reserving five units leaves three available" fails if a previous test already decremented the row. Each scenario should seed the exact inventory it asserts against, then leave the schema clean.

**One schema per parallel worker.** CI parallelism and shared state are incompatible. Either run the authoritative suite in one job with serialized tests, or give each worker its own database (Testcontainers makes this trivial: each worker gets its own container). The seed and prune tooling is what allows either choice to be deterministic.

The seed tooling also serves the demo. The challenge's own evaluation runs the app from Docker Compose; a seed script that populates inventory and a few prescriptions means the reviewer sees a working workflow without reading SQL. Deterministic seeds are not a testing-only concern, which is exactly the kind of observation the interview rewards.

## Assert Against The Database, Not The Mock

The strongest assertions in an integration test are SQL assertions. "The repository returned success" is weak evidence; "the database contains exactly one approval history row and one unpublished outbox event" is the claim being tested:

```kotlin
@Test
fun `approval commits history and outbox event together`() {
    val id = fixture.awaitingPrescription(repository)

    service.approve(id)

    val historyCount = jdbc.queryForObject(
        "SELECT count(*) FROM prescription_status_history WHERE prescription_id = :id",
        mapOf("id" to id),
        Int::class.java
    )
    assertEquals(1, historyCount)

    val outboxCount = jdbc.queryForObject(
        "SELECT count(*) FROM outbox_events WHERE aggregate_id = :id AND published_at IS NULL",
        mapOf("id" to id),
        Int::class.java
    )
    assertEquals(1, outboxCount)
}
```

The pattern generalizes to every claim in the series:

- Two concurrent approvals → `count(*) FROM prescription_status_history` equals 1.
- Oversell attempt → `available_quantity` is never negative and exactly one reservation row exists.
- Rejected with a reservation → the reservation is `RELEASED` and inventory is restored in the same transaction.
- Inbox deduplication → two deliveries of one `event_id` leave exactly one processed row.
- Outbox relay claim → rows claimed with `SKIP LOCKED` are not claimed twice.

Asserting through the same repository the service uses is weaker than asserting through a direct `JdbcTemplate` query: the repository could hide a bug in the assertion path. When the claim is about committed state, read it with a fresh connection outside the transaction, so the assertion observes what a concurrent reader would see.

## What The Suite Does Not Prove

A testing post that does not say where its authority ends would be lying about the pyramid. These are deliberately not proven by this suite:

- **Broker behavior.** RabbitMQ redelivery, dead-lettering, and publisher confirms are not PostgreSQL's domain. The RabbitMQ series owns those tests, with the same Testcontainers discipline.
- **Failure injection at the network level.** Killing a database container mid-transaction is an operational drill, not a unit test. The crash windows from the transactions post are reasoned about and documented, not all simulated.
- **Production scale.** A 300-row table with a warm cache proves the query shape, not the production behavior. The index post said exactly this: plans are validated for structure, and scale questions are answered with reasoning, not a bigger test.
- **Exactly-once.** No suite proves exactly-once anything. The tests prove at-least-once plus idempotency: duplicates can arrive, and the inbox makes them harmless.

Naming the limits is itself interview material. It is the same honesty the proprietary-database story required: the in-memory engine could not answer what the real database would do, so the team validated against the real one, and knew which claims the tests supported.

## Interview Review Checklist

- Which claims in this challenge are proven only by real PostgreSQL, and which can a fake honestly support?
- Why is a fake repository that mirrors the interface not a substitute for a Testcontainers test of the conditional update?
- What does the `READ COMMITTED` recheck look like in a test, and how do you write the concurrency test that observes it?
- Why must the Testcontainers image version match the Docker Compose version, and why must migrations run against the test database?
- What makes a seed deterministic, and why does a shared inventory row make tests order-dependent?
- Rollback-based isolation versus `TRUNCATE ... RESTART IDENTITY CASCADE`: when is each the right prune strategy?
- Why assert via a direct SQL query instead of the repository under test?
- The proprietary-database project: what did the in-memory engine give the team, what did real-database validation give, and how did seed/prune tooling support both?
- Which behaviors does the PostgreSQL suite not prove, and which series owns them?
- What is the one end-to-end test that makes the challenge demo defensible, and why is it worth more than mocked listener tests?

## Interview Takeaway

Testing PostgreSQL is the same decision the proprietary-database project forced: split the suite into fast local feedback and authoritative validation, and never confuse the two. In-memory fakes and unit tests make the Kotlin logic fast to iterate; Testcontainers with real migrations proves the claims that matter — the conditional update that wins once, the constraint that rolls back, the inbox that deduplicates, the transaction that commits its history and outbox event together. Deterministic seed and prune tooling is what makes the authoritative side runnable in CI at all, and honest SQL assertions are what make the tests mean something. The boundary, once rehearsed on this challenge, transfers directly: it is the same discipline the candidate already demonstrated at production scale, and the interview is the place to say so.
