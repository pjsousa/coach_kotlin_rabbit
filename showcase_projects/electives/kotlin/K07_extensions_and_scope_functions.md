# K07 Extensions and Scope Functions — Code-Along Elective

## Objective

Write domain extension functions and use `let`/`also`/`apply`/`run` with judgment — including the harder skill of *not* using them — by refactoring a Java-style service method and proving the refactor with tests.

## Time box

~1 hour. Core (Wave 2, anytime after K01). The last 15 minutes are the anti-scope-function stance; do not skip them.

## Prerequisites

- `K01_prescription_value_objects.md` — the domain values you will extend.
- `K02_nullable_patient_lookup.md` recommended (the safe-call + `let` interplay needs nullable values to be honest).
- Showcase position: before `exercise_01_foundation.md` Milestone 4 — Exercise 1's Kotlin guidance explicitly warns against "nested scope-function chains that hide transaction ownership", and this kata is where you learn to see them.

## Blog & curriculum links

- Primary: `posts/series-1-kotlin/01-kotlin-for-java-developers.md` — "Do not turn every block into a clever expression" and the practical translation rules.
- Secondary: `posts/series-1-kotlin/05-showcase-kotlin-prescription-domain.md` — "Prefer clear named operations over clever scope-function chains in transaction-heavy workflows."
- Coach-assessment gap: Day 1/3 idioms ("effective Kotlin") and the Day 4 reviewer stance ("nested scope-function chains that hide transaction ownership" is called out verbatim in `exercise_01_foundation.md`).

## Background & motivation

`apply`, `run`, `let`, `also` are the most-misused four functions in Kotlin onboarding. A Java veteran sees "lambda that receives the receiver" and sprinkles `apply` everywhere a builder existed, or writes `patient?.let { save(it) } ?: throw ...` and calls it idiomatic. This kata exists so the judgment — *when each scope function earns its keep and when an explicit statement is better* — is exercised in the pharmacy domain before Exercise 1, where the blog itself warns these chains hide transaction ownership.

It deliberately ignores: DSL construction, receiver lambdas for building JSON, contract details, and the Android-style ceremony. One objective: fluent-but-judged use, with the transaction-clarity lesson internalized.

## Learning objectives

- Write and test extension functions that read like domain verbs (`prescription.totalQuantity()`).
- Distinguish `let` (transform result), `also` (side effect), `apply` (configure receiver), `run` (compute with receiver scope) — and `with`.
- Recognize the null-safety chain `x?.let { } ?: fallback` and its pitfalls.
- Refactor a Java-style method into idiomatic Kotlin, then *back* into explicit statements where clarity wins.
- Explain in an interview why a transaction-heavy operation should not be a scope-function chain.

## Warm-up

Read `posts/series-1-kotlin/01-kotlin-for-java-developers.md`, "Expressions Make Control Flow Visible" and the "Practical Translation Rules". Then write the four-function cheat sheet from memory: "let = ___, also = ___, apply = ___, run = ___." Fill the blanks before reading the hints.

## System specification

**Scope in:** extension functions over `Prescription`/`Patient`, a Java-flavored service method refactored in three passes, and tests proving behavior is unchanged across passes.

**Scope out:** DSLs, receiver lambdas for serialization, framework interactions, complex contracts, anything transaction-like beyond one demonstration.

**Functional requirements (minimal):**

- Extensions: `Prescription.totalQuantity()`, `Prescription.isWaitingForApproval()`, `List<PrescriptionItem>.mergedQuantity(medicationId)`.
- A `SubmissionWorkflow`-style function with the shape "lookup → validate → record → notify" written three ways: Java-style, chain-style, explicit-statement style.
- Tests: one test suite that runs unchanged against all three implementations (behavior pin).
- A deliberate demonstration of a `?.let { } ?: run { }` bug caught by a test.

**Constraints:** single module, in-memory, no Spring, no `var`, tests unchanged across refactors.

## Step-by-step code-along

**Step 1 — Extension functions as domain verbs**

**Do:** add `src/main/kotlin/pharmacy/extensions.kt`:

```kotlin
fun Prescription.totalQuantity(): Int = items.sumOf { it.quantity }

fun Prescription.isWaitingForApproval(): Boolean =
    status == PrescriptionStatus.AWAITING_APPROVAL
```

