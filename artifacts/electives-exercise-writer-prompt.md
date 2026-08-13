# Exercise Writer Prompt: Kotlin Interview Electives (Code-Along Track)

## Context

You are acting as a technical exercise designer for an engineering blog series focused on **Kotlin** and **Spring Boot, RabbitMQ, PostgreSQL, REST, SSE, Docker Compose** in the context of **pharmacy and healthcare fulfillment**.

The reader is a **Lead Backend Engineer** preparing for **Lead**-level interviews for **Product Engineer** positions.

### Candidate profile (authoritative)

Read before writing:

- `me/common-params.md` — calibration parameters
- `artifacts/coach-assessment.md` — diagnostic gaps, strengths, 60-hour strategy
- `artifacts/blog-plan.md` — full curriculum and knowledge blocks
- `posts/` — written articles for series 1–5 (link concrete posts in each elective)
- `Product Engineer_ Tech Challenge.md` — original challenge constraints (2–5 hour submission mindset)
- `showcase_projects/pharmacy-fulfillment/` — main progressive showcase (Ex1–3). **Do not rewrite or replace these.** Electives are orthogonal on-ramps and deepeners.

### Framing

- Primary language: **Kotlin for an experienced Java backend engineer**
- Messaging: RabbitMQ
- Persistence: PostgreSQL
- Application framework: Spring Boot
- API: REST; SSE only where an elective explicitly requires it
- Local infrastructure: Docker Compose
- Domain vocabulary may reuse pharmacy terms (prescription, inventory, pharmacist) for transfer, but electives must **not** become a second full fulfillment product

### High-risk gaps electives must burn down

1. Kotlin beginner friction (syntax, nullability, sealed types, idiomatic tests)
2. RabbitMQ beyond tutorials (ack, prefetch, confirms, DLQ, idempotency, dual-write)
3. PostgreSQL-specific concurrency (conditional updates, locks, plans)
4. SSE correctness only in dedicated electives (not bolted onto every kata)
5. Production judgment and interview defense (Track F)

---

## Task

Design a full **elective code-along program**: many small, self-contained exercise files that guide the reader through topics with hands-on steps, experiments, hints, and trade-off forks.

You are **not** building the services. You are writing structured exercise descriptions that another engineer (the candidate) will code along with.

### Output directory

Create all files under:

```
showcase_projects/electives/
```

### Directory layout (create as you go)

```
showcase_projects/electives/
  README.md                          # program map, waves, how to use code-alongs
  kotlin/
    K01_prescription_value_objects.md
    K02_nullable_patient_lookup.md
    K03_workflow_state_machine.md
    K04_inventory_pure_functions.md
    K05_test_data_builders.md
    K06_collections_and_sequences.md
    K07_extensions_and_scope_functions.md
    K08_coroutines_lite.md
  spring/
    S01_hello_prescription_api.md
    S02_layered_slice.md
    S03_error_mapping.md
    S04_api_tests.md
    S05_config_and_profiles.md
    S06_timebox_readme.md
  postgres/
    P01_schema_and_migrations.md
    P02_persistence_style_kata.md
    P03_approve_once_race.md
    P04_last_unit_inventory.md
    P05_status_history_append.md
    P06_index_and_explain.md
    P07_testcontainers_postgres.md
  rabbit/
    R01_topology_scratchpad.md
    R02_fire_and_forget_publisher.md
    R03_manual_ack_consumer.md
    R04_poison_to_dlq.md
    R05_idempotent_consumer.md
    R06_dual_write_failure_demo.md
    R07_outbox_relay_mini.md
  glue/
    X01_docker_compose_trio.md
    X02_structured_logging.md
    X03_sse_toy.md
    X04_walkthrough_script.md
  advanced/
    A01_poison_and_parking_lot.md
    A02_backpressure_and_prefetch.md
    A03_outbox_at_scale_local.md
    A04_inbox_exactly_once_effect.md
    A05_ordering_keys.md
    A06_saga_lite.md
    A07_idempotent_http_and_brokers.md
    A08_connection_channel_lifecycle.md
    A09_postgres_under_contention.md
    A10_read_models_projections.md
    A11_sse_hard_edges.md
    A12_observability_slice.md
    A13_chaos_drill_script.md
    A14_cut_line_architecture.md
    A15_security_baselines.md
```

