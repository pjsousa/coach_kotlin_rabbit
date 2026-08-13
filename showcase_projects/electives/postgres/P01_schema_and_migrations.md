# P01 Schema and Migrations — Code-Along Elective

## Objective

Build the minimal pharmacy schema — `prescriptions`, `prescription_items`, `inventory`, `inventory_reservations`, `prescription_status_history`, `outbox_events`, `inbox_events` — as versioned migrations with a deterministic medication/inventory seed, running against local Docker PostgreSQL. One primary objective: a defensible schema whose constraints express the invariants shared across requests, and whose migrations reproduce from an empty database in one clean pass.

## Time box

~2 hours, core for the Postgres track. The expand-and-contract stretch adds ~30 minutes.

## Prerequisites

- `../glue/X01_docker_compose_trio.md` (or at least a `postgres:16` container reachable from `psql`) — X01 is the infrastructure prerequisite for the whole track.
- `psql` client (the compose `db` service works, or a host install).
- No Kotlin or Spring required for this elective; a Spring module only matters later when Flyway runs on app startup.
- Showcase position: before `../../pharmacy-fulfillment/exercise_02_optimization.md`. P01 is the schema prerequisite for P02, P03, P04, P05, P06, and P07 — every later Postgres elective assumes these tables and the seed exist.

## Blog & curriculum links

- Primary: `posts/series-2-postgres/01-schema-design.md` (all sections; especially "Migration Discipline" and "PostgreSQL Choices From A SQL Server Background").
- Secondary: `posts/series-2-postgres/06-showcase-concurrent-persistence.md` (the model table shows which tables protect which invariants).
- Coach-assessment gap this attacks: "PostgreSQL-specific behavior is not yet familiar, despite strong SQL Server and transaction foundations. Focus on PostgreSQL DDL, … migrations."

## Background & motivation

This kata exists because the candidate's SQL Server DDL instincts are strong but transfer badly in three places: identity strategy, index vocabulary, and migration discipline. A Lead who can write a defensible `CREATE TABLE` in SQL Server will still reach for a `uniqueidentifier`-spelled-differently, a clustered-index assumption, and `IF NOT EXISTS` in a startup method — all of which the blog post explicitly warns against.

The exercise deliberately ignores application code, concurrency (P03/P04 own that), query tuning (P06 owns that), and testing (P07 owns that). The schema is also deliberately *not* the full fulfillment product: no patient table, no medication catalog, no batches, no warehouses. Owning only what the challenge needs — identifiers, not identity systems — is itself an interview answer.

## Learning objectives

- Write versioned, forward-only migrations and prove they apply cleanly from an empty database.
- Choose identity strategy deliberately: client-generated `uuid` vs `GENERATED ALWAYS AS IDENTITY` vs SQL Server's `IDENTITY`.
- Encode cross-request invariants as constraints (CHECK, FK, UNIQUE, composite PK) instead of app-level `if`s.
- Name the PostgreSQL-vs-SQL-Server DDL differences: `uuid` vs `uniqueidentifier`, `timestamptz` vs `datetimeoffset`, `boolean` vs `bit`, `jsonb` vs `json`, partial index vs filtered index.
- Seed deterministically with fixed UUIDs and quantities so later electives (P03–P07) and the showcase can assert exact state.
- Explain why a constraint failing (`23514`) is not the same as a clean domain outcome — a theme P03 and P04 will exploit.

## Warm-up

Read the "Migration Discipline" and "PostgreSQL Choices From A SQL Server Background" sections of `posts/series-2-postgres/01-schema-design.md` (about 5 minutes). Then probe the database:

```bash
psql -h localhost -U postgres -d postgres -c 'select version();'
psql -h localhost -U postgres -d postgres -c 'show transaction_isolation;'
```

Observe: PostgreSQL 16.x, and `READ COMMITTED` — the default the whole track builds on. That default is the single most important difference from what you might assume in SQL Server.

## System specification

**Scope in:** the seven tables above, their constraints, the two indexes the post ships (`prescription_status_history_lookup_idx`, `outbox_unpublished_idx`, plus `outbox_aggregate_idx`), and a deterministic seed of five medications with inventory quantities.

