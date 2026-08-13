# S04 API Tests — Code-Along Elective

## Objective

Pin the S01–S03 API with automated tests: `@SpringBootTest` + MockMvc (or WebTestClient — see the fork) asserting the happy path and the four error answers (`200/201/400/404/409`), with request fixtures built K05-style. The primary objective is to leave the API with tests that a reviewer can run and trust in under a minute — the "prove, don't describe" standard Ex1 is judged on.

## Time box

~2 hours, core track. Suggested split: test deps + first test 20 min, fixtures 20 min, happy path 25 min, validation/error tests 30 min, 409 test 15 min, fork + `@WebMvcTest` experiment 15 min, cleanup 5 min.

## Prerequisites

- `S01_hello_prescription_api.md` (the API under test) and `S03_error_mapping.md` (the contract being asserted).
- **`../kotlin/K05_test_data_builders.md`** — its builder pattern is reused directly here; do the fixture step with that file open.
- Showcase position: **before Exercise 1** — these are the test shapes Ex1 expands, and the "tests as evidence" habit the showcase grades.

## Blog & curriculum links

- Primary: `posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md` — "Start With Domain Transition Tests" and "Where Mocks Stop Being Convincing" (why the happy path here is a *real* context test, not a mock).
- Secondary: `posts/series-1-kotlin/05-showcase-kotlin-prescription-domain.md` — "Test The Rules At The Right Level" (which rules belong in domain tests vs API tests).
- Coach-assessment gap attacked: idiomatic Kotlin testing for a Java engineer — backtick test names, fixtures, no mock-itis.

## Background & motivation

S03 gave you a contract; this kata makes it un-breakable. The blog's message: tests should be part of the design, a reviewer runs the project, observes the happy path, and sees evidence for the important failure cases. For a 2-hour submission that means: one end-to-end happy path through real HTTP plus the four error answers. This kata deliberately ignores Testcontainers (that is `../postgres/P07_testcontainers_postgres.md`), broker tests (R-track), and full contract-test frameworks — the S-track proves its claims with the Spring test slice and no Docker.

## Learning objectives

1. Write `@SpringBootTest` + `@AutoConfigureMockMvc` tests in Kotlin with backtick names.
2. Choose MockMvc vs WebTestClient for this API and justify the choice (fork).
3. Reuse the K05 builder pattern for request fixtures — one builder, per-test overrides.
4. Assert status *and* body shape for all four error answers, including `fieldErrors`.
5. Prove the happy path end-to-end through the real HTTP stack without a database.
6. Decide when `@WebMvcTest` beats `@SpringBootTest` — and when it is a trap on a small app.

## Warm-up

Open `posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md` and read "Start With Domain Transition Tests" and "A Practical Test Set For The Time Box" (4 min). Probe: take two of your past Java `@Test` method names and rewrite them as Kotlin backtick names — e.g. `fun `submitting with empty items is rejected`()`. Notice how the name becomes a specification line an interviewer can read without opening the body.

## System specification

- **Scope in:** one `@SpringBootTest` class per surface area (or one well-named class — pick and justify); fixtures module; assertions for `201/200/400/404/409`; happy-path end-to-end; runnable via `./gradlew test` with no Docker.
- **Scope out:** Postgres, RabbitMQ, SSE, browser-style tests, contract-test tooling, performance tests, mocks of the service/repository (this kata is the opposite of that).
- **Constraints:** in-memory storage still; tests must be deterministic *within one class* (see Try-this for the cross-class leak); no test secrets; single module.

## Step-by-step code-along