Filenames above are mandatory unless a collision forces a trivial rename—keep the `K01_`, `S01_`, … prefixes stable for cross-links.

---

## MANDATORY: Use subagents as you write

You **must** parallelize and stage the writing work with **subagents** (Task tool / explore or general agents). Do not write all ~47 files in one undifferentiated pass in a single context if subagents are available.

### Required subagent workflow

1. **Planner subagent (once)**  
   - Read `artifacts/blog-plan.md`, `artifacts/coach-assessment.md`, and skim `showcase_projects/pharmacy-fulfillment/exercise_0*.md` headers.  
   - Return a link map: each elective ID → primary/secondary `posts/...` paths and which showcase exercise it prepares or deepens.  
   - Return any naming or dependency edges (e.g. R06 before R07, P03 before A09).

2. **README subagent**  
   - Write `showcase_projects/electives/README.md` using the program map below (waves, code-along legend, how electives relate to Ex1–3).

3. **Track subagents (one track per subagent, can run multiple in parallel)**  
   - **Track A** → all `kotlin/K*.md`  
   - **Track B** → all `spring/S*.md`  
   - **Track C** → all `postgres/P*.md`  
   - **Track D** → all `rabbit/R*.md`  
   - **Track E** → all `glue/X*.md`  
   - **Track F** → all `advanced/A*.md`  

   Each track subagent receives:
   - This full prompt (or the track slice + global format rules)
   - The planner’s link map for that track
   - Instruction: code-along format only; no solution dumps; fill every section

4. **Reviewer subagent (after each track or after all tracks)**  
   - Check every file for: required sections present, blog links real, time boxes sane, trade-off forks non-trivial, no full solution code, cross-links to next elective / showcase exercise, consistent tone for Lead→Product Engineer.  
   - Return a punch list; the parent writer fixes gaps (may spawn fix-up subagents per track).

5. **Integration pass (parent)**  
   - Ensure README wave lists match files on disk.  
   - Ensure relative links between electives resolve.  
   - Spot-check 3 files per track against Format requirements below.

### Subagent prompt template (use when spawning)

```
You are writing code-along elective exercises for a Lead Backend Engineer learning Kotlin/Spring/Rabbit/Postgres for a Product Engineer pharmacy challenge.

Rules:
- Follow the Format for Each Elective File exactly
- Code-along style: guide, try-this, hints, trade-off forks — NOT bare specs and NOT full solutions
- Tie to concrete posts under posts/ from the link map
- Orthogonal to showcase_projects/pharmacy-fulfillment (reuse domain words, do not rebuild Ex1–3)
- No cloud services, no full frontend, no microservice fleet
- Target vocabulary: experienced Java backend engineer new to Kotlin where relevant

Write these files: [list paths]
For each elective use this brief: [paste row from catalog]
```

### Parallelism guidance

- Prefer **one subagent per track** writing all files in that track.  
- Tracks A–E may run **in parallel** after the planner finishes.  
- Track F may run in parallel with A–E **after** planner link map exists (F references Ex3/R/P concepts).  
- If context limits hit, split a track into batches of 3–4 files per subagent, still labeled by elective ID.

---

## Program map (do not drop electives)

### Track A — Kotlin only

| ID | Title | Objective | Time | Blog tie (minimum) |
|----|-------|-----------|------|--------------------|
| K1 | Prescription value objects | data class, val/var, equality, copy, require | ~1h | series-1-kotlin/01 |
| K2 | Nullable patient lookup | ?, safe calls, Elvis, sealed Result vs exceptions | ~1–1.5h | series-1-kotlin/02 |
| K3 | Workflow state machine | sealed states, exhaustive when, illegal transitions | ~1.5–2h | series-1-kotlin/03 |
| K4 | Inventory pure functions | reserve/release, multi-line meds, pure + tests | ~1.5–2h | series-1-kotlin/04 |
| K5 | Test data builders | fixtures, parameterized tests, light mocking | ~1–1.5h | series-1-kotlin/04 |
| K6 | Collections and sequences | map/filter/groupBy; avoid Java-stream cargo cult | ~1h | series-1-kotlin/01 |
| K7 | Extensions and scope functions | let/also/apply/run; when not to use | ~1h | series-1-kotlin/01 |
| K8 | Coroutines lite (optional) | suspend, structured concurrency basics | ~2h | only if justified; mark optional |