**Scope out:** application code, stored procedures (there are none in this design), triggers, any index beyond the shipped ones (index decisions belong to P06), reservation release/consume logic (P04), and any RabbitMQ wiring (the outbox table is created here, nothing publishes from it).

**Functional requirements (minimal):**

- A migration set that applies in order to an empty database and leaves the seed in place.
- Constraints: legal status values, positive quantities, non-negative `available_quantity`, line `line_number > 0`, per-prescription line uniqueness, FK chains, outbox `event_id` PK, inbox `(consumer_name, event_id)` PK.
- Seed: Amoxicillin, Ibuprofen, Lisinopril, Metformin, Atorvastatin with fixed UUIDs and deterministic quantities (e.g., 100/80/60/40/25 — your choice, but write it down).

**Constraints:** local Docker PostgreSQL 16 only; versioned SQL files under `db/migration/`; no cloud, no managed DB, no `CREATE TABLE IF NOT EXISTS` in application startup.

## Step-by-step code-along

### Step 1 — Verify the stack

- **Do:** bring up PostgreSQL via the X01 compose file (or a bare `docker run -e POSTGRES_PASSWORD=... -p 5432:5432 postgres:16-alpine`), create a `pharmacy` database.
- **Run:** `docker compose ps` then `createdb -h localhost -U postgres pharmacy` and `psql -h localhost -U postgres -d pharmacy -c 'select current_setting(''server_version'');'`.
- **Observe:** clean connection, PostgreSQL 16. Record the exact image version — P07 will enforce that tests match it.
- **Decision:** none yet; just record the version.

### Step 2 — Pick the migration runner

- **Do:** create `db/migration/` in your scratch project and decide how migrations execute: Flyway (Docker CLI or, once a Spring module exists, the Maven/Gradle plugin) or plain numbered `psql -f` scripts. Defer the full justification to the Trade-off fork — just pick one now.
- **Run:** for Flyway via Docker:
  ```bash
  docker run --rm -v "$(pwd)/db/migration:/flyway/sql:ro" flyway/flyway:10 \
    -url=jdbc:postgresql://localhost:5432/pharmacy -user=postgres -password=... info
  ```
- **Observe:** an empty `info` list — no migrations applied, no errors. This is the baseline every later `migrate` run compares against.
- **Decision:** Flyway container CLI vs plugin. Nudge: the container CLI keeps this elective independent of a Spring module; the plugin is what P07 will reuse in the build.

### Step 3 — `V1` core tables: prescriptions and items

- **Do:** write `db/migration/V1__core_workflow_tables.sql` with `prescriptions` and `prescription_items`. Include: `id uuid PRIMARY KEY` (client-generated, not a database identity), a `status` CHECK against the Foundation vocabulary (`SUBMITTED`, `AWAITING_APPROVAL`, `APPROVED`, `PACKAGING`, `READY_FOR_COLLECTION`, `FULFILLED`, `REJECTED`), `created_at/updated_at timestamptz`, `status_version bigint`. Items get the composite PK `(prescription_id, line_number)`, the positive-quantity CHECK, the FK, and `UNIQUE (prescription_id, medication_id)`.
- **Run:** `flyway migrate` (or your psql equivalent), then `psql -d pharmacy -c '\d prescriptions'`.
- **Observe:** the CHECK constraints and PK are visible in `\d`. If you used the blog post's exact status list, note that the blog spells the ready state `READY` while the Foundation showcase uses `READY_FOR_COLLECTION` — one vocabulary must win.
- **Decision:** state vocabulary. Nudge: match `exercise_01_foundation.md` since the showcase is the product; write the divergence into your notes so an interviewer sees you caught it.

SQL Server callouts: `uuid` here is PostgreSQL's answer to `uniqueidentifier` (no `NEWID()` default — the app generates it, which is why there is no `DEFAULT gen_random_uuid()`; if you prefer a DB default, that is a one-line change and a defensible one). `timestamptz` is the safer default for workflow instants, the way `datetimeoffset` is in SQL Server — `timestamp without time zone` is the `datetime` trap.

### Step 4 — `V2` inventory and reservations

