# Exercise Writer Prompt: Track Capstones (Code-Along Mini-Projects)

## Context

You are acting as a technical exercise designer for an engineering blog series focused on **Kotlin** and **Spring Boot, RabbitMQ, PostgreSQL, REST, SSE, Docker Compose** in the context of interview prep for **pharmacy and healthcare fulfillment** — but these **capstones deliberately use non-pharmacy geek domains** so they stay orthogonal to the main showcase.

The reader is a **Lead Backend Engineer** preparing for **Lead**-level interviews for **Product Engineer** positions.

### Authoritative sources (read before writing)

- `me/common-params.md` — calibration
- `artifacts/coach-assessment.md` — gaps and 60-hour strategy
- `artifacts/blog-plan.md` — curriculum / posts map
- `artifacts/electives-exercise-writer-prompt.md` — pedagogy and format contract for electives
- `showcase_projects/electives/README.md` — existing elective catalog and waves
- `showcase_projects/electives/{kotlin,spring,postgres,rabbit,glue,advanced}/` — **all prior electives**; capstones must require skills those files taught
- `showcase_projects/pharmacy-fulfillment/` — main Ex1–3 spine; **do not rewrite**; only link as handoff targets
- `posts/` — concrete blog paths for warm-ups and reflection

### Framing

- Capstones are **code-along mini-projects**: the reader **creates a real new project** from zero under a guided path.
- Same teaching style as electives: warm-up, guided steps, try-this, trade-off forks, progressive hints, checkpoints, reflection — **not** bare tickets and **not** full solution dumps.
- Each capstone sits **after** its track’s electives and forces reuse of **nearly everything that track touched**.
- Domains are **geek / nerd / small-cool** — not a second prescription fulfillment system.

---

## Task

Write **6 track capstone** code-along files and **update** the electives README to include the capstone program.

You are **not** implementing the projects. You write the exercise markdown that guides the candidate to build them.

### Output paths (mandatory)

```
showcase_projects/electives/kotlin/CAPSTONE_KC_dungeon_dice_oracle.md
showcase_projects/electives/spring/CAPSTONE_SC_tiny_status_cafe.md
showcase_projects/electives/postgres/CAPSTONE_PC_seathold_arena.md
showcase_projects/electives/rabbit/CAPSTONE_RC_courier_packet_relay.md
showcase_projects/electives/glue/CAPSTONE_XC_observatory_desk.md
showcase_projects/electives/advanced/CAPSTONE_AC_nightshift_incident_lab.md
```

Update in place:

```
showcase_projects/electives/README.md
```

Optional (only if useful): add a short `showcase_projects/electives/projects/README.md` explaining that **the candidate** creates project dirs here (writer does **not** scaffold Gradle apps).

### Where the reader’s code will live (document in every capstone)

Instruct the reader to create:

```
showcase_projects/electives/projects/<slug>/
```

| Capstone | slug |
|----------|------|
| KC | `dungeon-dice-oracle` |
| SC | `tiny-status-cafe` |
| PC | `seathold-arena` |
| RC | `courier-packet-relay` |
| XC | `observatory-desk` |
| AC | `nightshift-incident-lab` |

---

## MANDATORY: Use subagents as you write

Do **not** write all six capstones in one undifferentiated solo pass if subagents are available.

### Required workflow

1. **Planner subagent (once)**  
   - Skim every elective in the six tracks (at least titles + objectives + checkpoints).  
   - Produce a **skill coverage matrix**: capstone ID → list of elective IDs that must appear as explicit “you must touch” checklist items.  
   - Flag any elective that is easy to skip and how the capstone forces it (e.g. K08 optional stretch vs required milestone).  
   - Propose milestone breakdown (M1–M4) per capstone.  
   - Return concrete `posts/...` links per capstone warm-up.

2. **README updater subagent**  
   - Patch `showcase_projects/electives/README.md`: new “Track capstones” section, catalog rows, wave placement (capstones after track labs; AC in wave 3), projects/ directory convention, skill-checklist rule.

3. **Six capstone subagents (parallel after planner)**  
   - One subagent per capstone file.  
   - Each receives: this prompt’s capstone brief for that ID, the skill matrix rows, format rules, and “no solution dump”.

4. **Reviewer subagent**  
   - Verify: new project scaffold steps exist; skill checklist maps to track electives; code-along sections complete; try-this + ≥1 trade-off fork; time box sane; no pharmacy-fulfillment clone; no full solutions; handoff to Ex1/Ex2/Ex3/interview; cross-links to prior electives resolve.  
   - Return punch list; parent fixes (or spawn fix-up subagents).