**Run:** tests for both, including a multi-item prescription and a non-waiting status.

**Observe:** an extension function compiles to a static method with the receiver as first parameter — the caller reads `prescription.totalQuantity()` but there is no member to maintain. Java veterans: this is "utility class" without the utility class; and it *is* testable in isolation.

**Kotlin idiom for Java veterans:** put extensions next to the type they extend (or in a clearly named file), not in a `*Utils` bucket. The signature `fun Prescription.totalQuantity(): Int` is documentation: "computable from a prescription, not stored on it."

**Decision (if any):** member method vs extension function for `totalQuantity`. Members own behavior and invariants; extensions own derived computations that must not hide state. Pick per function and justify in one line each.

**Step 2 — The Java-style baseline**

**Do:** write `SubmissionWorkflow.submit(...)` the way your Java self would:

```kotlin
class SubmissionWorkflow(private val repository: PrescriptionRepository) {
    fun submit(request: PrescriptionRequest): SubmissionOutcome {
        var prescription = Prescription(...)          // build from request
        // validate, print/log, save, notify — imperative statements
        ...
    }
}
```

Keep it deliberately un-Kotlin: an empty `var`, explicit intermediate names, side effects inline.

**Run:** write the behavior-pin tests first (valid submission → outcome `Submitted`, persisted count +1, notify called once; duplicate id → `Rejected` outcome, no notify). These tests will not change for the rest of the kata.

**Observe:** you have a stable contract. Everything that follows is refactoring with a safety net — the test suite is the referee, which is exactly how the blog says to practice.

**Step 3 — The chain-style pass (do it, feel the pull)**

**Do:** rewrite `submit` using `apply` to assemble the prescription and `also` for the side effects:

```kotlin
fun submit(request: PrescriptionRequest): SubmissionOutcome {
    val prescription = Prescription(
        id = request.id,
        // ...
    ).apply { /* ... */ }        // assemble
        .also { repository.save(it) }
        .also { notify(it) }
    return SubmissionOutcome.Submitted(prescription)
}
```

Make the duplicate-id path use `run` and the fallback `let`:

```kotlin
fun submit(request: PrescriptionRequest): SubmissionOutcome =
    repository.find(request.id)
        ?.let { SubmissionOutcome.Rejected(it) }
        ?: run { /* save + notify + Submitted */ }
```

**Run:** the pin tests. Green — but you should already feel the discomfort: which `also` runs first, what happens if `save` throws, and is the `?: run { }` branch obvious to a reviewer?

**Observe:** the chain is shorter and the compiler is happy, and that is precisely the trap: correctness is preserved *here*, but the reader cannot see control flow. This is the blog's "nested scope-function chains can hide error behavior" in the flesh.

**Decision (if any):** keep a *single* `also` for a pure side effect (e.g. logging) if you find it readable — the point is not to ban the functions, it is to place them where the reader cannot misread.

**Step 4 — The explicit pass**

**Do:** rewrite `submit` as explicit statements in the shape the blog and `exercise_01_foundation.md` endorse:

```kotlin
fun submit(request: PrescriptionRequest): SubmissionOutcome {
    val existing = repository.find(request.id)
    if (existing != null) {
        logger.log("duplicate submission {}", request.id)
        return SubmissionOutcome.Rejected(existing)
    }
    val prescription = Prescription(...)
    repository.save(prescription)
    notify(prescription.id)
    return SubmissionOutcome.Submitted(prescription)
}
```

**Run:** the pin tests again — still green.

**Observe:** the three implementations are behaviorally identical and the tests never changed. The explicit version wins on *reviewability*: the early return, the side-effect ordering, and the failure semantics are visible without tracing receiver scope. This is the exact stance Exercise 1 demands.

**Step 5 — Keep `let` where it belongs**

**Do:** find one place where `let` is genuinely the right tool — a nullable transformation that maps to a value, e.g.:

```kotlin
fun Prescription?.toSubmittedAtText(): String =
    this?.let { it.submittedAt.toString() } ?: "unknown"
```

**Run:** tests for both null and non-null receivers.

**Observe:** `let` transforms; `also` runs side effects; `apply` configures; `run` computes in receiver scope. The cheat sheet from the warm-up is now experience, not memorization.

## Try this