- **Do:** add `inventory` (`medication_id uuid PRIMARY KEY`, `available_quantity integer` with `CHECK (available_quantity >= 0)`) and `inventory_reservations` (`PRIMARY KEY (prescription_id, medication_id)`, FKs to both sides, quantity CHECK, status CHECK over `RESERVED/RELEASED/CONSUMED`).
- **Run:** `flyway migrate`, then `\d inventory_reservations`.
- **Observe:** both FKs and both CHECKs present. Say out loud why the `>= 0` CHECK stays even when P04 adds a correct conditional decrement: it converts a *future buggy writer* into a failed transaction, never a silent negative.
- **Decision:** reservation row with a status column vs a bare join table. Nudge: the status column is what lets P04 prove "release exactly once"; without it, release-idempotency has nothing to predicate on.

### Step 5 — `V3` status history

- **Do:** add `prescription_status_history` with composite PK `(prescription_id, sequence_number)`, `CHECK (sequence_number > 0)`, `actor_type`, nullable `reason`, and the lookup index `(prescription_id, sequence_number DESC)`.
- **Run:** `flyway migrate`, then `\d prescription_status_history` and `\di prescription_status_history*`.
- **Observe:** the index ships with the table because the timeline read is a known shape from day one. Note honestly: nothing in the DDL prevents `DELETE` from history — append-only is a discipline P05 will probe, not a constraint.
- **Decision:** none. But notice the sequence is per-prescription, not a global identity — that is the ordering key a future SSE replay will use (P05 and `../rabbit/R01_topology_scratchpad.md`).

### Step 6 — `V4` outbox and inbox

- **Do:** add `outbox_events` (`event_id uuid PRIMARY KEY`, `aggregate_type`, `aggregate_id uuid`, `event_type`, `payload jsonb`, `available_at`, `published_at`, `attempt_count`, `last_error`) with the partial index `(available_at, occurred_at) WHERE published_at IS NULL`, plus `outbox_aggregate_idx`. Add `inbox_events` with `PRIMARY KEY (consumer_name, event_id)`.
- **Run:** `flyway migrate`, then `\d outbox_events`.
- **Observe:** the partial index shows in `\d`. This is your SQL Server *filtered index* under a different name — say both names aloud in the interview.
- **Decision:** `jsonb` payload vs relational columns. Nudge: the blog's rule is `jsonb` for the event body, relational columns for the metadata used in routing and dedup. If you argue for fully relational payloads, name what you lose (flexibility at the cost of a schema per event type).

### Step 7 — `V5` seed

- **Do:** add a seed migration inserting the five medications with fixed UUIDs and deterministic quantities. Fixed UUIDs are the point — later electives and the showcase assert against exact rows.
- **Run:** `flyway migrate`, then `select medication_id, available_quantity from inventory order by medication_id;`.
- **Observe:** the exact five rows, always, from an empty database. Run `ANALYZE inventory;` now — P06 will demand it and stale statistics are a classic surprise.
- **Decision:** seed as a versioned migration vs a separate manual script. Nudge: a migration keeps "reproducible from empty" true by definition, which is the standard the showcase uses; a separate script lets you reseed without a version bump but breaks that guarantee unless documented.

### Step 8 — Clean-room rebuild

- **Do:** drop the database, recreate it, and apply the full migration set from scratch in one pass.
- **Run:** `dropdb -h localhost -U postgres pharmacy && createdb -h localhost -U postgres pharmacy && flyway migrate && psql -d pharmacy -c '\dt'`.
- **Observe:** all seven tables present, seed intact, zero manual intervention. Save this exact transcript — it is the demo for "a reviewer can start PostgreSQL, run the migrations, and understand the schema."

## Try this

Break a migration after it has been applied. Edit `V1__core_workflow_tables.sql` (add a comment, rename a column, anything) and re-run `flyway migrate`.

- **Expected:** Flyway fails with a checksum validation error naming the migration, the expected checksum, and the found one.
- **Paste the error** into your notes. This is the mechanism that keeps deployed schemas auditable — and the exact reason `CREATE TABLE IF NOT EXISTS` in a startup method is not a migration strategy.

Second experiment (if time): `insert into inventory (medication_id, available_quantity) values ('00000000-0000-0000-0000-0000000000ff', -1);` — capture the SQLSTATE `23514` violation. You will meet this error code again in P04, where the design must make it the exception rather than the normal insufficient-stock path.

## Trade-off fork

**Option A — Flyway** (Docker CLI or build plugin): version bookkeeping, checksum validation, baseline/repair tooling, and a standard path into P07's Testcontainers setup and the Spring app's startup.

