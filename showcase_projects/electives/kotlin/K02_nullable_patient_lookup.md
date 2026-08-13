# K02 Nullable Patient Lookup — Code-Along Elective

## Objective

Model patient lookup so that absence (`Patient?`), expected business alternatives (sealed `PatientLookupOutcome`), and infrastructure failure (exceptions) are three different things with three different signatures — then prove each with tests.

## Time box

~1–1.5 hours. Core (Wave 1). If you hit 1.5h, the exception-vs-outcome boundary discussion matters more than the last test.

## Prerequisites

- `K01_prescription_value_objects.md` — you reuse `Prescription`, `Patient`-adjacent values, and the test harness from K01.
- JDK 17+, the Gradle Kotlin JVM module from K01 (you may copy the folder and rename the project).
- Showcase position: before `exercise_01_foundation.md` Milestone 1; this kata is where you decide "how the service maps repository absence and invalid state" before any HTTP layer exists.

## Blog & curriculum links

- Primary: `posts/series-1-kotlin/02-nullability-results-domain-errors.md` — the whole kata is the post turned executable.
- Secondary: `posts/series-1-kotlin/01-kotlin-for-java-developers.md`, section "A Java Habit To Avoid" and translation rule 2 (replace `Optional` deliberately).
- Coach-assessment gap: Day 2 ("nullable types, safe calls, Elvis, smart casts, `require`, `check`, sealed results, exception boundaries"); also the Day 1 interview prompt "distinguish absence, invalid state, and infrastructure failure".

## Background & motivation

A Java veteran's toolbox for "patient not found" is `Optional<Patient>` + `orElseThrow` + a `PatientNotFoundException` for everything else. Kotlin makes the *distinction* first-class: `Patient?` says "may not exist", a sealed outcome says "caller must handle each alternative I list", and an exception says "this operation cannot continue normally". The pharmacy domain needs all three, and the failure taxonomy is directly judged ("failure handling" is a stated evaluation area of the challenge).

This kata deliberately ignores: HTTP status mapping (S03), database lookups (P-series), coroutines, and any framework. It also ignores the question of *why* the patient was not found — that nuance belongs to the exception path and to later reliability kadas.

## Learning objectives

- Write and consume `T?` return types; distinguish "may not exist" from "does not exist right now".
- Use safe calls (`?.`) and the Elvis operator (`?:`) without turning them into a style contest.
- Model expected command outcomes as a sealed interface and force exhaustive `when` handling.
- Place exceptions where the caller cannot recover normally, and keep them out of ordinary business flow.
- Ban `!!` by explaining what it costs.
- Test absence, each sealed outcome, and the exception path separately.

## Warm-up

Re-read `posts/series-1-kotlin/02-nullability-results-domain-errors.md`, sections "Nullable Types Are Explicit Absence" and "Sealed Results Represent a Closed Set of Outcomes". Before coding, write the three sentences that distinguish: (a) prescription id not found, (b) prescription found but cannot be approved, (c) database unavailable. Use them as your test names.

## System specification

**Scope in:** an in-memory `PatientRepository` (interface + implementation), a lookup service function, a `Patient` value, sealed lookup outcomes, and the tests.

**Scope out:** HTTP, persistence, approval commands (K03), inventory, concurrency, caching, real patient data.

**Functional requirements (minimal):**

- `findById(id)` returns `Patient?` — absence is expected and boring.
- A service-level lookup returns a sealed `PatientLookupOutcome` with `Found(patient)` and `NotFound` alternatives.
- A third path exists: the repository can throw a `PatientStoreUnavailable`-style exception for the "infrastructure is broken" case, and the service does **not** convert it into `NotFound`.
- Safe-call chains work against nested optional fields (e.g. patient address details) with a sensible default.
- Every path is covered by a test; `!!` appears nowhere in `src/main`.

**Constraints:** single module, in-memory, deterministic, no Spring. Kotlin idioms are the point; platform types are out of scope until S-series.

## Step-by-step code-along

**Step 1 — Nullable lookup**

**Do:** define a `Patient` data class (id, name, maybe `contact: Contact?` with an optional `phone` field) and a repository interface:

```kotlin
interface PatientRepository {
    fun findById(id: String): Patient?
}
```

