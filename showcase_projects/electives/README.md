# Electives — Code-Along Program

Electives are **orthogonal on-ramps and deepeners** for the main progressive showcase at [`showcase_projects/pharmacy-fulfillment/`](../pharmacy-fulfillment/exercise_01_foundation.md). They are **not** replacements for [`exercise_01_foundation.md`](../pharmacy-fulfillment/exercise_01_foundation.md), [`exercise_02_optimization.md`](../pharmacy-fulfillment/exercise_02_optimization.md), and [`exercise_03_production.md`](../pharmacy-fulfillment/exercise_03_production.md) — the three showcase exercises remain the spine of the interview story. Each elective is a small, throw-away lab that reuses pharmacy vocabulary (prescription, inventory, pharmacist) without rebuilding the fulfillment product.

**Candidate profile:** Lead Backend Engineer, Java background, targeting Product Engineer roles. Budget: **60 hours** of prep. Every time box below is a **local lab target inside that 60-hour budget** — distinct from the 2–5 hour submission mindset of the original tech challenge (`Product Engineer_ Tech Challenge.md`).

## Why electives exist

| Need | Elective answer |
|---|---|
| Kotlin friction for a Java veteran | Track A (`kotlin/`) — syntax, nullability, sealed types, idiomatic tests |
| RabbitMQ beyond tutorials | Tracks D + F (`rabbit/`, `advanced/`) — ack, prefetch, confirms, DLQ, idempotency, dual-write |
| Postgres concurrency specifics | Tracks C + F (`postgres/`, `advanced/`) — conditional updates, locks, EXPLAIN |
| SSE correctness only where needed | `glue/X03_sse_toy.md`, `advanced/A11_sse_hard_edges.md` — not bolted onto every kata |
| Production judgment + interview defense | Track F (`advanced/`) |

## Code-along legend

Every elective file follows the same structure. Read it as a **guided lab**, not a ticket and not a solution dump:

| Section | What it asks of you |
|---|---|
| **Warm-up** | 2–5 min read (a blog post section) or tiny probe before you write code |
| **Guided steps** | Numbered Do / Run / Observe steps; shapes and stub signatures, never full solutions |
| **Try this** | Deliberate experiment: break it, race it, kill a process, send a duplicate |
| **Trade-off fork** | Two viable options; pick one and write 3–5 lines of justification — this is interview muscle |
| **Hints** | Progressive (Hint 1 mild → Hint 2 stronger). No full file dumps |
| **Checkpoint** | "You may leave when…" — a binary done condition with evidence |
| **Reflection** | 3–5 questions tied to Product Engineer criteria: patient experience, simplicity, system design, failure handling |
| **Handoff** | Next elective, the showcase exercise it unlocks, and the one line you should be able to say aloud |

Kotlin electives call out idioms for Java veterans explicitly ("prefer `val` and expression bodies here because…").

## Catalog

The single source of truth for what exists on disk. All links are repo-relative from `showcase_projects/electives/`.

### Track A — Kotlin (`kotlin/`)

| ID | Title | Time | Wave |
|---|---|---|---|
| K01 | [Prescription value objects](kotlin/K01_prescription_value_objects.md) | ~1h | 1 |
| K02 | [Nullable patient lookup](kotlin/K02_nullable_patient_lookup.md) | ~1–1.5h | 1 |
| K03 | [Workflow state machine](kotlin/K03_workflow_state_machine.md) | ~1.5–2h | 1 |
| K04 | [Inventory pure functions](kotlin/K04_inventory_pure_functions.md) | ~1.5–2h | 2 |
| K05 | [Test data builders](kotlin/K05_test_data_builders.md) | ~1–1.5h | 1 |
| K06 | [Collections and sequences](kotlin/K06_collections_and_sequences.md) | ~1h | 2 |
| K07 | [Extensions and scope functions](kotlin/K07_extensions_and_scope_functions.md) | ~1h | 2 |
| K08 | [Coroutines lite](kotlin/K08_coroutines_lite.md) (optional) | ~2h | 2 |

### Track B — Kotlin + Spring Boot (`spring/`)

