# K05 Test Data Builders — Code-Along Elective

## Objective

Replace repetitive object literals with default-parameter builder functions, drive the K03/K04 matrices with parameterized tests, and use light mocking only where a collaborator boundary is actually being tested — with the discipline to know when a fake beats a mock.

## Time box

~1–1.5 hours. Core (Wave 1, listed before S1 in the wave order). The builder-vs-Builder-class fork and the mock-vs-fake reflection are worth 20 minutes combined.

## Prerequisites

- `K04_inventory_pure_functions.md` — the reserve/release functions your builders will feed (dependency edge: builders test pure functions).
- `K02_nullable_patient_lookup.md` and `K03_workflow_state_machine.md` — the `Patient` and `Prescription` shapes you are building.
- Showcase position: before `exercise_01_foundation.md` Milestone 6 ("Add unit, API, and happy-path evidence").

## Blog & curriculum links

- Primary: `posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md` — "Prefer a small test data builder to repeating a large object literal" and "Test Outcomes, Not Implementation Details".
- Secondary: `posts/series-1-kotlin/02-nullability-results-domain-errors.md` — the outcome shapes your service tests will assert.
- Coach-assessment gap: Day 4 ("testing conventions", "MockK versus Mockito Kotlin") and Day 10 ("test data builder"). Light mocking is deliberately *light*: the blog warns interaction assertions get brittle.

## Background & motivation

A Java veteran writes fixtures with a `TestDataFactory` class, a fluent `Builder`, or a copy-paste of a `new Prescription(...)` with eight arguments. Kotlin's default parameters collapse this: `prescription(status = REJECTED)` is a builder with zero ceremony, and every test reads as one deliberate difference. Parameterized tests then turn the K03 transition matrix and the K04 boundary suite into tables, which is what a reviewer wants to run.

This kata deliberately ignores: Spring Boot test slices, Testcontainers, mocking frameworks beyond a tiny exercise, and integration tests. It is the "cheap credible tests" tier from `posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md` — and it teaches you to *know* it is the cheap tier, which is the interview point.

## Learning objectives

- Write default-parameter builder functions that produce valid, minimal fixtures with one named override per test.
- Convert the K03 transition matrix and K04 boundary cases into `@ParameterizedTest` tables.
- Verify a collaborator boundary with a light mock (or a hand-rolled fake) without asserting implementation details.
- State the difference between a stub, a fake, and a mock — and when each lies.
- Name the tests that cannot live at this tier (real PostgreSQL/RabbitMQ semantics).

## Warm-up

Read `posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md`, the "Start With Domain Transition Tests" section, especially the `prescription(status = ...)` example and the "Where Mocks Stop Being Convincing" section. Before coding, write the builder contract in one sentence: defaults are a *valid common case*, and a test changes exactly one thing.

## System specification

**Scope in:** builder functions for `Prescription`, `Patient`, and inventory maps; parameterized transition/boundary tests; one service test with a light mock verifying an expected call and outcome.

**Scope out:** Spring Boot test infrastructure, real database/broker tests, heavy mocking frameworks, test utilities shipped in `src/main`.

**Functional requirements (minimal):**

- `prescription(status: PrescriptionStatus = AWAITING_APPROVAL)` and a `patient()` builder with sensible defaults and named overrides (id, name, phone optionality).
- `inventoryOf(vararg pairs)` or a small map builder for the K04 seed.
- The K03 matrix and K04 boundary suite run as parameterized tests with case objects.
- One service test where a mocked (or faked) repository/publisher collaborator lets you assert: outcome is right, and the publish happened exactly once on success and zero times on `InvalidState`.
- A stub vs fake vs mock note in the test file header or your notes file.

**Constraints:** `kotlin.test` / JUnit 5 only; MockK (or Mockito-Kotlin) permitted as a dependency; builders live in `src/test`, never in `src/main`.

## Step-by-step code-along

**Step 1 — Builder functions**

**Do:** in `src/test/kotlin`, create `fixtures.kt` with:

```kotlin
fun prescription(
    id: String = "prescription-1",
    patientId: String = "patient-1",
    items: List<PrescriptionItem> = listOf(PrescriptionItem("amoxicillin", 10)),
    status: PrescriptionStatus = PrescriptionStatus.AWAITING_APPROVAL,
): Prescription = Prescription(id, patientId, items, Instant.parse("2026-01-02T03:04:05Z"), status)
```

