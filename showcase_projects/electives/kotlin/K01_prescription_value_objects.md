# K01 Prescription Value Objects — Code-Along Elective

## Objective

Build the immutable value model of the prescription domain — `Medication`, `PrescriptionItem`, `Prescription` — as Kotlin `data class`es, using `val`, `require` validation, structural equality, and `copy` for change, all proven by plain Kotlin tests with zero Spring.

## Time box

~1 hour. Core (Wave 1, first file). Do not extend it; K02–K03 depend on it being done, not perfect.

## Prerequisites

- Nothing before it in the elective chain. This is the entry point of Track A.
- JDK 17+ and a working Gradle (8.x) install, or IntelliJ with Kotlin plugin. No Docker yet — this elective is in-memory only.
- Showcase position: before `showcase_projects/pharmacy-fulfillment/exercise_01_foundation.md` Milestone 1. Everything you model here is the vocabulary that exercise will persist and expose.

## Blog & curriculum links

- Primary: `posts/series-1-kotlin/01-kotlin-for-java-developers.md` (val/var, data classes, "practical translation rules").
- Secondary: `posts/series-1-kotlin/03-state-machines-with-sealed-types.md` (why a `copy(status = ...)` on the value is a *preview* of the state machine, not the machine).
- Coach-assessment gap: "Kotlin syntax … must be learned through implementation" (Day 1: expressions, functions, classes, properties, constructors, data classes) — this file is the Day 1 drill.

## Background & motivation

This kata exists because the single highest-risk gap in the plan is Kotlin friction: if the language itself is a speed bump, the system-design signal in the showcase exercises never shows up. The cheapest way to burn that down is a small, pure, value-only model where every line compiles to something you can test in milliseconds.

It deliberately ignores: persistence, HTTP, state transitions (K03), nullability policy (K02), collections (K06), and every framework. One primary learning objective only: what a Kotlin value object is and when it is the right tool. A Java veteran's instinct will be to reach for a mutable bean with getters/setters; this kata forces the value-model habit in a domain small enough to hold in your head.

## Learning objectives

- Read and write a `data class` with a primary constructor-as-property-declaration.
- Explain what `val` guarantees (reference reassignment) and what it does **not** guarantee (deep immutability).
- Enforce invariants at construction time with `require` (vs a Java `IllegalArgumentException` by hand).
- Use `copy` with named arguments for safe, local change instead of mutation.
- Predict `equals`/`hashCode`/`toString` behavior from structural equality, not identity.
- Run a minimal Kotlin/Gradle test suite without Spring.

## Warm-up

Read `posts/series-1-kotlin/01-kotlin-for-java-developers.md`, section "Start With Readability, Not Cleverness" and "`val` Is the Default". Then answer in one sentence each: (1) does `val` freeze the object graph? (2) is a `List` property deeply immutable? Write both answers before opening the editor.

## System specification

**Scope in:** an in-memory value model for medication, prescription lines, and a prescription aggregate value; construction validation; equality tests; one `copy`-based transition-like operation.

**Scope out:** patient entity (K02), status/state machine (K03), inventory math (K04), collections pipelines (K06), any framework, files, database, network.

**Functional requirements (minimal):**

- `Medication(id, name)` and `PrescriptionItem(medicationId, quantity)` values.
- `Prescription(id, patientId, items, submittedAt)` value.
- Construction rejects: blank IDs, a non-positive quantity, and a prescription with zero items.
- Two prescriptions with equal fields are `equal`; a changed field makes them unequal.
- An "items replaced" operation exists via `copy` and does not mutate the original.

**Constraints:** single Gradle module, Kotlin JVM only (`org.jetbrains.kotlin.jvm`), `kotlin("test")` for assertions, in-memory, deterministic. No `var` unless a step explicitly asks for one (none will).

## Step-by-step code-along

**Step 1 — Bare Kotlin module**

**Do:** create `showcase_projects/electives/kotlin/k01/` (or your own scratch directory — the exercises are throwaway) with a `settings.gradle.kts` naming the project and a `build.gradle.kts` applying:

```kotlin
plugins {
    kotlin("jvm") version "2.1.x"
}
dependencies {
    testImplementation(kotlin("test"))
}
```