| ID | Title | Time | Wave |
|---|---|---|---|
| S01 | [Hello prescription API](spring/S01_hello_prescription_api.md) | ~2h | 1 |
| S02 | [Layered slice](spring/S02_layered_slice.md) | ~2h | 2 |
| S03 | [Error mapping](spring/S03_error_mapping.md) | ~1.5–2h | 2 |
| S04 | [API tests](spring/S04_api_tests.md) | ~2h | 1 |
| S05 | [Config and profiles](spring/S05_config_and_profiles.md) | ~1h | 2 |
| S06 | [Time-box README](spring/S06_timebox_readme.md) | ~1h | 2 |

### Track C — Kotlin + Spring + PostgreSQL (`postgres/`)

| ID | Title | Time | Wave |
|---|---|---|---|
| P01 | [Schema and migrations](postgres/P01_schema_and_migrations.md) | ~2h | 2 |
| P02 | [Persistence style kata](postgres/P02_persistence_style_kata.md) | ~2–3h | 2 |
| P03 | [Approve-once race](postgres/P03_approve_once_race.md) | ~2h | 1 |
| P04 | [Last-unit inventory](postgres/P04_last_unit_inventory.md) | ~2h | 1 |
| P05 | [Status history append](postgres/P05_status_history_append.md) | ~1.5–2h | 2 |
| P06 | [Index and EXPLAIN](postgres/P06_index_and_explain.md) | ~1.5h | 2 |
| P07 | [Testcontainers Postgres](postgres/P07_testcontainers_postgres.md) | ~2h | 2 |

### Track D — Kotlin + RabbitMQ (`rabbit/`)

| ID | Title | Time | Wave |
|---|---|---|---|
| R01 | [Topology scratchpad](rabbit/R01_topology_scratchpad.md) | ~1.5h | 2 |
| R02 | [Fire-and-forget publisher](rabbit/R02_fire_and_forget_publisher.md) | ~1h | 2 |
| R03 | [Manual ack consumer](rabbit/R03_manual_ack_consumer.md) | ~2h | 1 |
| R04 | [Poison to DLQ](rabbit/R04_poison_to_dlq.md) | ~2h | 2 |
| R05 | [Idempotent consumer](rabbit/R05_idempotent_consumer.md) | ~2h | 2 |
| R06 | [Dual-write failure demo](rabbit/R06_dual_write_failure_demo.md) | ~2h | 1 |
| R07 | [Outbox relay mini](rabbit/R07_outbox_relay_mini.md) | ~2.5–3h | 2 |

### Track E — Glue (`glue/`)

| ID | Title | Time | Wave |
|---|---|---|---|
| X01 | [Docker Compose trio](glue/X01_docker_compose_trio.md) | ~1–1.5h | 1 |
| X02 | [Structured logging](glue/X02_structured_logging.md) | ~1h | 2 |
| X03 | [SSE toy](glue/X03_sse_toy.md) | ~2–2.5h | 2 |
| X04 | [Walkthrough script](glue/X04_walkthrough_script.md) | ~1h | 3 |

### Track F — Advanced / production (`advanced/`)

| ID | Title | Time | Wave |
|---|---|---|---|
| A01 | [Poison and parking lot](advanced/A01_poison_and_parking_lot.md) | ~2–3h | 3 |
| A02 | [Backpressure and prefetch](advanced/A02_backpressure_and_prefetch.md) | ~2–3h | 3 |
| A03 | [Outbox at scale (local)](advanced/A03_outbox_at_scale_local.md) | ~2.5–3h | 3 |
| A04 | [Inbox exactly-once effect](advanced/A04_inbox_exactly_once_effect.md) | ~2–3h | 3 |
| A05 | [Ordering keys](advanced/A05_ordering_keys.md) | ~2–3h | 3 |
| A06 | [Saga lite](advanced/A06_saga_lite.md) | ~3h | 3 |
| A07 | [Idempotent HTTP + brokers](advanced/A07_idempotent_http_and_brokers.md) | ~2h | 3 |
| A08 | [Connection and channel lifecycle](advanced/A08_connection_channel_lifecycle.md) | ~2h | 3 |
| A09 | [Postgres under contention](advanced/A09_postgres_under_contention.md) | ~2.5–3h | 3 |
| A10 | [Read models / projections](advanced/A10_read_models_projections.md) | ~2.5–3h | 3 |
| A11 | [SSE hard edges](advanced/A11_sse_hard_edges.md) | ~2.5–3h | 3 |
| A12 | [Observability slice](advanced/A12_observability_slice.md) | ~2h | 3 |
| A13 | [Chaos drill script](advanced/A13_chaos_drill_script.md) | ~2–3h | 3 |
| A14 | [Cut-line architecture](advanced/A14_cut_line_architecture.md) | ~1.5–2h | 3 |
| A15 | [Security baselines](advanced/A15_security_baselines.md) | ~2h | 3 |