5. **Integration pass (parent)**  
   - README links match files on disk.  
   - Each track’s last electives can optionally gain a one-line footer “Capstone: see CAPSTONE_…” **only if** you touch those files lightly — prefer README + capstone Prerequisites over mass-editing all electives.  
   - Do **not** modify `showcase_projects/pharmacy-fulfillment/*` bodies except if you must fix a broken link (prefer not).

### Subagent prompt template

```
You are writing a track CAPSTONE code-along for a Lead Backend Engineer (Java → Kotlin) prepping for Product Engineer interviews.

Rules:
- Follow "Format for Each Capstone File" exactly
- Reader creates a NEW project under showcase_projects/electives/projects/<slug>/
- Force skills from these electives: [matrix row]
- Geek domain as specified — NOT pharmacy fulfillment
- Code-along: warm-up, milestones, try-this, trade-off forks, hints — NO full solution code
- Allow stub signatures / shapes with // ... gaps only
- Local only; no cloud; no full frontend
- Tie reflection to patient experience / simplicity / system design / failure handling by analogy where useful

Write: [path]
Brief: [paste capstone brief]
```

---

## Capstone briefs (do not drop or rename IDs)

### KC — Dungeon Dice Oracle (Track A: Kotlin)

- **File:** `kotlin/CAPSTONE_KC_dungeon_dice_oracle.md`
- **Project slug:** `dungeon-dice-oracle`
- **Time box:** ~4–6h (K08 coroutines combat tick may be stretch +1–2h)
- **Stack:** Pure Kotlin CLI (or thin main); **no** Spring, **no** DB, **no** Rabbit unless as a joke stub — stay on-track
- **Product fantasy:** A tabletop nerd’s dice oracle: parse expressions like `2d6+3`, `d20`, advantage/disadvantage; sealed outcome trees (crit / hit / miss / fumble); loot tables; a tiny DSL via extensions (`d20.advantage()`); tests with builders; optional coroutine multi-round “combat tick” simulator printing a play-by-play
- **Must force:** K01 value objects & validation, K02 null/Result vs exceptions at parse boundaries, K03 sealed state/outcome machines, K04 pure functions (damage/loot math), K05 test builders + parameterized tests, K06 collections/sequences for loot weighting, K07 extensions/scope for DSL ergonomics, K08 optional structured concurrency for rounds
- **Try-this ideas:** malformed dice strings; weighted loot fairness simulation; illegal combat state transitions
- **Trade-off forks (pick ≥2 across milestones):** sealed Result vs exceptions for parse errors; eager list vs sequence for large loot sims; DSL extensions vs plain functions
- **Showcase handoff:** idiomatic Kotlin domain modeling before `exercise_01_foundation.md`
- **Interview line:** “I model illegal states as unrepresentable and keep pure domain logic testable without a container.”

### SC — Tiny Status Café (Track B: Spring)

- **File:** `spring/CAPSTONE_SC_tiny_status_cafe.md`
- **Project slug:** `tiny-status-cafe`
- **Time box:** ~5–7h
- **Stack:** Kotlin + Spring Boot + REST; in-memory repo OK (Postgres optional only as stretch — do not require Track C)
- **Product fantasy:** A coffee-cart status board: customer places order; barista marks in-progress / ready / picked-up / cancelled; GET status by order id + simple “board” list; problem-details style errors; test + dev profiles; serious test suite; README with explicit 2h vs 5h cut-line for a fake take-home
- **Must force:** S01 POST/GET + validation DTOs, S02 controller→service→repo layering + DI, S03 sealed domain errors → HTTP mapping, S04 API tests (MockMvc/WebTestClient), S05 config/profiles/secrets hygiene, S06 time-box README as a deliverable artifact
- **Try-this ideas:** double-complete the same order; invalid transition; fail a test on purpose then fix; run under test profile with different port/logging
- **Trade-off forks:** exception handler vs Result-returning service at API boundary; 409 vs 422 for illegal transitions; board as query on orders vs separate read model (keep simple — discuss only)
- **Showcase handoff:** patient-first API instincts before Ex1; simplicity scoring
- **Interview line:** “I ship a thin vertical with explicit error contracts and a written cut-line before I decorate.”

### PC — Seathold Arena (Track C: Postgres)

