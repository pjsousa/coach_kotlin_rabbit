# K06 Collections and Sequences — Code-Along Elective

## Objective

Write idiomatic collection pipelines (`map`, `filter`, `groupBy`, `sortedBy`, `sumOf`, `associateBy`) over prescription and inventory data, then learn when `asSequence()` matters — and when a Java-stream-style pipeline is cargo cult that hurts readability.

## Time box

~1 hour. Core (Wave 2, but free to do anytime after K01). Keep it to one hour; the insight is the *judgment*, not the API list.

## Prerequisites

- `K01_prescription_value_objects.md` — the `Prescription`/`PrescriptionItem` values and test harness.
- Nothing else; this runs in parallel with K02/K03 if you like.
- Showcase position: before `exercise_01_foundation.md` Milestone 4 — the status view and pharmacist queue are these pipelines in disguise.

## Blog & curriculum links

- Primary: `posts/series-1-kotlin/01-kotlin-for-java-developers.md` — "Collections: Read-Only Does Not Mean Persistent" and translation rule 5 ("Keep collection mutability visible").
- Secondary: `posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md` — evidence-oriented thinking carries into pipeline tests.
- Coach-assessment gap: Day 3 ("collection transformations", "immutable collections, collection performance"); also the Day 1 prompt's "accidental Java idioms".

## Background & motivation

A Java veteran writes `.stream().map(...).collect(Collectors.toList())` from muscle memory. Kotlin gives you `map { }` on the list itself, and the temptation is to translate streams literally — `.asSequence().filter{}.map{}.toList()` everywhere. This kata exists to make that translation *deliberate*: the eager functions are the default because pharmacy collections are small and the readable pipeline is the product, and sequences are the exception when laziness actually buys something (short-circuiting a big source, chains that would allocate repeatedly).