1. **Test skeleton**
   - **Do:** add `spring-boot-starter-test` (the Spring Initializr starter includes JUnit 5, AssertJ, MockMvc, jsonPath). Write a shell test: `@SpringBootTest @AutoConfigureMockMvc class PrescriptionApiTest { @Autowired lateinit var mvc: MockMvc }` with one test hitting `GET /prescriptions/{random-uuid}` and asserting `404`.
   - **Run:** `./gradlew test`.
   - **Observe:** the full application context starts (no DB, no broker — it's still the S-track slice), the request goes through real Spring MVC, and the assertion passes. Kotlin idiom: `lateinit` is the *one* place it earns its keep — test-injected fields that JUnit populates.

2. **Fixtures**
   - **Do:** port the builder from `../kotlin/K05_test_data_builders.md` — a function like `fun submitRequest(patientId: String = "patient-1", items: List<ItemRequest> = listOf(ItemRequest("amox-10", 1)), clientSubmissionKey: String = "key-${UUID.randomUUID()}") = SubmitPrescriptionRequest(...)`. Convert to JSON in each test via `ObjectMapper` (or `jacksonObjectMapper()`).
   - **Run:** `./gradlew test`.
   - **Observe:** each test becomes a one-liner override: `submitRequest(clientSubmissionKey = "dup-key")`. Defaults with `val` and constructor params are doing the fixture work — no builder classes, no reflection.

3. **Happy path end-to-end**
   - **Do:** test `fun `submit then retrieve shows SUBMITTED status`()` — POST a request, extract the `id` from the `201` body, GET it, assert `200` and `status == "SUBMITTED"`.
   - **Run:** `./gradlew test`.
   - **Observe:** this is the blog's end-to-end happy path claim, executed without a database. Assert the body with `jsonPath("$.status").value("SUBMITTED")` or parse to a `PrescriptionView` — pick the style you can defend.

4. **The four answers**
   - **Do:** tests for each: empty `items` → `400` with `fieldErrors` containing the `items` field; missing id → `404`; double approve → second `409` with `code` "INVALID_STATE" (or your vocabulary); `GET` on approved id → `200`.
   - **Run:** `./gradlew test`.
   - **Observe:** the suite now *is* the contract from S03 — an interviewer reads the test names and knows what the API guarantees. If a name forces a story ("why does this 400 have two field errors?"), the contract is leaking; fix the test name or the contract.

5. **`@WebMvcTest` vs `@SpringBootTest`**
   - **Decision:** try one test as `@WebMvcTest(PrescriptionController::class)` with `@MockBean`/`@MockitoBean` service. Run both styles.
   - **Run:** `./gradlew test`.
   - **Observe:** `@WebMvcTest` starts a lighter slice but needs mocked beans, and the mock has to be kept in sync with real behavior — for a three-class app, the full context is usually cheaper. Write one line on when you'd switch (hint: when the context grows Postgres/Rabbit beans in Ex1 and gets slow).

6. **Stability pass**
   - **Do:** run `./gradlew test --rerun-tasks` twice; run the full suite with `--tests "*"`. Then read the failure output of one deliberately broken assertion.
   - **Run:** as above.
   - **Observe:** MockMvc failure messages name the exact request/response mismatch — that is the debugging experience you want to be able to describe.

## Try this

Two experiments. First: remove the `@field:NotEmpty` (or the `@Valid`) from the request DTO, run the suite, and watch the `400` tests fail — then restore. Second: add a *second* test class that also submits and asserts an exact total (e.g. "only one prescription exists"), run both classes, and observe the flaky interaction — the in-memory map is shared across the context. Name the failure mode ("tests share state through the singleton context") and decide the minimal fix for S-track (hint: each test asserting isolation needs its own repository instance or a documented reset) — this exact issue returns at Ex1 scale in `../postgres/P07_testcontainers_postgres.md`.

## Trade-off fork

**Option A — MockMvc** (synchronous, `MockMvcRequestBuilders`): the classic Spring stack; works with any assertion library; simple to reason about.

**Option B — WebTestClient** (reactive client, fluent `expectStatus().isOk()`): a thinner, chainable API; the same syntax will test WebFlux endpoints and — relevantly — SSE streams later (`../glue/X03_sse_toy.md`).

Pick one and write 3–5 lines justifying it. Name the lost benefits: A can't test the streaming/reactive paths you may add in the SSE electives; B's async API is more ceremony for a tiny sync controller. Nudge: A for this kata (sync slice, zero cost), but note in your decisions log that B is the same skillset as `../glue/X03_sse_toy.md`.

## Hints

- **Hint 1:** `jsonPath` is on the classpath via `spring-boot-starter-test`, but if the fluent `.andExpect(jsonPath("$.code").value(...))` fights your Kotlin, fall back to `mvc.result.andReturn().response.contentAsString` + `jacksonObjectMapper().readTree` and assert on the tree — both are defensible; pick one style and be consistent.
- **Hint 2:** for the double-approve test you need the id from the first response: `val id = ...readTree(response).get("id").asText()`. `@AutoConfigureMockMvc` gives you full request/response lifecycle, so no manual mock HTTP server.

## Checkpoint / success criteria

- `./gradlew test` is green with no Docker and no external services.
- Suite covers `201/200/400/404/409` with body assertions, plus the end-to-end happy path.
- Request fixtures come from one K05-style builder; tests override only what they care about.
- Test names read as specifications; a stranger can describe the API from the suite alone.
- Fork choice + the state-sharing observation are written down (S06 decisions log).

## Bottleneck & reflection questions

1. **Failure handling:** the suite asserts the four answers — which one *isn't* really pinned (hint: the `500`)? What would pinning it require, and is that worth the budget?
2. **Simplicity:** the blog warns against mock-itis. Which of your tests would survive the repository being swapped for Postgres unchanged, and which would not?
3. **System design:** if Ex1 grows a RabbitMQ consumer, where do *its* tests live — here, or in the R-track? What is the boundary that keeps API tests from becoming a second product?
4. **Patient experience:** is there a test that asserts the patient-facing *message text*, not just the status code? If not, what would it catch?
5. Which test would you delete first under a 2-hour deadline, and which would you fight to keep — and why does the blog's "practical test set" agree?

## Handoff

- **Next:** `S05_config_and_profiles.md` (make the tests profile-aware) and capstone `S06_timebox_readme.md` (your suite output becomes the README's evidence section). Later, `../postgres/P07_testcontainers_postgres.md` reuses every skill here against a real database.
- **Related showcase:** `../../pharmacy-fulfillment/exercise_01_foundation.md` — "happy-path evidence plus failure cases" is exactly its testing demand; this suite is the seed.
- **Interview line to say aloud:** "The API's claims are pinned by tests: one end-to-end happy path through real HTTP and the four error answers asserted on status and body. The suite runs in under a minute with no external services, and the fixtures come from builders, so each test states only what it cares about."

## Optional stretch

Add one test that asserts the error body never leaks internals — assert a `404` body contains no stack trace lines and no internal storage key — mirroring Ex1's rule that responses must not expose queue names or internal identifiers. Then write one line on why that test would have caught a real incident.
