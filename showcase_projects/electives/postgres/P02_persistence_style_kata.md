# P02 Persistence Style Kata — Code-Along Elective

## Objective

Pick **one** of JDBC / jOOQ / JPA and map the P01 tables to Kotlin types: a read path that returns rows as data classes with correct nullability, and a write path that uses `INSERT ... RETURNING` and interprets a zero-row conditional update. One primary objective: get fluent mapping rows ↔ Kotlin in a style you can defend, before any concurrency complexity lands in P03.

## Time box

~2–3 hours, core. The aggregate-mapping stretch adds ~30 minutes.

## Prerequisites

- `P01_schema_and_migrations.md` — the schema and seed are required; you will read and write against them.
- `../glue/X01_docker_compose_trio.md` or a running `postgres:16` from P01.
- A minimal Gradle Kotlin module with the PostgreSQL driver (`org.postgresql:postgresql`) and the dependency of your chosen style. Spring Boot is optional; for the JDBC path `spring-jdbc` alone (for `JdbcClient`/`JdbcTemplate`) is enough.
- Showcase position: before `../../pharmacy-fulfillment/exercise_02_optimization.md`. The repository boundaries you build here are the ones Exercise 2's Milestones 2–3 will harden.

## Blog & curriculum links

- Primary: `posts/series-2-postgres/01-schema-design.md` (the schema you map; the "Constraints Are Product Decisions" section tells you which columns are load-bearing).
- Secondary: `posts/series-2-postgres/02-transactions-isolation.md` (the `AppliedTransition` sketch and `RETURNING` discussion that this kata's write path anticipates).
- Coach-assessment gap this attacks: "Java-to-Kotlin translation is not yet demonstrated" and the PostgreSQL implementation gap — this kata is where the two meet.

## Background & motivation

A Lead with a C#/Java background has mapped rows to objects in ADO.NET, Dapper, Hibernate, and EF. The reflex is to reach for the highest-level tool and to treat the mapping layer as plumbing. This kata exists to make the mapping layer *deliberate*: in this codebase the repository is where the pharmacy invariants will live (P03, P04, P05), so the style you pick must expose affected rows, `RETURNING` results, and nullable outcomes without fighting you.

It deliberately ignores concurrency (P03/P04), query tuning (P06), migrations tooling (P01), and Testcontainers (P07). Its only job is type mapping and one write-path sketch, in real PostgreSQL, with the P01 seed.

## Learning objectives

- Stand up one persistence style against local PostgreSQL from a blank Kotlin module.
- Map rows to immutable data classes with correct types: `UUID`, `Instant`/`OffsetDateTime` for `timestamptz`, `Int`/`Long` for quantities, and nullable `String?` for columns like `reason`.
- Use parameter binding (not string concatenation) and `INSERT ... RETURNING`; contrast with SQL Server's `OUTPUT` clause.
- Represent "no row" honestly in each style: empty result / `Optional.empty` / nullable — and say which one survives service-level scrutiny.
- Execute a conditional-update sketch and observe how each style reports affected rows or returned rows (the seam P03 builds on).
- Decide nullability at the boundary the way a reviewer would: nullable repository results for ordinary absence, not `Optional` cargo cult.

## Warm-up

2–3 minutes. Re-read the repository sketch in `posts/series-2-postgres/03-inventory-reservation.md` (the `tryReserve` JdbcClient example) and the `AppliedTransition` data class in `posts/series-2-postgres/02-transactions-isolation.md`. Then pick your style and write down two sentences: *why this style, and what do I expect it to hide from me?* You will revisit that note in the Trade-off fork.

## System specification

**Scope in:** one read (inventory row by `medication_id`, and a prescription with a nullable reason in history), one write (insert a prescription with `RETURNING id, status_version`, or the conditional inventory decrement sketch), and one "row missing" probe.

**Scope out:** full service layer, transactions spanning multiple statements (P03/P05), indexes (P06), tests against containers (P07 — a plain test or `main()` smoke against the local database is fine here), and any ORM entity lifecycle features.

**Functional requirements (minimal):**

- `selectAvailableQuantity(medicationId): Int?` — null for unknown medication, never a default.
- `insertSubmitted(...)` returning the inserted `id` and `status_version` via `RETURNING`.
- A conditional inventory update `UPDATE ... WHERE available_quantity >= :q RETURNING available_quantity` that surfaces "no row" as a nullable/empty result.
- One smoke run proving all three against the P01 seed.

**Constraints:** local Docker PostgreSQL only; no cloud; Kotlin `val` and immutable data classes at the boundary; no `Optional`-in-Kotlin ceremony for what a nullable expresses directly.

## Step-by-step code-along

### Step 1 — Scaffold and connect

- **Do:** create a Gradle Kotlin module with the driver and your chosen style's dependency. Configure the datasource from env vars or a small `application.properties` pointing at the P01 `pharmacy` database.
- **Run:** `./gradlew run` with a trivial `main()` that opens a connection and prints `SELECT version();`.
- **Observe:** connect, print `PostgreSQL 16.x`, exit cleanly. This is your only infra step; everything else is mapping.
- **Decision:** how to hold the datasource. Nudge: a single `DataSource` created in `main()` is fine for this kata; a Spring `@Configuration` is only worth it if you already have a Spring module from S-track electives.

### Step 2 — Kotlin types for the rows

- **Do:** define immutable types:
  ```kotlin
  data class InventoryRow(val medicationId: UUID, val availableQuantity: Int, val updatedAt: Instant)
  data class HistoryRow(val prescriptionId: UUID, val sequenceNumber: Long, val status: String, val reason: String?)
  ```
  Notice the decisions already in these five lines: `Instant` for `timestamptz`, `String?` for `reason`, `Int` for quantity.
- **Run:** `./gradlew compileKotlin`.
- **Observe:** compilation succeeds — and then think about what SQL Server + C# would have let you defer. The `String?` forces you to handle absence at the type level; there is no NPE at 2 a.m.
- **Decision:** `Int` vs `Long` for quantity and sequence numbers. Nudge: `sequence_number` is `bigint` in the schema, so a `Long` on the Kotlin side is the honest mapping; `Int` for `available_quantity` matches the schema's `integer` but forces a decision the day quantities grow.

### Step 3 — The read path

- **Do:** implement `findInventory(medicationId): InventoryRow?` using parameter binding.
- **Run:** a smoke `main()` that looks up Amoxicillin's P01 seed row, then a bogus UUID.
- **Observe:** Amoxicillin returns its seeded quantity; the bogus UUID returns null — not an exception, not a sentinel. In jOOQ: `fetchOptional().orElse(null)`; in plain JDBC: a single-row `ResultSet` check; in JPA, this is where a repository `Optional<Entity>` starts leaking laziness into the service.
- **Decision:** repository returns nullable vs sealed result for *this* read. Nudge: the blog's convention is nullable for ordinary absence, sealed outcomes for expected business alternatives — P03 will build the sealed part; keep this method nullable.

### Step 4 — Nullable columns, deliberately

- **Do:** read one `prescription_status_history` row (seed one manually if needed) where `reason IS NULL`, and map it to `HistoryRow`.
- **Run:** print the row. Then change the mapping to `String` (non-null) and recompile.
- **Observe:** the compiler — not the test suite, not production — points at the exact line that would have been an NPE under a Java/`String` mapping. This is the Java-to-Kotlin translation gap the coach assessment flagged, shown in 30 seconds.
- **Decision:** none; just record the observation.

### Step 5 — The write path with `RETURNING`

- **Do:** implement `insertPrescription(id: UUID, patientId: UUID, ...)` ending with `RETURNING id, status_version`, and map the returned row to a small `InsertedPrescription` value.
- **Run:** insert one row, print the returned `status_version` (should be 0 from the default), then select it back.
- **Observe:** the write *is* the read — no second `SELECT` after insert, which is exactly how SQL Server's `OUTPUT` clause or `OUTPUT INSERTED` works. One difference to say aloud: in SQL Server you often need `OUTPUT` with `MERGE` or a table variable for multi-row cases; PostgreSQL's `RETURNING` composes directly with the statement, including `UPDATE` and `DELETE`.
- **Decision:** client-generated UUID (P01's choice) vs `DEFAULT gen_random_uuid()` at the database. Nudge: the schema uses client-generated IDs so the API knows the ID before the first insert — changing it now breaks P03's retry-after-lost-response reasoning, so keep client-side.

### Step 6 — The zero-row probe (P03's seam)

- **Do:** implement the conditional decrement as a *sketch* — `UPDATE inventory SET available_quantity = available_quantity - :q WHERE medication_id = :m AND available_quantity >= :q RETURNING available_quantity` — and map "no row returned" to a nullable result.
- **Run:** against the seed: reserve 5 from Amoxicillin (succeeds), then reserve 10,000 (null).
- **Observe:** the first returns a quantity, the second returns null. In JDBC (`query(...).optional()` or `if (!rs.next())`), null means "no claim happened" — the same semantics P04 will bet the last unit on. Note what jOOQ does (`fetchOptional`) and what JPA makes awkward: a `@Modifying` query returns an `int` affected count, which *is* enough for "did it win" but drops the returned row, forcing a second statement to learn the new quantity.
- **Decision:** none now — but write down which style made the zero-row → outcome mapping the most honest. P03 will need that sentence.

### Step 7 — Evidence note

- **Do:** keep a `kata-notes.md` with the three smoke outputs (read, null column, return-values) and one line per style choice you made.
- **Run:** the smoke twice from a clean database to confirm reproducibility.
- **Observe:** no hidden state; the P01 seed makes every run identical. This determinism is what P07 formalizes.

## Try this

If your style allows it cheaply, express the *same* conditional decrement in a second style (a 15-minute side branch, not a rewrite): write the `tryReserve` sketch in plain JDBC and in JPA. Run both against the same seeded row.

- **Expected:** plain JDBC (and jOOQ) hand you "no row" directly; JPA hands you an `int`, and your side effect — "the new quantity" — requires an extra query or a different method shape.
- **Paste** both results into `kata-notes.md` and write two sentences: what the higher-level style bought you, and what it cost on exactly the statement this domain will run under race conditions in P03/P04.

## Trade-off fork

**Option A — JDBC (`JdbcClient`/`JdbcTemplate` or raw driver):** every SQL statement is visible and yours; `RETURNING`, affected rows, and partial indexes are first-class; the blog series' own repository sketches are JDBC-shaped.

**Option B — jOOQ:** compile-time-checked SQL in Kotlin, composable statements, `fetchOptional()` returns nullable results naturally; costs a DSL to learn and a generated/reflected schema layer to explain.

**Option C — JPA/Hibernate:** least boilerplate for CRUD, but dirty-checking and `@Modifying int` results sit awkwardly on a design whose correctness lives in `RETURNING` and affected rows — and a 2–5 hour challenge has no entity lifecycle worth managing.

Choose one and write 3–5 lines justifying it for *this* codebase (not "in general"), naming what the runners-up lost. The curriculum bias is visible — the posts use `JdbcClient` — but that is a soft bias, not a hard constraint: a jOOQ answer defended on type-safety or a JPA answer defended on team familiarity is defensible in an interview if you can name the lost benefits (for JPA: the conditional-update semantics get buried, and Exercise 2's evidence demands they stay visible).

## Hints

**Hint 1 (mild):** the parameter names in your SQL should match the schema column names (`medication_id`), and if you use Spring's `JdbcClient`, `.param("medication_id", id)` binds by name — no positional `?` juggling. Check whether your driver maps `timestamptz` to `OffsetDateTime` or `Instant`; either works, but be consistent.

**Hint 2 (stronger):** for the `RETURNING` mapping in plain JDBC, a `RowMapper` that reads `rs.getObject("id", UUID::class.java)` and `rs.getObject("status_version", Long::class.java)` is the whole technique; there is no need for a row-streaming library. If you picked JPA and the conditional update refuses to behave, the intended answer is `@Query` with a native `UPDATE ... WHERE ... RETURNING` declared as a modifying query — not dirty-checking.

## Checkpoint / success criteria

You may leave when:

- [ ] `findInventory`, `insertPrescription` (with `RETURNING`), and the conditional decrement all run against the P01 database and their outputs are saved.
- [ ] A `NULL` `reason` mapped cleanly to `String?` and a non-null recompile failed at compile time.
- [ ] You can say exactly how your style represents "no row" for the conditional update, and what the service should do with it (P03 formalizes this).
- [ ] One paragraph in `kata-notes.md` explains your style choice and its lost benefits.

## Bottleneck & reflection questions

- Which columns in the P01 schema are nullable *on purpose*, and which are nullable only because the app never set them?
- Your read returns `InventoryRow?`. When does a nullable result become an API `404`, when does it become a `409`, and who makes that call — the repository or the service?
- Where would your style push you toward an N+1 (P06's word for it), and what in P01's composite keys makes the join natural?
- `RETURNING` collapses read-then-write. Why does that matter more here than it did in your SQL Server `OUTPUT` usage?
- If a teammate insisted on a mutable entity with a `status` setter, which of the blog's invariants would their design silently drop?

## Handoff

- **Next electives:** `P03_approve_once_race.md` (your conditional-update seam becomes the whole point) and `P04_last_unit_inventory.md` (the `tryReserve` sketch becomes the reservation decision). `P06_index_and_explain.md` needs the query shapes your repository now has.
- **Showcase:** `../../pharmacy-fulfillment/exercise_02_optimization.md` — Milestone 2 says "repository operations expose whether the conditional write won"; that sentence is the contract your style choice must satisfy, and `../rabbit/R01_topology_scratchpad.md` will later consume the outbox rows you only mapped here.
- **Interview line you should be able to say aloud:** "I chose the persistence style that keeps the SQL visible, because this domain's correctness lives in conditional updates and `RETURNING` — a mapper that hides the statement hides the invariant."

## Optional stretch

Map the whole prescription aggregate (prescription + its lines) in one query and one mapping pass, returning a `Prescription` with a `List<PrescriptionLine>`. Do it twice: once with a separate query per line (the N+1 shape), once with a single query and client-side grouping. Record the query count difference. This is the exact comparison Exercise 2 Milestone 6 asks you to evidence, and you have now seen it before the showcase demands it.