Implement an in-memory version with a `Map<String, Patient>` — `map[id]` is already `Patient?`, no casting needed.

**Run:** test that a known id returns a patient and an unknown id returns `null`. Use `assertEquals` / `assertNull` from `kotlin.test`.

**Observe:** the Kotlin compiler now *forces* callers to handle the null. In Java, `Optional` was advisory and frequently ignored; here the signature is the contract. This is the "replace Optional deliberately" rule from the blog — you replaced it with a question mark.

**Kotlin idiom for Java veterans:** Kotlin has no checked exceptions and no `Optional` convention. The idiomatic move is: `T?` for absence at a lookup boundary. Not `Result<T>`, not exceptions — a nullable return, because "not found" is a normal, expected outcome.

**Step 2 — Consume it without `!!`**

**Do:** a service function that looks up a patient and returns either the patient or a default-safe representation. First try the safe-call chain over the nested `contact?.phone`:

```kotlin
fun contactLine(patient: Patient): String =
    patient.contact?.phone ?: "no phone on file"
```

Then write the "lookup + guard" pattern the blog shows:

```kotlin
fun lookUp(id: String): PatientLookupOutcome {
    val patient = repository.findById(id)
        ?: return PatientLookupOutcome.NotFound
    return PatientLookupOutcome.Found(patient)
}
```

**Run:** tests for the `?:` default and for the early-return guard (found and not-found cases).

**Observe:** the `?:` early-return is readable where a `if (patient == null) return ...` would be. You did not catch an exception and you did not use `!!` — the compiler did the check.

**Decision (if any):** early-return-with-Elvis vs `let { ... } ?: ...`. Pick one style for this kata; note that the blog explicitly warns against chains that hide which branch handles what (see K07 for the full treatment).

**Step 3 — Sealed outcomes**

**Do:** declare the sealed outcome next to the service, not inside the repository:

```kotlin
sealed interface PatientLookupOutcome {
    data class Found(val patient: Patient) : PatientLookupOutcome
    data object NotFound : PatientLookupOutcome
}
```

**Run:** write an exhaustive `when` over the outcome in a test helper or a tiny "describe" function, and test both branches. Then deliberately delete one branch and confirm the compiler errors.

**Observe:** `data object` (Kotlin 1.9+) is the idiomatic singleton for a no-payload alternative. The exhaustive `when` is compile-time documentation: adding an outcome later breaks every `when`, which is the property the blog calls "compiler tells us a new state has not been handled."

**Decision (if any):** `Found(patient)` carries the payload; `NotFound` has none. Would `NotFound(reason: String)` be better? Decide now — K03's outcomes will follow your chosen shape.

**Step 4 — The exception boundary**

**Do:** add a failure mode: a `PatientStoreUnavailable` exception type (a simple class extending `RuntimeException`), and an in-memory repository that throws it when a test flag is set (or when the id equals `"boom"`). In the service, do **not** wrap it into `NotFound`:

```kotlin
fun lookUp(id: String): PatientLookupOutcome {
    // lookup can throw PatientStoreUnavailable — let it propagate
    ...
}
```

**Run:** a test asserting that the exception propagates when the store is down, and a second test asserting `lookUp("unknown")` returns `NotFound` (not an exception) — prove the two paths are distinct.

**Observe:** if you had converted the exception to `NotFound`, the patient would receive a "no such prescription" when the real truth is "we could not answer". That lie is exactly the failure-handling signal an interviewer hunts for.

**Step 5 — Ban `!!` with a test**

**Do:** write one test that *demonstrates* the `!!` failure mode — construct a `Patient?` that is null, call `!!` on it in the test body, and assert it throws `NullPointerException`. Keep this test as documentation of why `!!` is a code smell, with a comment in the test name (`does not appear in main`).

**Run:** `./gradlew test`.

**Observe:** the NPE message says "null" with no context. Compare with the `require` messages from K01: a *named* failure beats a naked null dereference. That contrast is the interview takeaway.

## Try this

Deliberate experiment — **the NotFound lie**:

1. Temporarily change `findById` so an unknown id throws `PatientStoreUnavailable` instead of returning `null`.
2. Run your `lookUp("unknown")` test. It fails — because the service now propagates an exception where the contract promised `NotFound`.
3. Now flip it: make the repository throw, and change the service to catch-and-convert (`catch (e: PatientStoreUnavailable) { PatientLookupOutcome.NotFound }`).
4. Observe the *new* test that was green become a lie: a patient whose record cannot be read now appears "not found". Note in one sentence why "database down" must never masquerade as "no such patient".

## Trade-off fork

**Option A — sealed `PatientLookupOutcome` as the service contract (this kata's default).**
Pros: exhaustive handling, named alternatives, maps cleanly to future HTTP statuses (S03: `404` vs `409` vs `5xx`). Cons: you must maintain the hierarchy; every caller adds a branch; infrastructure failures are *not* in the type, so the boundary between "expected" and "broken" lives in discipline.

**Option B — exceptions for everything, including not-found.**
Pros: single mechanism, Java-familiar, `@NotFound`-style handling is one line. Cons: "not found" is not an exceptional state for a busy pharmacy backend — it is a routine API answer; every caller must remember to catch, and a forgotten catch turns a 404 into a 500. Also: the blog's mapping table (`404`/`409`/`400`/`5xx`) becomes inexpressible in the type system.

Pick one and write 3–5 lines justifying it for a 2–5 hour submission judged on failure handling. Name the lost benefit of the other. The kata's tests will encode your choice — that is the point.

## Hints

**Hint 1 (mild):** if `map[id]` feels like magic, recall `Map.get` returns `V?` in Kotlin. Every missing key *is* the nullable path — no `containsKey` dance, no `Optional.ofNullable(map.get(id))`.

**Hint 2 (stronger):** when the exhaustive-`when` compile error appears ("when expression must be exhaustive"), the compiler lists the missing branch. Read that message; it is the tutorial. For the `data object` syntax on older toolchains, `data class NotFound private constructor() : ...` works but loses the singleton — prefer upgrading the Kotlin plugin.

## Checkpoint / success criteria

You may leave when:

- `findById` returns `Patient?`; `lookUp` returns `PatientLookupOutcome`; the store failure throws and is *not* swallowed.
- Tests cover: found, not-found, phone default via `?:`, exhaustive-when compile check (or equivalent), exception propagation, and the `!!` documentation test.
- No `!!` and no `Optional` appear in `src/main`.
- You can write the three-sentence taxonomy (absence / expected alternative / infrastructure) from memory.

## Bottleneck & reflection questions

- A patient calls the pharmacy: the *status endpoint* says "prescription not found". What are the three possible truths, and which one did K02 teach you to never collapse?
- Why is `Patient?` right for the repository and a sealed outcome right for the service? What changes if the HTTP layer arrives (S03)?
- Your K01 `require` threw `IllegalArgumentException` for a bad quantity; your repository throws for "store down". Both are exceptions — what makes one acceptable and the other a boundary you must document?
- Where in `exercise_01_foundation.md` Milestone 2 does this exact taxonomy become HTTP status codes, and which outcome should map to `409`?
- If two pharmacist clients race an approval, is "invalid state" an absence, an expected alternative, or an infrastructure failure? (K03 answers it; note your answer first.)

## Handoff

- Next elective: `K03_workflow_state_machine.md` — the sealed-outcome discipline you just built becomes the transition API. (Optional detour: `K05_test_data_builders.md` later reuses your `Patient` fixture.)
- Related showcase exercise: `showcase_projects/pharmacy-fulfillment/exercise_01_foundation.md` — Milestones 1–2 error taxonomy and the status-`GET` correctness baseline.
- Interview line you should be able to say aloud: "I use three distinct models for failure: nullable returns for absence at lookup boundaries, sealed outcomes for expected business alternatives callers must handle exhaustively, and exceptions only for failures the current operation cannot continue past — I never convert 'the store is down' into 'no such patient', because the patient experience and the retry behavior would both lie."

## Optional stretch

Add a second service function, `updateContact(patientId, newPhone): UpdateOutcome`, that first does the nullable lookup, then performs the update only if the patient exists. Keep a side channel (a mutable map or a captured list) that records update attempts, and assert that an update for an unknown patient records *zero* attempts — proving the guard, not just the return value.
