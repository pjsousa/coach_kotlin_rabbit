# SC Tiny Status Café — Track Capstone (Code-Along)

## Objective

Build a small, complete Spring Boot + Kotlin REST service from zero: a coffee-cart status board. A customer places an order; a barista walks it through `PLACED → IN_PROGRESS → READY → PICKED_UP` (or `CANCELLED`); anyone can fetch status by order id or glance at a live "board" list. You will assemble S01–S06 into one thin vertical — validation at the edge, a three-layer slice with constructor injection, sealed domain errors mapped to a deliberate 400/404/409/500 contract, a serious MockMvc suite, test/dev profiles, and a time-box README that reads like a take-home you can defend. The primary objective is to *ship the S-track as a product*, not as six labs.

## Time box

~5–7 hours. Suggested split: M1 bootstrap + thin vertical 1.5h, M2 sealed errors + transitions + board 1.5–2h, M3 test suite + profiles/config 1.5h, M4 time-box README 1h, try-this + fork notes interleaved. If the clock bites, the cut-line is written down *before* you decorate: M4's README is not skippable, tests are not skippable, Postgres is.

## Prerequisites

- Track B electives complete (or knowingly waived — see Skill checklist):

| ID | Title | Why it is required here |
|---|---|---|
| S01 | [Hello Prescription API](../spring/S01_hello_prescription_api.md) | The exact submit/status pair this capstone re-skins for coffee |
| S02 | [Layered Slice](../spring/S02_layered_slice.md) | Controller → service → repo discipline with constructor injection |
| S03 | [Error Mapping](../spring/S03_error_mapping.md) | Sealed domain errors → deliberate HTTP mapping (the capstone's spine) |
| S04 | [API Tests](../spring/S04_api_tests.md) | The MockMvc/WebTestClient suite pattern you will extend |
| S05 | [Config and Profiles](../spring/S05_config_and_profiles.md) | `application.yml`, test/dev profiles, fail-fast, secrets placeholder |
| S06 | [Time-Box README](../spring/S06_timebox_readme.md) | M4 is this elective applied to the capstone project |

- Tools: JDK 17+ (21 fine), `curl`, a browser tab to start.spring.io or a Gradle 8.x install. No Docker, no Postgres, no broker — this track stays local and single-module.
- Position vs showcase: **before `exercise_01_foundation.md`** ([`../../pharmacy-fulfillment/exercise_01_foundation.md`](../../pharmacy-fulfillment/exercise_01_foundation.md)). This capstone is the dress rehearsal: Ex1 is the same thin vertical at pharmacy scale with a database and a broker bolted on.

## Blog & curriculum links

- Primary: `../../../posts/series-4-product-sse/01-patient-first-api.md` — "The Patient-Facing Surface: Two Endpoints", "The State Machine Is the Contract", "Error Contracts: Four Answers". Read these twice; the café board is the same shape with coffee names.
- Primary: `../../../posts/series-4-product-sse/04-time-box-scoping.md` — "The Two-Hour Slice: Correct Before Fancy" and "Documenting Limitations: The README Is Scope". M4 is this post executed against your own repo.
- Secondary: `../../../posts/series-1-kotlin/01-kotlin-for-java-developers.md` — "`val` Is the Default" and "Properties Are Not Just Public Fields" for the Java-veteran muscle-memory nudges.
- Electives program: [`../README.md`](../README.md) — "Track capstones" section (gate rule, `projects/` convention).

## Background & motivation

A customer at a coffee cart asks exactly one synchronous question: *"is my order ready?"* — the same shape as a patient asking about a prescription. The blog's claim is that the entire patient experience reduces to submit + status; this capstone makes you prove that claim with a **new project** in a domain where no pharmacy vocabulary can mask a gap. If the café board is legible and testable, you own the pattern; if it is not, Ex1's bigger state machine will be where you learn the lesson instead of where you reuse it. The deliberate non-pharmacy domain keeps this project orthogonal to the showcase — it is the S-track's proof of transfer, not a second fulfillment system.

## Skill checklist (mandatory)

Mark **pass** or **skip + waiver** (one written sentence of why) for every row before you leave the capstone. This table is the M4 README's coverage section.

| Prior elective | Concrete behavior forced in Tiny Status Café | Pass / skip + waiver |
|---|---|---|
| S01 | `POST /orders` + `GET /orders/{id}` with DTOs and bean validation (non-blank customer, non-empty items, quantity bounds); `201`/`400` at the edge; in-memory repository | [ ] pass / [ ] skip + waiver |
| S02 | Controller → service → repo three-layer slice with constructor injection only (no `@Autowired`, no `lateinit`); service constructible in a plain JUnit test; controller carries no business rules | [ ] pass / [ ] skip + waiver |
| S03 | Sealed domain errors (`OrderNotFound`, `IllegalTransition`, `InvalidOrder`) mapped deliberately to `400`/`404`/`409`/`500` with consistent JSON body; no HTTP types in the domain package; exhaustive `when`; 409-vs-422 fork on illegal transitions (double-complete, invalid transition) | [ ] pass / [ ] skip + waiver |
| S04 | `@SpringBootTest` + MockMvc (or WebTestClient — fork) suite asserting `201`/`200`/`400`/`404`/`409` with body assertions plus the board-list endpoint; fixture builder; test names read as specifications | [ ] pass / [ ] skip + waiver |
| S05 | Magic values in `application.yml`; test and dev profiles (different port/logging); fail-fast on bad values (`menu.max-items: "abc"` fails startup); `${ENV_VAR:default}` placeholder for secrets; `git diff` shows zero secrets | [ ] pass / [ ] skip + waiver |
| S06 | Time-box README as required deliverable: explicit 2h-vs-5h cut-line table with ≥3 "No — documented gap" cells with consequences, decisions log covering every S01–S05 fork, evidence a reviewer verifies in under 60 seconds | [ ] pass / [ ] skip + waiver |

**Easy-to-skip risks, forced:** S06 is a writing-only deliverable — it is mandatory M4, gated on "clean checkout → running in 60 seconds using only the README". S05 hygiene is a milestone (M3), not an afterthought. S03's 409-vs-422 fork is proven by the double-complete try-this as checkpoint evidence — you cannot pass without a written position.

## Learning objectives

1. Rebuild S01–S05 from memory in a new domain without looking at the lab repos (that is the point of a capstone).
2. Enforce a small status machine with a sealed outcome type and watch the compiler force exhaustive handling.
3. Decide and justify the 409-vs-422 position with a concrete double-complete experiment as evidence.
4. Write a specification-grade test suite where test names read as the API contract.
5. Configure profiles and fail-fast behavior without committing a secret.
6. Produce a time-box README whose cut-line table and decisions log survive a hostile reviewer.

## Warm-up

Read `../../../posts/series-4-product-sse/01-patient-first-api.md` sections "The Patient-Facing Surface: Two Endpoints" and "Error Contracts: Four Answers" (5 min). Then answer out loud: for the coffee cart, what is the single synchronous question, who are the three consumers (customer, barista, board), and what are the four error answers? If you can say "one status GET that is the correctness baseline, plus barista commands and a board that is a projection of the same state" you have the frame this project tests.

## Project bootstrap

- **Exact directory:** `showcase_projects/electives/projects/tiny-status-cafe/` — you create this. The directory is candidate-owned code; nothing in the repo scaffolds it for you.
- **Do:** open start.spring.io. Pick **Kotlin**, **Gradle — Kotlin**, Spring Boot **3.x**, Java 17+, dependencies **Spring Web** and **Validation** (add **Spring Boot DevTools** if you like fast restarts). Generate and unzip so the project root is `tiny-status-cafe/`.
- **Run:** `./gradlew bootRun` in that directory; hit `http://localhost:8080` with curl and confirm the whitelabel page answers.
- **Observe:** you now have a working app with zero business code — the same state S01 left you in. Everything from here on is yours. Alternative if you prefer the terminal: `gradle init` a Kotlin library/app and add the Spring Boot plugins by hand (more steps, same result — pick one and note it in the decisions log).
- **Do:** create the README skeleton now — the file you will fill across every milestone (it is the S06 deliverable, written incrementally, not at the end):

```markdown
# Tiny Status Café

A coffee-cart status board: place an order, track it to picked-up.
One synchronous question: "is my order ready?"

## Run
(commands: bootRun, the three curls, ./gradlew test)

## API contract
(table: endpoint, method, body, statuses)

## Error contract
(table: code, status, when)

## Time-box cut-line (2h vs 5h)
| Capability | 2h slice | 5h version |
|---|---|---|
| ... | Yes / No — documented gap (consequence) | ... |

## Decisions log
| Fork | Choice | Why (2 lines) |

## Known failure windows
(one sentence each)

## Evidence
(curl transcript, test summary — copied at checkpoint)
```

## System specification

- **Product fantasy / actors:**
  - *Customer:* shouts an order (`POST /orders`), then asks "is my order ready?" (`GET /orders/{id}`). Never touches status transitions.
  - *Barista:* works the machine (`PATCH /orders/{id}/status`): in-progress → ready → picked-up, or cancels. The only actor who mutates status.
  - *Board:* a wall display (`GET /orders/board`) listing active orders oldest-first so the barista knows what to pull — a read-only projection of the same state.
- **Scope in:** single module; `POST /orders`, `GET /orders/{id}`, `PATCH /orders/{id}/status`, `GET /orders/board`; validation DTOs; sealed domain errors → 400/404/409/500 with consistent JSON body; in-memory repository; test + dev profiles; MockMvc suite; time-box README.
- **Scope out:** Postgres, RabbitMQ, SSE, auth (a `cart-token` placeholder is enough), menus/pricing/inventory, persistence of order history, idempotent resubmission, a UI, Docker, multiple instances.
- **Functional requirements:**
  - `POST /orders` takes `{ customer, items: [{ item, quantity }] }`, rejects blank `customer`, empty `items`, blank `item`, quantity outside bounds (`1..menu.max-quantity`), more than `menu.max-items` lines → `400`. Returns `201` with `{ id, customer, status, items, placedAt }`.
  - `GET /orders/{id}` returns the order view; unknown id → `404`.
  - `PATCH /orders/{id}/status` with `{ "newStatus": "IN_PROGRESS" }`; allowed: `PLACED→IN_PROGRESS→READY→PICKED_UP` and any pre-pickup → `CANCELLED`. Everything else → `409` (or `422` — your fork, your evidence).
  - `GET /orders/board` returns active orders (`PLACED`/`IN_PROGRESS`/`READY`) oldest-first.
- **Non-functional / evidence requirements:** tests green; `curl` transcript saved to `notes/curl-transcript.txt`; `./gradlew test` summary saved; README reproducible from a clean checkout in under 60 seconds; `git diff` shows no secrets.
- **Constraints:** single module; in-memory repository; no HTTP types in the `domain` package; constructor injection only; port 8080 in `dev`, different port under `test`; no secrets in committed yml.

## Milestones (code-along)

### M1 — Bootstrap + the thin vertical (S01, S02)

- **Do:** create `domain/` with `enum class OrderStatus { PLACED, IN_PROGRESS, READY, PICKED_UP, CANCELLED }` and an immutable `Order(id, customer, items, status, placedAt)` (`data class`, `val` everywhere — series-1/01's "Properties Are Not Just Public Fields" is why). In `repository/`, `class InMemoryOrderRepository` wrapping a `ConcurrentHashMap`. In `service/`, `OrderService(private val repository: InMemoryOrderRepository)` with `placeOrder(...)`, `getOrder(id)`, `transition(id, newStatus)`, `board()`.
- **Mini fork (decisions log):** validation at the DTO boundary (`@field:NotBlank`, `@field:NotEmpty`, `@field:Range`) vs domain `require(...)`. Pick S01's habit: validate at the edge, keep domain constructors honest with `require`.
- **Run:** `./gradlew bootRun`; curl `POST /orders` (valid → 201, blank customer → 400, empty items → 400, `quantity: 0` → 400), then `GET /orders/{id}` for a real id (200) and a UUID you made up (404).
- **Observe:** the three-layer slice with constructor injection means `OrderService(InMemoryOrderRepository())` is constructible in a plain JUnit test with zero Spring — write that one-liner test now; it is the S02 proof. Check your controller: if it decides *anything* about statuses or bounds, that rule is in the wrong layer.
- **Checkpoint:** 201/400/200/404 all demonstrated; the plain-JUnit service test exists; no `@Autowired` anywhere.

### M2 — Sealed errors, transitions, board (S03)

- **Do:** in `domain/`, `sealed interface OrderError` with `data object OrderNotFound`, `data object IllegalTransition`, `data object InvalidOrder` (or data classes carrying detail). Service methods return `sealed interface` outcomes — e.g. `sealed interface OrderResult { data class Ok(val order: Order) : OrderResult; data class Err(val error: OrderError) : OrderResult }` — or `Result`-style wrappers. The controller maps error → status in one exhaustive `when`: `OrderNotFound → 404`, `IllegalTransition → 409 (your fork)`, `InvalidOrder → 400`. Build the consistent body: `{ "status": 409, "code": "ILLEGAL_TRANSITION", "message": "Order 3 is READY, not IN_PROGRESS" }` (400 additionally carries `fieldErrors`). No `HttpStatus`/`ResponseEntity` imports in `domain/` or `service/` — a compile error today, a interview answer tomorrow.
- **Mini fork (decisions log):** the transition command body — `{ "newStatus": "READY" }` (simple, but invites arbitrary jumps, which is exactly what `IllegalTransition` catches) vs `{ "action": "complete" }` (typed, more mapping). Pick one; the *illegal* cases are the same either way.
- **Do:** implement `transition` enforcing the machine; implement `board()` returning non-terminal orders oldest-first.
- **Run:** create an order; `PATCH` to `IN_PROGRESS` (200), `READY` (200), `READY` again → 409 with the deliberate body; `PATCH` from `READY` back to `IN_PROGRESS` → 409. Then `GET /orders/board`.
- **Observe:** the compiler now forces you to handle every outcome; the 409 body is *the same shape* as the 400 body. A reviewer can learn the whole error contract from one curl transcript — that is the S03 win.
- **Checkpoint:** illegal-transition 409 transcript saved; no HTTP types in `domain/`; the `when` has no `else` branch.

### M3 — The suite, profiles, config hygiene (S04, S05)

- **Fork (decisions log):** `@SpringBootTest` + **MockMvc** vs **WebTestClient**. MockMvc is the lighter Java-neighbor habit; WebTestClient is reactive-adjacent and reads like a spec. Pick one and own it — the suite is the deliverable, not the client.
- **Do:** write the suite with a fixture builder (`OrderFixture` — reuse the K05 builder muscle): tests named as specifications, e.g. `placing an order with a blank customer returns 400 and a VALIDATION_FAILED body`, `completing an order twice returns 409`, `transitioning a picked-up order to cancelled returns 409`, `the board lists active orders oldest first`. Assert bodies, not just statuses.
- **Do:** move every magic value into `application.yml`: `menu.max-items`, `menu.max-quantity`, `board.active-window-minutes`, plus a `cart-token` read from `${CART_TOKEN:local-dev}` (S05's secret rule: yml is committed, so yml has no secrets). Add `application-dev.yml` (port 8080, INFO logging) and `application-test.yml` (different port, DEBUG/quiet logging — your choice, your evidence). Prove fail-fast: run once with `menu.max-items: "abc"` and watch startup refuse.
- **Run:** `./gradlew test` — full suite green; then `./gradlew bootRun --args='--spring.profiles.active=test'` and confirm the port changed.
- **Observe:** `git diff` (or `git status`) shows only committed yml with defaults and placeholders — zero secrets. The suite + profile now double as the README's evidence.
- **Checkpoint:** suite green including double-complete and invalid-transition cases; fail-fast demonstrated; `git diff` clean of secrets.

### M4 — The time-box README (S06, required deliverable)

- **Do:** finish the skeleton: one-paragraph what-and-why that leads with the customer ("the customer asks one synchronous question"), the run section, the API/error contract tables, the 2h-vs-5h cut-line table with **≥3 "No — documented gap" cells each carrying a one-line consequence** (e.g. "No idempotent resubmission — a double-tap at the counter creates two orders; fixed by a client order key + unique constraint in Ex1"), the decisions log covering every fork you hit (DTO boundary, transition command body, MockMvc vs WebTestClient, exception handler vs Result service, 409 vs 422, board query vs read model), failure windows (in-memory repo loses everything on restart; one sentence each), and evidence (curl transcript + test summary).
- **Run:** `git stash` (or a clean clone) and follow your own README top to bottom with a stopwatch — no questions answered, no IDE magic.
- **Observe:** if it takes more than 60 seconds or needs a single clarification, the README lied. Fix the README, not the stopwatch. This gate is the capstone's whole point: the S-track story must survive a reviewer who owes you five minutes, not fifty.
- **Checkpoint:** clean checkout → running in ≤60 s using only the README; a second person (or a subagent) verifies the claims in <60 s.

## Try this

1. **Double-complete the same order.** Mark an order `READY`, then `PATCH` it to `READY` again, then try to `PATCH` a `PICKED_UP` order to `CANCELLED`. Record both 409 bodies. This experiment is your 409-vs-422 evidence — it goes in the README decisions log.
2. **Invalid transition.** Take a `READY` order and `PATCH` it back to `IN_PROGRESS`. Same status code family as #1? Different error text? Note what a client that retries would do with each — that is the fork's real question.
3. **Fail a test on purpose, then fix it.** Change the double-complete test to assert `200` and watch the suite go red; then restore the `409` assertion. This is your story for "the error contract is tested, not hoped for".
4. **Run under the test profile.** Start with `--spring.profiles.active=test` and confirm port and log level differ from `dev`. Then break config on purpose (`menu.max-items: "abc"`) and capture the startup failure — the fail-fast claim, evidenced.

## Trade-off forks

1. **Exception handler vs Result-returning service at the API boundary.** Either (a) service throws typed domain exceptions caught by a `@RestControllerAdvice` mapping to statuses, or (b) service returns sealed outcomes the controller maps in an exhaustive `when`. Fork (b) keeps the domain free of exceptions-as-flow and makes the mapping a pure function you can unit-test without Spring; fork (a) keeps controller code thin but hides possible outcomes from callers until they read the advice. Discuss with the K02 "exceptions have a place" split in mind — expected command alternatives are data, infrastructure surprises are exceptions.
2. **409 vs 422 for illegal transitions.** The blog's four answers put "illegal transition" in the conflict family; many HTTP-orthodox teams answer 422 (well-formed but semantically invalid). Your position needs a retry-ability argument: would a client retrying on 409 make things worse? On 422? The double-complete try-this is your evidence.
3. **Board as a query on orders vs a separate read model.** Keep simple — discuss only: the board is a `filter + sort` over the same in-memory orders today; a separate projection becomes interesting when baristas get dashboards or customers get sockets, which is Ex1/Ex3 territory. Name the trigger point; do not build it.

## Hints

- **M1, Hint 1:** validation annotations need `@field:` targets in Kotlin — if your 400 never fires, that is the first thing to check. **Hint 2:** `ConcurrentHashMap` + UUID keys; `map[id]` returns a nullable — Kotlin prefers that over `Optional`.
- **M2, Hint 1:** `sealed interface` + exhaustive `when` — write the `when` without an `else` and let the compiler list the cases you forgot. **Hint 2:** the board is a projection: `orders.values.filter { it.status in ACTIVE }.sortedBy { it.placedAt }` — one expression, no new machinery.
- **M3, Hint 1:** fixture builder first — `OrderFixture.aPlacedOrder(customer = "Sam")` — then the tests are one-liners that read like sentences. **Hint 2:** for fail-fast, a `@Validated @ConfigurationProperties` class plus a wrong-typed value in yml is enough; the startup log is your evidence.
- **M4, Hint 1:** write the cut-line table as if a skeptic is reading it: every "No" needs a consequence, not an excuse. **Hint 2:** the evidence section is copied output — curl transcript and test summary — not promises.

## Checkpoint / success criteria

You may leave when:

- Skill checklist: all six rows **pass**, or waived with one written sentence each, copied into the README.
- `./gradlew test` green with a suite whose test names read as the contract (including double-complete → 409 and invalid-transition → 409/422).
- A `notes/` folder in the project holds `curl-transcript.txt` and the test summary; the README's Evidence section reproduces them.
- Clean checkout → running in ≤60 s using only the README; cut-line table has ≥3 "No — documented gap" cells with consequences; decisions log covers every S01–S05 fork including 409 vs 422 with the double-complete evidence.
- `git diff` shows no secrets; dev and test profiles demonstrably differ (port/logging); fail-fast on `menu.max-items: "abc"` demonstrated.
- No Postgres, no broker, no UI, no cloud — restraint is a feature, and the README says so.

## Bottleneck & reflection questions

1. **Patient experience:** the customer's one synchronous question is "is my order ready?" — does `GET /orders/{id}` answer it without noise? Map the analogy: what the barista does here, the pharmacist does in Ex1; where does the café's board correspond to the pharmacy's queue?
2. **Simplicity:** what did you *not* build, and is each omission defensible in the cut-line table? Which omission would a reviewer call a bug rather than a gap — and did you move it to the 5h column?
3. **System design:** where does the status machine live, and could a second writer add a transition without reading the service? Is the board a query or a read model, and what would make you switch?
4. **Failure handling:** walk a retrying client through your 400/404/409/422 decisions — does any choice cause a retry storm? What does the in-memory repo lose on restart, and what is the one-line fix you would say next?
5. What was the hardest part of the 60-second README gate — writing, or admitting what the 2h slice cannot do?

## Handoff

- **Next:** `../../pharmacy-fulfillment/exercise_01_foundation.md` — Ex1 is this capstone with Postgres, RabbitMQ, and a larger state machine. You now enter it with the patient-first surface, the error contract, the profile hygiene, and the cut-line README already owned. Alternatively, the Rabbit or Postgres capstones ([`../rabbit/CAPSTONE_RC_courier_packet_relay.md`](../rabbit/CAPSTONE_RC_courier_packet_relay.md), [`../postgres/CAPSTONE_PC_seathold_arena.md`](../postgres/CAPSTONE_PC_seathold_arena.md)) extend the same vertical.
- **Interview line to say aloud:** "I ship a thin vertical with explicit error contracts and a written cut-line before I decorate."

## Optional stretch

**Postgres-backed variant (~1–2h, not required; Track C optional):** swap `InMemoryOrderRepository` for a real table (orders + a `status_history` append table, [`../postgres/P05_status_history_append.md`](../postgres/P05_status_history_append.md) is the pattern) with Flyway, keep the service and controller untouched. The point is to prove the three-layer slice's swap-ability — if the controller and tests survive the repository swap with only config changes, the layering claim is demonstrated, and you have the Ex1 head start. Add the DB URL via `${DB_URL:...}` placeholder and note the new failure window (DB down) in the README. Everything else — including the cut-line table — stays true.