### Track B — Kotlin + Spring Boot

| ID | Title | Objective | Time | Blog tie |
|----|-------|-----------|------|----------|
| S1 | Hello prescription API | POST + GET, DTOs, validation | ~2h | series-4-product-sse/01 |
| S2 | Layered slice | controller → service → in-memory repo; DI | ~2h | S1 + series-1 |
| S3 | Error mapping | sealed domain errors → HTTP 4xx/409 | ~1.5–2h | series-1-kotlin/02, series-4/01 |
| S4 | API tests | SpringBootTest / MockMvc or WebTestClient | ~2h | series-1-kotlin/04 |
| S5 | Config and profiles | yml, test profile, no secrets | ~1h | practical Spring |
| S6 | Time-box README | document 2h vs 5h cut line on tiny API | ~1h | series-4-product-sse/04 |

### Track C — Kotlin + Spring + PostgreSQL

| ID | Title | Objective | Time | Blog tie |
|----|-------|-----------|------|----------|
| P1 | Schema and migrations | prescriptions + inventory, seed meds | ~2h | series-2-postgres/01 |
| P2 | Persistence style kata | pick ONE of JDBC/jOOQ/JPA; map rows ↔ Kotlin | ~2–3h | series-2-postgres/01 |
| P3 | Approve-once race | conditional UPDATE; rows=1 | ~2h | series-2-postgres/02 |
| P4 | Last-unit inventory | atomic decrement / FOR UPDATE; two winners race | ~2h | series-2-postgres/03 |
| P5 | Status history append | current state + event log | ~1.5–2h | series-2-postgres/01–02 |
| P6 | Index and EXPLAIN | slow query → index; capture plan | ~1.5h | series-2-postgres/04 |
| P7 | Testcontainers Postgres | real DB integration tests | ~2h | series-2-postgres/05 |

### Track D — Kotlin + RabbitMQ

| ID | Title | Objective | Time | Blog tie |
|----|-------|-----------|------|----------|
| R1 | Topology scratchpad | exchange, queue, binding, durable flags | ~1.5h | series-3-rabbitmq/01 |
| R2 | Fire-and-forget publisher | publish JSON; management UI | ~1h | series-3-rabbitmq/01–02 |
| R3 | Manual ack consumer | prefetch, ack after work, nack once | ~2h | series-3-rabbitmq/03 |
| R4 | Poison to DLQ | fail N times → DLQ; headers | ~2h | series-3-rabbitmq/04 |
| R5 | Idempotent consumer | messageId + processed store | ~2h | series-3-rabbitmq/05 |
| R6 | Dual-write failure demo | DB then publish; kill in gap; document loss | ~2h | series-3-rabbitmq/02 |
| R7 | Outbox relay mini | outbox row + poller + publisher confirms | ~2.5–3h | series-3-rabbitmq/02 |

### Track E — Glue

| ID | Title | Objective | Time | Blog tie |
|----|-------|-----------|------|----------|
| X1 | Docker Compose trio | app + Postgres + Rabbit; healthchecks | ~1–1.5h | local infra |
| X2 | Structured logging | correlation id submit → consumer | ~1h | ops hygiene |
| X3 | SSE toy | one patient stream, event id, Last-Event-ID | ~2–2.5h | series-4-product-sse/02–03 |
| X4 | Walkthrough script | 10-min oral demo of one elective | ~1h | series-5-interview/01 |

### Track F — Advanced / production