- **File:** `postgres/CAPSTONE_PC_seathold_arena.md`
- **Project slug:** `seathold-arena`
- **Time box:** ~6–8h
- **Stack:** Kotlin + Spring Boot + PostgreSQL + migrations + Testcontainers; Docker required
- **Product fantasy:** General-admission concert holds: create event with N seats; place hold; confirm hold → ticket; expire/release hold; no oversell under concurrent clients; hold/ticket history; EXPLAIN on “list active holds by event”; integration tests on real Postgres
- **Must force:** P01 schema/migrations/seed, P02 one persistence style only (JDBC **or** jOOQ **or** JPA — trade-off fork at start), P03 confirm-once / conditional transitions, P04 last-seat / last-unit style race, P05 history append, P06 index + EXPLAIN evidence in notes, P07 Testcontainers
- **Try-this ideas:** two threads confirm the last seat; double-confirm same hold; sequential scan before/after index
- **Trade-off forks:** FOR UPDATE vs conditional UPDATE for seat claim; isolation level choice; persistence library choice (bind for rest of capstone)
- **Showcase handoff:** concurrency evidence before `exercise_02_optimization.md`
- **Interview line:** “I prove inventory-like invariants with real database races and plans, not in-memory hope.”

### RC — Courier Packet Relay (Track D: Rabbit)

- **File:** `rabbit/CAPSTONE_RC_courier_packet_relay.md`
- **Project slug:** `courier-packet-relay`
- **Time box:** ~6–8h
- **Stack:** Kotlin + Spring Boot + RabbitMQ (+ small Postgres or file/DB table for outbox/inbox as needed); Docker Compose
- **Product fantasy:** Neighborhood courier drop network: shipper submits packet; dispatcher publishes “pickup requested”; courier consumer accepts/acks; poison packages go DLQ; duplicate delivery must not double-deliver to customer; show dual-write loss; then fix path with outbox + publisher confirms
- **Must force:** R01 topology, R02 publisher, R03 manual ack + prefetch, R04 DLQ/poison, R05 idempotent consumer, R06 dual-write failure demo with evidence, R07 outbox relay + confirms
- **Try-this ideas:** kill process between DB commit and publish; redeliver same messageId; message that always throws
- **Trade-off forks:** topic vs direct exchange for neighborhood zones; ack-after-work vs ack-after-DB-commit; outbox poller interval vs latency
- **Never claim:** exactly-once **delivery** — only safe **effects**
- **Showcase handoff:** reliability path before/during `exercise_03_production.md`
- **Interview line:** “I demo the dual-write hole, then close it with outbox and idempotent consumers under at-least-once delivery.”

### XC — Observatory Desk (Track E: Glue)

- **File:** `glue/CAPSTONE_XC_observatory_desk.md`
- **Project slug:** `observatory-desk`
- **Time box:** ~5–7h
- **Stack:** Docker Compose trio (app + Postgres + Rabbit), structured logging with correlation IDs, SSE “mission clock” or telemetry stream, walkthrough script as deliverable
- **Product fantasy:** A tiny mission-control desk: operators submit a “observation run”; work hops through a queue; logs carry one correlation id end-to-end; SSE streams run status ticks with event ids + Last-Event-ID reconnect; finish with a 10-minute oral walkthrough script (markdown) you could use in interview
- **Must force:** X01 compose healthchecks, X02 correlation/structured logs across HTTP→DB→publish→consume, X03 SSE toy correctness (ids, replay, isolation — **not** one Rabbit consumer per SSE connection), X04 walkthrough script quality bar
- **Try-this ideas:** reconnect SSE mid-run; grep logs for one correlation id across services; break healthcheck and watch compose
- **Trade-off forks:** log JSON vs key=value; SSE backed by DB projection vs in-memory bus for this lab; what belongs in 10-min demo vs appendix
- **Showcase handoff:** local demo discipline + SSE habits before Ex3 SSE; interview walkthrough series
- **Interview line:** “I can trace one request across async boundaries and demo reconnect-safe patient-style status without coupling SSE to competing consumers.”

### AC — Nightshift Incident Lab (Track F: Advanced)