Add the `kotlin { jvmToolchain(17) }` block. Put sources under `src/main/kotlin` and `src/test/kotlin`.

**Run:** `./gradlew build` — expect a green build with "BUILD SUCCESSFUL" and no sources yet.

**Observe:** the `kotlin("test")` dependency maps to a Kotlin-aware JUnit 5 bridge; you write `kotlin.test.*` assertions and get them without a separate framework import.

**Decision (if any):** Kotlin JVM plugin `2.x` vs whatever Gradle suggests — pick the newest stable; this is a scratch project, lockfile churn is noise.

**Step 2 — First data class**

**Do:** write `PrescriptionItem` and `Medication` as `data class`es in one file each under `src/main/kotlin/pharmacy/`.

```kotlin
data class Medication(val id: String, val name: String)
data class PrescriptionItem(val medicationId: String, val quantity: Int)
```

Then add validation so `PrescriptionItem("amoxicillin", 0)` cannot exist:

```kotlin
data class PrescriptionItem(val medicationId: String, val quantity: Int) {
    init {
        require(medicationId.isNotBlank()) { "medicationId must not be blank" }
        require(quantity > 0) { "quantity must be positive, was $quantity" }
    }
}
```

**Run:** `./gradlew test` — add a test in `src/test/kotlin` that asserts construction with `quantity = 0` throws with the message containing `"positive"`.

```kotlin
@Test
fun `zero quantity is rejected`() {
    val error = assertFailsWith<IllegalArgumentException> { PrescriptionItem("amoxicillin", 0) }
    // assert error.message contains "positive"
}
```

**Observe:** `require` is a function, not a statement — it throws `IllegalArgumentException` with your message. Your Java self may want a custom exception class; resist here.

**Kotlin idiom for Java veterans:** the primary constructor *is* the property declaration — there is no field, getter, and constructor triplication. The `init` block is the constructor body that runs after property initialization. Prefer `require` for construction invariants because it fails fast with a message at the point the invalid value enters the system.

**Step 3 — The aggregate value**

**Do:** write `Prescription` with four `val` properties including `items: List<PrescriptionItem>` and `submittedAt: Instant`. Validate non-empty items in an `init` block.

```kotlin
data class Prescription(
    val id: String,
    val patientId: String,
    val items: List<PrescriptionItem>,
    val submittedAt: Instant,
) {
    init {
        require(id.isNotBlank()) { ... }
        require(items.isNotEmpty()) { "a prescription needs at least one item" }
    }
}
```

**Run:** `./gradlew test`; add the empty-items rejection test.

**Observe:** the compiler now blocks a no-items prescription at the type boundary. Every construction site gets the rule for free — no `validate()` call the caller can forget.

**Decision (if any):** `Instant` (java.time) vs a `String` timestamp — use `Instant.now()` as the default everywhere; ordering and timezone semantics arrive later in P-series.

**Step 4 — Structural equality, proven**

**Do:** write three tests: (1) two prescriptions built from identical values are equal and share hashCode; (2) changing one field makes them unequal; (3) two distinct objects holding the *same* list instance still compare equal.

**Run:** `./gradlew test`.

**Observe:** `data class` generates `equals`/`hashCode`/`toString`/`copy` from the primary-constructor properties. Note `toString` is safe for logs — it prints fields, not object addresses. For Java veterans: this is the Kotlin answer to writing `equals`/`hashCode` by hand; the generated versions are correct for value semantics, and that correctness is *tested*, not assumed.

**Decision (if any):** include `submittedAt` in equality or exclude it (e.g. by moving it out of the constructor)? Pick one and note the consequence for "is this the same prescription?" semantics — you will defend this in an interview.

**Step 5 — Change without mutation**

**Do:** implement an operation that returns a *new* prescription with a revised item list, leaving the original untouched:

```kotlin
fun Prescription.withItems(newItems: List<PrescriptionItem>): Prescription =
    copy(items = newItems)
```

**Run:** add a test asserting the original's `items` is unchanged and the returned value differs.

**Observe:** `copy` with named arguments — the data-class equivalent of "clone and tweak". This is the mechanical basis of K03's `approve()`/`reject()` operations.

## Try this

Deliberate experiment — **the shallow-copy trap**:

1. Build a `Prescription`, then `val tweaked = original.copy(items = original.items + PrescriptionItem(...))`.
2. Now mutate *the same list instance* through the back door: `(original.items as MutableList).add(...)` — or better, construct the original with a `mutableListOf(...)` cast up to `List`.
3. Observe: the "immutable" prescription changed anyway, because `List` is a read-only *interface*, not a guarantee.

Expected outcome: your mental model shifts from "val means immutable" to "val means the reference is fixed — immutability must be designed at each boundary." That sentence is an interview answer. Write it down.

## Trade-off fork

**Option A — data class for the aggregate (`Prescription`).**
Pros: generated equality/hashCode/toString/copy, pattern-matching via componentN in later kadas, zero ceremony. Cons: you do not control the generated methods; adding a field silently changes equality for every caller; `copy` can bypass a `require` you forgot to re-check in a public helper.

**Option B — regular class with a private constructor and factory functions, hand-written `equals`/`hashCode`.**
Pros: full control over invariants and equality; the "domain authority" flavor reads well in an interview. Cons: you now maintain equality/toString by hand — exactly the bug surface data classes exist to remove; more lines for the same test result.

Choose one and write 3–5 lines justifying it for *this* kata (a two-to-five-hour interview submission, judged on simplicity and failure handling). State the lost benefit of the other option explicitly. There is no official winner; you must be able to say the words "data class is the right tool at a value boundary, and I'd stop using it the moment lifecycle or identity semantics arrived."

## Hints

**Hint 1 (mild):** validation placement — a `require` in the `init` block runs on *every* construction path, including `copy`. Put a wrong-by-design `require` in the aggregate and watch `copy` trip it. That is a feature: new values are validated, not just constructed ones.

**Hint 2 (stronger):** if a test feels awkward because `Instant.now()` differs between two constructions, pass the `Instant` explicitly in one test instead of weakening the data class. Default parameter values (`submittedAt: Instant = Instant.now()`) are idiomatic for test ergonomics — the blog's translation rule 3 says: data classes for values, not automatically for every class.

## Checkpoint / success criteria

You may leave this kata when:

- `./gradlew test` is green with tests for: valid construction, blank ID rejection, non-positive quantity rejection, empty-items rejection, structural equality (equal / unequal / shared-list-instance), and `withItems` non-mutation.
- You can say, without notes, what `val` does and does not guarantee.
- The word `var` does not appear in `src/main`.

## Bottleneck & reflection questions

- A patient-facing status view is a *read* of the prescription value: why does structural equality make "has the prescription changed?" trivial to test?
- If `List<PrescriptionItem>` is read-only but not deeply immutable, where in the future system does real protection come from? (Name the layer: PostgreSQL constraints.)
- A `require` failure is an `IllegalArgumentException` — a Java veteran reaches for it at construction; where in `showcase_projects/pharmacy-fulfillment/exercise_01_foundation.md` does that class of failure need to become a *domain outcome* instead?
- Why would `copy`-based transition methods be a *preview* of the K03 state machine, and what rule do they still not encode?
- What happens to `toString` output in logs when a prescription carries a patientId — is that a PII concern you should note now? (File it; A15 later.)

## Handoff

- Next elective: `K02_nullable_patient_lookup.md` (lookup returns `Patient?`, sealed outcomes). After K01 you may *also* do `K06_collections_and_sequences.md` or `K07_extensions_and_scope_functions.md` in any order.
- Related showcase exercise: `showcase_projects/pharmacy-fulfillment/exercise_01_foundation.md` — Milestone 1/4 domain vocabulary and "Kotlin guidance" section.
- Interview line you should be able to say aloud: "I model the prescription as an immutable data class: `val` pins the reference, `require` enforces invariants at construction, `copy` gives me change-without-mutation, and structural equality makes value comparisons testable — the database, not the type system, is what guards shared state later."

## Optional stretch

Define a `@JvmInline value class MedicationId(val raw: String)` and refactor `PrescriptionItem.medicationId` and `Prescription.id` to use it. Observe what breaks (everywhere that passed a raw `String`) and what is now impossible (swapping a patient id for a medication id). One paragraph in your notes: when would value classes hurt rather than help at a JSON boundary?