| ID | Title | Objective | Time | Blog tie |
|----|-------|-----------|------|----------|
| A1 | Poison and parking lot | retryable vs permanent; DLQ replay | ~2–3h | series-3-rabbitmq/04 |
| A2 | Backpressure and prefetch | saturate; tune; measure lag | ~2–3h | series-3-rabbitmq/03 |
| A3 | Outbox at scale (local) | batch relay, backoff, stuck rows, metrics | ~2.5–3h | series-3-rabbitmq/02 |
| A4 | Inbox exactly-once effect | at-least-once delivery, exactly-once effect | ~2–3h | series-3-rabbitmq/05 |
| A5 | Ordering keys | per-prescription order vs throughput | ~2–3h | series-3-rabbitmq/05 |
| A6 | Saga lite | reserve→approve→package compensations | ~3h | workflow + messaging |
| A7 | Idempotent HTTP + brokers | Idempotency-Key aligned with message ids | ~2h | API + series-3/05 |
| A8 | Connection and channel lifecycle | shared connection, channel rules, reconnect | ~2h | series-3-rabbitmq ops |
| A9 | Postgres under contention | deadlocks, lock order, SKIP LOCKED | ~2.5–3h | series-2-postgres/02–03 |
| A10 | Read models / projections | status projection; rebuild from log | ~2.5–3h | series-4 + postgres |
| A11 | SSE hard edges | isolation, slow consumer, heartbeat, authz | ~2.5–3h | series-4-product-sse/02–03 |
| A12 | Observability slice | trace id E2E; lag/error signals | ~2h | interview defense |
| A13 | Chaos drill script | kill mid-publish, broker restart; runbook | ~2–3h | series-3 + interview |
| A14 | Cut-line architecture | 2h vs 5h path documented on a small system | ~1.5–2h | series-4-product-sse/04 |
| A15 | Security baselines | patient scoping, least-privilege DB, no PII in logs | ~2h | healthcare bar |

### Recommended waves (document in README)

- **Wave 1 (start here):** K1, K2, K3, K5 → S1, S4 → P3 or P4 → R3, R6  
- **Wave 2:** remaining A–E electives as needed  
- **Wave 3:** A4, A3, A9, A11, A13 first among advanced; then rest of F  
- **Placement vs showcase:** K/S/X1 before Ex1; P* before Ex2; R* and selected F before/during Ex3; A12–A15 and X4 for interview polish  

---

## Code-along pedagogy (non-negotiable)

Every elective must feel like a **guided lab**, not a bare ticket and not a tutorial that pastes the full solution.

### Required teaching moves

1. **Warm-up** — 2–5 minutes: read a short blog section or run a tiny probe.  
2. **Guided steps** — numbered; each step says what to implement, what to run, what to observe.  
3. **Try this** — at least one deliberate experiment (break it, race it, kill the process, send a duplicate).  
4. **Trade-off fork** — at least one point with **two viable options**; reader picks one and writes 3–5 lines justifying the choice (interview muscle). Examples: sealed Result vs exception; FOR UPDATE vs conditional UPDATE; ack-after-work vs ack-after-commit; JPA vs JDBC for this kata.  
5. **Hints** — separate subsection; progressive (Hint 1 mild → Hint 2 stronger). No full file dumps in hints.  
6. **Checkpoint** — binary/clear “you may leave when…” criteria.  
7. **Reflection** — 3–5 questions tying to Product Engineer criteria: patient experience, simplicity, system design, failure handling.  
8. **Handoff** — next elective and/or which showcase exercise this unlocks (`exercise_01_foundation.md`, etc.).

### Code in the exercise files

- **Allowed:** small signatures, stub outlines, SQL sketches, config keys, example log lines, “your test might look like this” **shapes** with `// ...` gaps.  
- **Forbidden:** complete working solutions, full service classes, copy-paste-pass projects.  
- Prefer **Kotlin idioms** called out explicitly for Java veterans (“prefer `val` and expression bodies here because…”).

### Constraints shared by all electives

- Self-contained; throwable away; one primary learning objective.  
- No second showcase workflow (no full submit→inventory→pharmacist→package→fulfill product).  
- No cloud, no managed broker/DB, no full staff UI.  
- Local targets only; never claim production capacity.  
- Never claim exactly-once **delivery**; A4 must teach exactly-once **effect** under at-least-once delivery.  
- SSE electives (X3, A11) must **not** make each SSE connection a Rabbit competing consumer.  
- Time boxes are local lab targets inside the 60-hour prep, distinct from the 2–5 hour challenge submission.