Deliberate experiment — **the `let` fallback bug**:

1. Write this chain: `repository.find(id)?.let { saved -> notify(saved.id) } ?: SubmissionOutcome.NotFound(id)`.
2. Think carefully: when `notify` returns... a `SubmissionOutcome`? No — make `notify` return `Unit`. What does `?.let { ... }` return when `find` is null? (Kotlin answer: `null`, because `Unit` is still `Unit`.)
3. Now make `notify` return a *nullable* value — `Boolean?` — and observe the chain's type change: `?: ` now fires even on success-with-null-notify.
4. Expected observation: the fallback fires on the wrong condition, and a test that only checks the success path is green while the real bug (false `NotFound`) sails past. Record the lesson: `?: ` after `?.let` only "means not-found" if the let body can never return null.

## Trade-off fork

**Option A — explicit statements at business boundaries (this kata's default).**
Pros: control flow visible, transaction/side-effect ownership obvious, reviewer-friendly, matches `exercise_01_foundation.md` guidance verbatim ("nested scope-function chains that hide transaction ownership"). Cons: more lines; the reader carries state manually; purely derived values must be named.

**Option B — maximal idiomatic chains everywhere.**
Pros: compact, "looks like modern Kotlin" to a Java reviewer, fun to write, excellent for pure transformations and value assembly. Cons: control flow hides in receiver scope; a throw inside `also` or a null from `let` changes semantics invisibly; the blog's own warning applies — in a workflow with persistence, chains obscure *what owns the transaction*.

Choose one as your *default policy* and write 3–5 lines justifying it for a submission judged on simplicity, naming the lost benefit — then add the one-line carve-out for when you would break your own policy.

## Hints

**Hint 1 (mild):** `apply` returns the receiver; `also` returns the receiver; `let` and `run` return the block result. If you cannot say which of the four a snippet returns in five seconds, that snippet is wrong for your codebase.

**Hint 2 (stronger):** for the Try-this experiment, keep `notify`'s return type explicit in a comment so the chain's type change is visible before you run it. And when a chain grows past two `?`-operators, that is the refactor signal — flatten it.

## Checkpoint / success criteria

You may leave when:

- Extension functions exist and are tested; the Java-style baseline compiles and the pin tests pass.
- The chain-style and explicit-style passes both keep the pin suite green.
- The `let`-fallback bug is demonstrated by a failing-then-passing test and the lesson is in your notes.
- You can state the default policy from the fork and its carve-out without notes.
- No scope-function chain longer than two links survives in `src/main`.

## Bottleneck & reflection questions

- The pin suite stayed green across three rewrites. What does that prove about the tests, and what does it *not* prove about the readability claims?
- Where in `exercise_01_foundation.md` Milestone 4 does the explicit-vs-chain choice directly affect the reviewer's ability to see the database transaction boundary?
- A patient-facing "status line" is computed via `when` (K03) — would you write it as an extension function or a member? What does that decision say about where derived facts live?
- `also { repository.save(it) }` looks innocent. What happens to the chain when `save` throws — and how does that failure surface differently in the explicit version?
- Which of the four scope functions would you allow in a code review of a *transaction-owning* method, and which two would you flag? (There is a defensible answer; be ready to argue it.)

## Handoff

- Next elective: `../spring/S01_hello_prescription_api.md` — Wave 1's first Spring elective, where these extension functions become the service layer's verbs. (If you skipped `K06_collections_and_sequences.md`, it is still available anytime.)
- Related showcase exercise: `showcase_projects/pharmacy-fulfillment/exercise_01_foundation.md` — the Kotlin guidance section is this kata as a review standard.
- Interview line you should be able to say aloud: "I use extension functions to give domain types readable verbs, and I place `let`/`also`/`apply`/`run` only where the receiver scope aids clarity — in transaction-owning workflow code I deliberately write explicit statements, because a scope-function chain that hides whether the database commit and the notify call are ordered is a review failure, not an idiom."

## Optional stretch

Build a small, testable `PatientNotificationMessage` from a prescription using exactly *two* scope functions where each is defensible (e.g. `run` to assemble fields, `also` to log the outcome), then have a reviewer — or your future self, one week later — explain each scope function's role from memory. If they cannot, the code is still wrong no matter how idiomatic it looks.