Do the same for `patient()` (with an optional phone). Then refactor *every* K01–K04 test that builds an object literal to use the builders.

**Run:** `./gradlew test` — the existing suites must stay green, only the construction sites change.

**Observe:** `prescription(status = PrescriptionStatus.REJECTED)` now reads like a sentence. Named arguments at the call site are the readability win; positional style would silently rebuild the old ceremony.

**Kotlin idiom for Java veterans:** this *is* the Builder pattern, but the builder is the function's default parameters — no separate class, no fluent chain, no `build()` method. The cost: you cannot partially build and you cannot have invalid intermediate states, which for fixtures is a feature.

**Decision (if any):** fixed `Instant` default vs `Instant.now()` in the fixture. Fixed wins for determinism; note why a *moving* default would make equality tests flaky.

**Step 2 — Parameterized transition tests**

**Do:** rewrite the K03 matrix test using JUnit 5's `@ParameterizedTest` with a `@MethodSource` returning case objects:

```kotlin
data class TransitionCase(val command: Command, val from: PrescriptionStatus, val legal: Boolean)

fun transitionCases() = listOf(
    TransitionCase(Command.APPROVE, PrescriptionStatus.AWAITING_APPROVAL, true),
    TransitionCase(Command.APPROVE, PrescriptionStatus.REJECTED, false),
    // ...
)

@ParameterizedTest
@MethodSource("transitionCases")
fun `transition legality matches the table`(case: TransitionCase) {
    val p = prescription(status = case.from)
    // apply command, assert legal == expected
}
```

**Run:** `./gradlew test` — you should see every cell reported individually in the test report.

**Observe:** when a matrix cell breaks, the report names the exact `(command, from)` pair. That is the difference between a wall of `assertTrue` and a diagnostic.

**Decision (if any):** `@MethodSource` vs `@ValueSource` for enums — use `@MethodSource` with case objects here; `@ValueSource` is fine for simple flag variations.

**Step 3 — Parameterized inventory boundaries**

**Do:** convert the K04 boundary suite the same way: `InventoryCase(request, seed, expectInsufficient, expectedShortages?)` cases for exact fit, over-reserve, zero stock, unknown med, empty lines, duplicates.

**Run:** `./gradlew test`.

**Observe:** the boundary suite is now a table you can show an interviewer: "these are the stock states I proved."

**Step 4 — Light mocking at a real boundary**

**Do:** introduce a tiny service that owns one workflow decision — e.g. `PrescriptionService` with a `notifyPatient(prescriptionId)` operation that looks up the prescription via a `PrescriptionRepository` (interface) and calls a `NotificationGateway` when, and only when, the prescription is approved:

```kotlin
fun notifyIfApproved(id: String): NotificationOutcome {
    val p = repository.findById(id) ?: return NotificationOutcome.NotFound
    if (p.status != PrescriptionStatus.APPROVED) return NotificationOutcome.NotApplicable
    gateway.send(p.id)
    return NotificationOutcome.Sent(p.id)
}
```

Use MockK (or Mockito-Kotlin) to stub `repository.findById` and *verify* `gateway.send` was called exactly once. Add a second test: status `AWAITING_APPROVAL` → `NotApplicable` and `verify(gateway) { wasNotCalled }`.

**Run:** `./gradlew test`.

**Observe:** the mock is only at the *boundary* — repository and gateway — while the decision logic is real code. If the decision logic were itself mocked, the test would prove nothing. That sentence is the blog's "test outcomes, not implementation details" in practice.

**Decision (if any):** MockK vs Mockito-Kotlin. MockK is Kotlin-first (`every { ... }`, `verify { ... }`); Mockito-Kotlin stays closer to Java habits. Pick one — the choice is not graded, the *boundary* choice is.

**Step 5 — Fake beats mock where behavior is simple**

**Do:** replace the repository mock with a hand-rolled `InMemoryPrescriptionRepository` (a `Map` backed implementation, ~10 lines) and rerun the service tests. Keep the gateway mock for the *call-count* assertion, which is the one thing worth verifying.

**Run:** `./gradlew test`.

**Observe:** the fake makes "found vs not found" real instead of stubbed, which is closer to the K02 lesson. Now you can name the distinction: a fake implements the contract; a stub answers canned data; a mock additionally asserts calls. You used each where it earns its keep — and nowhere else.

## Try this

Deliberate experiment — **the lying default**:

1. Change the builder default `quantity` on `PrescriptionItem` from `10` to `-5` (an invalid value).
2. Run the suite. Observe: tests that never override quantity either throw at construction (`require`) or, worse, silently pass if a test was too weak to assert quantity at all.
3. Revert. Now write the lesson in one sentence: builder defaults must be *valid and representative*, because every test inherits them — a broken default is a silent global.
4. Optional second round: add a property to `Prescription` with a default in the builder but *not* in the domain class, and note how the compiler flags every construction site — the builder's signatures mirror the domain.

## Trade-off fork

**Option A — default-parameter builder functions (this kata's default).**
Pros: minimal ceremony, named overrides at call sites, type-safe, no extra class to maintain, idiomatic Kotlin the blog itself shows. Cons: no discoverability of available knobs beyond the IDE; a test that needs a "slightly different" object must pick *one* builder or nest overrides; defaults can silently drift from domain defaults.

**Option B — a fluent `Builder` class per aggregate.**
Pros: explicit `withStatus(...)` steps, chains read like recipes, easy to add derived variants, familiar to Java reviewers. Cons: an extra class per aggregate *in the test tree*, more code to maintain, and the fluent style invites builders that construct intermediate invalid states — the thing Kotlin defaults avoid.

Choose one and write 3–5 lines justifying it for a time-boxed submission, naming the lost benefit of the other. Bonus reflection: which option survives if the test suite grows to fifty fixtures, and which survives if the fixture needs to differ from domain rules (e.g. deliberately invalid data)?

## Hints

**Hint 1 (mild):** put all builders in one `fixtures.kt` and keep them flat — no nested objects. If a test overrides three things, three named arguments is fine; if it overrides five, the test itself is trying to say something and deserves its own named case object in the parameterized source.

**Hint 2 (stronger):** when mocking with MockK, `every { repo.findById(any()) } returns prescription(status = APPROVED)` — the builder feeds the stub, which is the whole point: fixtures compose *into* mocks. And for `wasNotCalled`, MockK's `verify(exactly = 0) { gateway.send(any()) }` is the spelled-out form.

## Checkpoint / success criteria

You may leave when:

- Every K01–K04 test constructs fixtures through builders; zero object-literal repetition remains.
- The transition matrix and inventory boundaries run as parameterized tables, and a deliberately broken case appears by name in the report.
- The service test verifies outcome + exactly-one-publish on success and zero publishes on the wrong state.
- You can write the stub/fake/mock one-liners from memory and say which claims they cannot prove (real routing, real constraints — the P/R-series tier).

## Bottleneck & reflection questions

- The blog's practical test set names "one end-to-end happy path" as priority #2 — what does this kata's builder/mock tier *not* prove about that path?
- A mocked `NotificationGateway` proves a call; `exercise_01_foundation.md` Milestone 6 requires "at least one real local dependency assertion". Where does the builder tier stop and the Docker tier start?
- If a builder default becomes invalid, some tests fail loudly and others silently pass. Which of your tests are vulnerable to the second kind, and how does naming help?
- "Verify exactly one publish" is the blog's example of a brittle interaction assertion. When would it be the *right* assertion, and when would it break under an outbox refactor (R7)?
- How does `prescription(status = REJECTED)` compare to `TransitionCase(Command.APPROVE, REJECTED, false)` as documentation of the K03 graph? Which one would you show an interviewer first?

## Handoff

- Next elective: `K06_collections_and_sequences.md` (any time after K01) or `K07_extensions_and_scope_functions.md`, then `../spring/S01_hello_prescription_api.md` — Wave 1's first Spring elective. K05 is the last pure-Kotlin test-tier kata before the framework arrives.
- Related showcase exercise: `showcase_projects/pharmacy-fulfillment/exercise_01_foundation.md` — Milestone 6's evidence suite reuses every builder you wrote here.
- Interview line you should be able to say aloud: "I build fixtures with default-parameter builder functions so each test shows exactly one deliberate difference, drive the state and inventory matrices as parameterized tables, and mock only at collaborator boundaries — verifying the outcome and one publish call, not implementation details. I know this tier proves domain rules, not routing or constraints, and I reserve real PostgreSQL and RabbitMQ assertions for the claims that need them."

## Optional stretch

Write a test that generates `TransitionCase` rows for *all* 7×6 command/state combinations programmatically and asserts the set of legal cells equals your hand-written expectation — a "the table is complete" meta-test. Then write one paragraph on when such meta-tests add confidence and when they become tests-of-tests that are not worth keeping.