It deliberately ignores: `fold`/`reduce` beyond one example, parallel streams (Kotlin's `parallelStream` is a Java interop escape hatch — out of scope), performance tuning, and the actual queue pages in `exercise_01_foundation.md`. One objective: pipeline fluency plus the "when to use which" reflex.

## Learning objectives

- Chain `map`, `filter`, `groupBy`, `sortedBy`, `sumOf`, `associateBy` over domain lists without `Collectors` and without `stream()`.
- Explain the eager default and name the costs of `asSequence()` (iteration overhead, terminal-operation requirement).
- Prove with a counter-instrumented experiment where laziness actually changes work done.
- Spot and avoid the Java-stream cargo cult: sequence pipelines that buy nothing.
- Write read-only pipelines that never mutate an input list.

## Warm-up

Read `posts/series-1-kotlin/01-kotlin-for-java-developers.md` "Collections: Read-Only Does Not Mean Persistent". Then translate this Java snippet to Kotlin on paper, *without* streams: "the total quantity of Amoxicillin across all prescriptions in a list." Do not look at the answer; you are warming the muscle, not checking correctness.

## System specification

**Scope in:** pipeline functions over `List<Prescription>` and inventory maps — totals per medication, grouping by status, ordering a pharmacist queue, building lookup maps; one laziness experiment.

**Scope out:** mutable collections as a design goal, parallel processing, infinite data, persistence, pagination (keyset pagination is P06's job — note it, do not build it).

**Functional requirements (minimal):**

- `totalQuantityByMedication(prescriptions): Map<String, Int>` — merge across all prescriptions, with duplicate lines handled like K04's rule.
- `prescriptionsByStatus(prescriptions): Map<PrescriptionStatus, List<Prescription>>` via `groupBy`.
- `pharmacistQueue(prescriptions): List<Prescription>` — only `AWAITING_APPROVAL`, ordered by `submittedAt` ascending, bounded to N.
- `inventoryLookup(catalog): Map<String, Medication>` via `associateBy` for O(1) name lookups.
- A laziness experiment (Try this) with counter evidence.

**Constraints:** single module, in-memory, read-only outputs, no `stream()`, no mutation of inputs, no Spring.

## Step-by-step code-along

**Step 1 — Basic mapping and filtering**

**Do:** build a sample list of prescriptions with your K05-style builder (or a small local `samplePrescriptions()` helper). Implement:

```kotlin
fun medicationIds(prescriptions: List<Prescription>): List<String> =
    prescriptions.flatMap { it.items }.map { it.medicationId }.distinct()

fun awaitingApproval(prescriptions: List<Prescription>): List<Prescription> =
    prescriptions.filter { it.status == PrescriptionStatus.AWAITING_APPROVAL }
```

**Run:** tests asserting distinctness, ordering stability, and that inputs were not mutated (`assertEquals` against the original list).

**Observe:** `flatMap` on a list of prescriptions replaces the nested `for` + `addAll`. Kotlin's collections are read-only at the boundary — your function *returns* a new list; the input never changes. The blog's rule 5 ("keep collection mutability visible") is satisfied by the signature itself.

**Kotlin idiom for Java veterans:** no `stream()`, no `collect(toList())` — `map`/`filter`/`flatMap` are member extension functions on `Iterable` and return fresh lists eagerly. The pipeline is the object; there is no terminal operation step.

**Step 2 — Grouping and summing**

**Do:** implement the two aggregation functions:

```kotlin
fun prescriptionsByStatus(prescriptions: List<Prescription>): Map<PrescriptionStatus, List<Prescription>> =
    prescriptions.groupBy { it.status }

fun totalQuantityByMedication(prescriptions: List<Prescription>): Map<String, Int> =
    prescriptions
        .flatMap { it.items }
        .groupingBy { it.medicationId }
        .fold(0) { acc, item -> acc + item.quantity }
```

**Run:** tests including the duplicate-line case (two Amoxicillin lines must sum, matching K04's merge rule) and an empty-list input.

**Observe:** `groupingBy` + `fold` is the Kotlin-idiomatic reduce-by-key; a Java veteran would reach for a `Collectors.groupingBy` with a downstream `summingInt`. Both work; the Kotlin form reads left-to-right.

**Decision (if any):** `groupingBy { }.fold(0) { ... }` vs `groupBy { } + mapValues { it.value.sumOf { ... } }` — pick one, keep the choice consistent, and note which one preserves order guarantees you rely on.

**Step 3 — The pharmacist queue**

**Do:** implement `pharmacistQueue(prescriptions, limit = 10)` — filter, sort by `submittedAt` ascending, take `limit`:

```kotlin
fun pharmacistQueue(prescriptions: List<Prescription>, limit: Int = 10): List<Prescription> =
    prescriptions
        .filter { it.status == PrescriptionStatus.AWAITING_APPROVAL }
        .sortedBy { it.submittedAt }
        .take(limit)
```

**Run:** tests: order is oldest-first; bound respected; non-awaiting prescriptions excluded; empty input yields empty output.

**Observe:** this function *is* the pharmacist queue of `exercise_01_foundation.md` Milestone 2, without a database. `sortedBy` picks the comparable property and you never wrote a comparator.

**Decision (if any):** `sortedBy` ascending vs `sortedByDescending` — the queue's "oldest first" is a *patient-experience* decision (waiting time fairness). Note it; interviewers ask.

**Step 4 — Lookup maps**

**Do:** build `inventoryLookup(catalog: List<Medication>): Map<String, Medication>` with `associateBy { it.id }`, and a `sumOf` usage for a per-prescription total.

**Run:** tests including "lookup an id not in the catalog returns null" — proving `associateBy` yields nullable values by design.

**Observe:** `Map<String, Medication>` with `get` returning `Medication?` is the K02 nullable lookup in collection form — the types compose.

## Try this

Deliberate experiment — **laziness with a counter**:

1. Create a `List` of 100,000 fake prescriptions (a simple generator; keep it in the test).
2. Instrument every stage with a side effect: `.map { alsoCount("map") ... }` or simply count invocations via a mutable counter.
3. Run `prescriptions.filter{...}.map{...}.take(3)` eagerly and count invocations; then run the same pipeline as `.asSequence().filter{...}.map{...}.take(3)` and count again.
4. Expected observation: the eager version processes all 100,000 at every stage (3 stages × 100k calls); the sequence short-circuits — roughly 3×fewer invocations because `take(3)` stops the chain early.
5. Now run the *same* experiment where the pipeline ends in `.toList()` with no `take`: eager and sequence call counts converge. Record the one sentence: sequences only pay when the pipeline short-circuits or reuses a lazy source.

## Trade-off fork

**Option A — eager functions as the default (this kata's default).**
Pros: simplest mental model, one allocation per stage is fine for pharmacy-sized data (tens to low thousands of rows), debugging shows intermediate lists, matches the blog's default tone. Cons: on genuinely large or expensive sources (100k+ rows, per-element I/O) the eager chain allocates every intermediate.

**Option B — `asSequence()` everywhere a Java veteran would stream.**
Pros: lazy, chain preserves the "stream" muscle memory, `take`/`first` short-circuit. Cons: sequences introduce per-element iterator overhead, debugging is harder (no intermediate lists), and for small data it is pure ceremony — the cargo cult the catalog warns about.

Choose one and write 3–5 lines justifying it for the *pharmacy challenge's* data sizes (a single store's queue, not a data warehouse), naming the lost benefit. State the condition under which you would switch the other way — that condition is the interview answer.

## Hints

**Hint 1 (mild):** if a pipeline reads like a sentence, it is right; if you need a comment to explain it, split it into named local values. `val awaiting = prescriptions.filter { ... }` then `val oldestFirst = awaiting.sortedBy { ... }` is senior-readable and testable between stages.

**Hint 2 (stronger):** for the counter experiment, prefer `also { }` for the side effect (`it.also { stageA++ }`) so the pipeline's return type is untouched — that is the same discipline you will see in K07. And remember `distinct()` uses structural equality — for a `Medication` data class that is content equality, which is what you want.

## Checkpoint / success criteria

You may leave when:

- All five pipeline functions exist with passing tests, including empty-input and duplicate-line cases.
- No input list is mutated; outputs are read-only.
- The laziness experiment produces counter evidence (numbers in the test output or a recorded observation) and a one-sentence conclusion.
- No `stream()`, no `Collectors`, no `toMutableList()` appears in your pipelines.
- You can answer "eager or sequence?" with a decision rule, not a vibe.

## Bottleneck & reflection questions

- The pharmacist queue sorts by `submittedAt` — what changes about *fairness* if two submissions share a timestamp? (File the thought; P06's keyset pagination resolves it properly.)
- `groupBy` produces a `Map<PrescriptionStatus, List<Prescription>>`. Where does that map become the patient-facing status view of `exercise_01_foundation.md`, and what must *never* come from it (hint: current status must come from the row, per Milestone 3)?
- If a pipeline is slow, is the fix sequences or a better algorithm? Give the challenge-flavored example (queue read shape → index, P06).
- `associateBy` returns nullable values. Does that surprise you given K02's "nullable for absence" rule — or is it the same rule in disguise?
- Where would a Java veteran's `stream().parallel()` be a trap in this codebase, and what does the blog's "read-only does not mean persistent" imply about sharing these lists across threads?

## Handoff

- Next elective: `K07_extensions_and_scope_functions.md` (the natural sibling; pipelines and scope functions are the two Java-veteran idioms), then `../spring/S01_hello_prescription_api.md`.
- Related showcase exercise: `showcase_projects/pharmacy-fulfillment/exercise_01_foundation.md` — Milestone 2 (queue contract) and Milestone 4 (status view) are these pipelines.
- Interview line you should be able to say aloud: "I default to eager Kotlin collection functions because pharmacy queues are small and readable pipelines are the product; I reach for `asSequence` only when a chain short-circuits or the source is large, and I proved that boundary with a counter experiment rather than cargo-culting Java streams."

## Optional stretch

Implement `topMedications(prescriptions, n)`: the n most-prescribed medications by total quantity, with a stable tie-break on name. Use `groupingBy`, `fold`, `sortedWith(compareByDescending<...>{...}.thenBy{...})`, and `take(n)`. Add a test where two meds tie and assert the deterministic order — then write one paragraph on why deterministic tie-breaks matter for a pharmacist's review screen.