**Option B — plain numbered `psql` scripts:** zero tooling, trivially understandable by any reviewer, and a pure-SQL story that never hides ordering.

Choose one and write 3–5 lines justifying it, naming what the other lost. The curriculum has no hard constraint here — the blog says "versioned migrations, such as Flyway" — so a psql choice is defensible *if* you can name the guarantee you are giving up (nobody will stop a rogue editor from re-running `V3.sql` after `V4.sql`, and "already applied" is a human memory, not a checksum). The interview payoff is saying exactly which failure each choice prevents, not which brand you prefer.

## Hints

**Hint 1 (mild):** the blog post contains every DDL decision you need — read it once and copy *shapes*, not text. The status CHECK list, the composite keys, and the partial-index predicates are all spelled out in `posts/series-2-postgres/01-schema-design.md`.

**Hint 2 (stronger):** stuck on seed UUIDs — any fixed, readable value works, e.g. `00000000-0000-0000-0000-000000000001` for Amoxicillin through `...05` for Atorvastatin. For the `V1` status CHECK, the Foundation vocabulary is `SUBMITTED, AWAITING_APPROVAL, APPROVED, PACKAGING, READY_FOR_COLLECTION, FULFILLED, REJECTED`; if you copied `READY` from the blog, that is fine as long as you document the rename in the migration file itself.

## Checkpoint / success criteria

You may leave when:

- [ ] A dropped-and-recreated `pharmacy` database reaches all seven tables and the seed via migrations alone (transcript saved).
- [ ] `\d` on every table shows the expected PKs, FKs, and CHECKs.
- [ ] The two shipped indexes exist (`\di`), including the partial `WHERE published_at IS NULL` index.
- [ ] A checksum-violation error from the Try-this is captured in your notes.
- [ ] You can say which of the schema's rules are local validation (Kotlin rejects a blank medication ID) and which are database invariants (a `val` cannot stop two transactions claiming the same inventory).

## Bottleneck & reflection questions

- Which invariants in this schema can *only* be enforced by the database, and which are fine as application checks? (Your `val` can't hold a row lock.)
- Why is a composite PK `(prescription_id, line_number)` better than a global auto-increment on `prescription_items`, and what would SQL Server's clustered-index habit suggest instead — and why doesn't that transfer?
- The `inventory_reservations` table records the *state* of a reservation. What does it deliberately not do, and which elective proves the atomic part?
- If patient identity were owned by another system, which FK would you refuse to add — and what does that decision say about scope discipline in a 2–5 hour challenge?
- An outbox row and a status-history row both describe the same approval. Why are they two tables, and what would go wrong if they were one?

## Handoff

- **Next electives:** `P02_persistence_style_kata.md` (map these rows to Kotlin), then `P03_approve_once_race.md` and `P04_last_unit_inventory.md` (make the transitions safe), `P05_status_history_append.md` (append discipline), `P06_index_and_explain.md` (index the query shapes), `P07_testcontainers_postgres.md` (prove it against real PostgreSQL). P01 is the prerequisite edge for all of them.
- **Showcase:** `../../pharmacy-fulfillment/exercise_02_optimization.md` — Milestone 1 reproduces the Foundation bottlenecks against exactly this schema; your migration + seed is what Exercise 2's "reproducible from an empty database" verification runs against. Also `../../pharmacy-fulfillment/exercise_01_foundation.md` for the state vocabulary.
- **Rabbit handoff:** the `outbox_events` table you created here is the durable handoff `../rabbit/R01_topology_scratchpad.md` will poll. You are not building the relay yet — you are making sure the schema can host it.
- **Interview line you should be able to say aloud:** "The schema is the smallest set of tables whose constraints express the invariants shared across requests — status is a current-state projection, history is append-only evidence, and the outbox record commits with the transition it describes. Migrations are versioned, forward-only, and reproducible from an empty database."

## Optional stretch

On a scratch copy of the database, practice an expand-and-contract migration the way the blog describes: add a nullable column, backfill it, enforce `NOT NULL`, then drop the old form. Do it as two forward-only migrations, not one, and write one sentence on why an already-deployed schema would need the same split. This is the migration answer that separates "runs on my laptop" from "safe in production."