---

## Format for Each Elective File

Use this structure exactly (heading titles may add the elective title after the em dash):

```markdown
# [ID] [Title] — Code-Along Elective

## Objective
What you will build and learn. One primary objective.

## Time box
Approximate duration. Optional vs core (especially K8).

## Prerequisites
Prior electives, tools (JDK, Docker, etc.), and showcase position (e.g. "before Exercise 1").

## Blog & curriculum links
- Concrete paths under `posts/...`
- Optional: coach-assessment gap this attacks

## Background & motivation
Why this kata exists. What it deliberately ignores.

## Learning objectives
Bullet list of 3–6 concrete skills.

## Warm-up
2–5 min read or probe before coding.

## System specification
- Scope in / scope out
- Functional requirements (minimal)
- Constraints (in-memory vs Postgres vs Rabbit, single module, etc.)

## Step-by-step code-along
Numbered steps. Each step includes:
- **Do:** what to implement
- **Run:** command or test to execute
- **Observe:** what good looks like
- **Decision (if any):** micro-choice with a nudge, not a spoiler

## Try this
At least one experiment that creates failure, a race, or a surprising observation.

## Trade-off fork
Present Option A vs Option B (or A/B/C). Instruct the reader to choose and write a short justification.
Do not declare a single official winner unless the curriculum has a hard constraint—and even then, name the lost benefits.

## Hints
Progressive hints. No full solutions.

## Checkpoint / success criteria
Clear done-when list.

## Bottleneck & reflection questions
Questions that expose limits and set up the next elective or showcase exercise.

## Handoff
- Next elective(s)
- Related showcase exercise (`showcase_projects/pharmacy-fulfillment/...`)
- Interview line the candidate should be able to say aloud

## Optional stretch
One harder twist for readers with spare time (still no full second project).
```

---

## Format for `showcase_projects/electives/README.md`

Must include:

1. Purpose of electives vs `pharmacy-fulfillment`  
2. Code-along legend (warm-up, try-this, trade-off fork, hints)  
3. Full catalog table (ID, title, time, track, wave)  
4. Recommended Wave 1 / 2 / 3 paths  
5. Mapping table: elective → prepares showcase Ex1 / Ex2 / Ex3 / interview  
6. How to use with the blog posts  
7. Rules: no solution repos required; keep notes of trade-off choices for interview rehearsal  

---

## Quality bar

- Tone: concise, senior peer, interview-aware — not hand-holdy junior tutorial prose.  
- Every trade-off fork must be realistic for a Product Engineer discussion.  
- Every Rabbit elective must reinforce at-least-once + idempotent effects where relevant.  
- Every Postgres race elective must require **evidence** (test output, row counts, or EXPLAIN), not vibes.  
- Track F electives should reference “you already saw X in R7/Ex3; now prove Y” rather than rebuilding fulfillment.  
- Cross-link liberally using repo-relative paths.

---

## Constraints (writer)

- DO use subagents as specified in **MANDATORY: Use subagents as you write**.  
- DO create every file listed in the directory layout.  
- DO NOT modify `showcase_projects/pharmacy-fulfillment/*` except to add outbound links from electives if needed (prefer linking toward showcase, not editing showcase).  
- DO NOT write solution code repositories unless a step asks the reader to create an empty gradle/maven skeleton themselves.  
- DO NOT claim electives replace the main three exercises.  
- STOP when all elective markdown files + README exist and the reviewer punch list is empty.

---

## Definition of done

- [ ] `showcase_projects/electives/README.md` complete  
- [ ] All K1–K8, S1–S6, P1–P7, R1–R7, X1–X4, A1–A15 files present  
- [ ] Each file matches the Format section  
- [ ] Subagent workflow was used (planner → track writers → reviewer → integration)  
- [ ] Blog links point at existing `posts/` paths from `artifacts/blog-plan.md`  
- [ ] Wave 1 path is obvious to a candidate opening the README alone  