- **File:** `advanced/CAPSTONE_AC_nightshift_incident_lab.md`
- **Project slug:** `nightshift-incident-lab`
- **Time box:** ~8–12h (allow multi-session)
- **Stack:** Kotlin + Spring + Postgres + Rabbit + SSE projection pieces as needed; local chaos only
- **Product fantasy:** You inherit a “mostly works” nightshift pipeline for a fictional game studio build-artifact courier (or similar geek ops domain). Your job is not greenfield product — it is **incident lab**: parking lot for poison, prefetch saturation, operable outbox (stuck rows, batch, metrics), inbox exactly-once **effect**, per-entity ordering keys, SKIP LOCKED workers, status projection, SSE hard edges, observability signals, chaos drill + runbook, written 2h vs 5h cut-line, security baselines (no secrets, no PII in logs/queues, tenant/player scoping)
- **Must force (weave, not 15 separate apps):** A01–A15 appear as **milestones or drills** inside one lab narrative. If time-box pressure bites, define a **core path** (A01, A03, A04, A09, A11, A13, A14, A15) and **elective drills** (A02, A05, A06, A07, A08, A10, A12) the reader still names in a coverage matrix with pass/skip+waiver
- **Try-this ideas:** chaos kill mid-publish; poison classification; slow SSE client; deadlock via wrong lock order then fix with ordering/SKIP LOCKED
- **Trade-off forks:** retry vs park; global queue vs ordered keys throughput; cut-line what ships in 2h incident response story
- **Showcase handoff:** post-Ex3 grilling; production judgment
- **Interview line:** “I can run a chaos drill, show lag and poison policy, and defend exactly-once effects without lying about broker delivery.”

---

## Format for Each Capstone File

```markdown
# [ID] [Title] — Track Capstone (Code-Along)

## Objective
## Time box
## Prerequisites
- Track electives that should be complete (table ID → title links)
- Tools (JDK, Docker, …)
- Position vs showcase (before Ex1 / Ex2 / Ex3 / after Ex3)

## Blog & curriculum links
## Background & motivation
## Skill checklist (mandatory)
Map each prior elective ID to a concrete capstone behavior or test the reader must implement.
Mark optional (e.g. K08) clearly.

## Learning objectives
## Warm-up
## Project bootstrap
- Exact directory to create under `showcase_projects/electives/projects/<slug>/`
- Build tool init steps (Gradle/Maven) as guided Do/Run/Observe — no pre-built solution repo
- README skeleton the reader fills as they go

## System specification
- Product fantasy / actors
- Scope in / scope out
- Functional requirements
- Non-functional / evidence requirements (races, logs, EXPLAIN, chaos notes)
- Constraints (local only, single deployable, etc.)

## Milestones (code-along)
### M1 — …
### M2 — …
### M3 — …
### M4 — …
Each milestone uses Do / Run / Observe steps, and may embed a mini trade-off.

## Try this
At least two experiments across the capstone (failure, race, reconnect, chaos).

## Trade-off forks
At least two major forks for the whole capstone (plus any milestone micros).

## Hints
Progressive; no full solutions.

## Checkpoint / success criteria
Including: skill checklist coverage, demo script or evidence folder (notes/, explains/, chaos/).

## Bottleneck & reflection questions
## Handoff
- Next track or showcase exercise
- Interview one-liner (say aloud)

## Optional stretch
```

---

## README update requirements

Add a section **Track capstones** that includes:

1. Purpose: synthesize track skills in a **new small project** (geek domains)
2. Table of all six capstones (ID, title, track, time, file link, project slug)
3. Rule: finish track labs (or knowingly waiver with notes) before capstone
4. `projects/` convention — gitignore suggestion optional; candidate-owned code
5. Wave guidance: KC/SC after wave-1 kotlin/spring; PC after P labs; RC after R labs; XC after glue; AC after advanced core path / post Ex3
6. Reminder: capstones do **not** replace `pharmacy-fulfillment` Ex1–3

---

## Pedagogy & constraints

- **Code-along only** — same spirit as electives; richer milestones because project-sized
- **No full solution dumps** — stubs/signatures/SQL sketches/log examples OK
- **Creative domains required** as specified (dice, café, tickets, courier, observatory, nightshift)
- **Not** a second pharmacy fulfillment showcase
- **No** cloud, **no** full SPA, **no** k8s
- Local evidence over vibes (test output, EXPLAIN, management UI screenshots described in text, chaos notes)
- Rabbit: at-least-once delivery; exactly-once **effect** only where taught
- SSE: never one competing Rabbit consumer per browser connection
- Tone: senior peer, interview-aware, geek-friendly without cringe overload

---

## Definition of done

- [ ] Six CAPSTONE_*.md files exist at the paths above
- [ ] README updated with capstone program
- [ ] Subagent workflow used (planner → parallel writers → reviewer → integration)
- [ ] Each capstone has project bootstrap + skill checklist covering its track
- [ ] Each has ≥2 try-this experiments and ≥2 trade-off forks
- [ ] No solution repositories created by the writer
- [ ] Links to prior electives and showcase exercises resolve

STOP when the reviewer punch list is empty.