## Recommended waves

**Wave 1 (start here — the core loop, ~15h):**
K01 → K02 → K03 → K05, then S01 → S04, then **P03 or P04**, then R03 → R06.

**Wave 2 (fill gaps, ~25h):** remaining A–E electives as needed. Prioritize the ones that attack your known weak spots; everything in Wave 1 is mandatory, the rest of Wave 2 is a menu.

**Wave 3 (advanced, ~20h):** among Track F, do **A04, A03, A09, A11, A13 first**, then the rest of F. Finish with A12–A15 and X04 for interview polish.

Total: 60 hours. Time boxes are lab targets inside the 60-hour budget, **not** an approximation of the 2–5 hour challenge submission.

## Dependency edges that matter

- **K01 → K02 → K03:** strict order — value objects, then nullability, then sealed states builds on both.
- **X01 early:** Docker Compose trio is the infra prerequisite — run it before any elective that needs Postgres or Rabbit (S01 onward, all of C, D, F).
- **R06 before R07:** dual-write failure demo motivates the outbox; R07 implements the fix.
- **P03 before A09:** approve-once race is the baseline; `A09` (deadlocks, lock order, `SKIP LOCKED`) assumes you have the evidence workflow from P03.
- **A03 / A04 / A09 / A11 / A13 first among advanced:** each is the "at scale" version of a core story (outbox, idempotency, Postgres contention, SSE, chaos) — they pay for themselves; the rest of F deepens.

## Placement vs the showcase

| Before / during | Electives |
|---|---|
| Before Ex1 | Track A (K01–K08), Track B (S01–S06), X01 |
| Before Ex2 | Track C (P01–P07) |
| Before / during Ex3 | Track D (R01–R07), X02, X03, selected Track F (A01–A11) |
| Interview polish | A12–A15, X04 |

## Elective → showcase mapping

| Prepares | Electives |
|---|---|
| `exercise_01_foundation.md` | K01–K08, S01–S06, X01 |
| `exercise_02_optimization.md` | P01–P07 |
| `exercise_03_production.md` | R01–R07, X02, X03, A01–A11 |
| Interview polish | A12–A15, X04 |

## How to use with the blog posts

Each elective ties to concrete posts under `posts/` (links are inside each file's **Blog & curriculum links** section). The post is the theory; the elective is the hands-on. Per track:

| Track | Posts |
|---|---|
| A — Kotlin | `posts/series-1-kotlin/` (01–04) |
| B — Spring | `posts/series-4-product-sse/01`, `posts/series-1-kotlin/` |
| C — Postgres | `posts/series-2-postgres/` (01–05) |
| D — Rabbit | `posts/series-3-rabbitmq/` (01–05) |
| E — Glue | `posts/series-4-product-sse/` (02–04), `posts/series-3-rabbitmq/06`, `posts/series-5-interview/01` |
| F — Advanced | `posts/series-3-rabbitmq/`, `posts/series-2-postgres/`, `posts/series-4-product-sse/`, `posts/series-5-interview/` |

For every elective: read the tied post section first (that's the warm-up), then code the lab, then re-read the post — you'll understand the "why" the second time.

## Rules of engagement

- **No solution repos required.** Electives are throw-away labs; create empty Gradle/Maven skeletons yourself when a step asks for one. Your artifacts are the evidence (test output, row counts, EXPLAIN plans, log lines), not checked-in code.
- **Keep a trade-off log.** Every fork (sealed Result vs exception, `FOR UPDATE` vs conditional `UPDATE`, ack-after-work vs ack-after-commit, JPA vs JDBC, …) ends with *"pick one and write 3–5 lines justifying it."* Collect those notes — they are your interview rehearsal material for `posts/series-5-interview/02-tradeoffs.md` style questions.
- **Evidence over vibes.** Race electives (P03, P04, A09) require reproducible evidence. If you can't show the failure, you haven't done the lab.
- **Honest framing.** Never claim exactly-once *delivery* — only exactly-once *effect* under at-least-once delivery. Never claim production capacity from a local lab.
