# K04 Inventory Pure Functions — Code-Along Elective

## Objective

Implement inventory reserve/release as pure functions over an immutable inventory snapshot — atomic all-or-nothing for multi-line prescriptions, sealed outcomes for insufficient stock — and prove the math with focused unit tests.

## Time box

~1.5–2 hours. Core (Wave 2; run after the Wave-1 Kotlin set K01, K02, K03, K05). The pure-vs-mutable fork at the end is the part to spend time on; the math itself is 45 minutes.

## Prerequisites

- `K03_workflow_state_machine.md` — inventory semantics must agree with the state machine (`AWAITING_APPROVAL` holds a reservation; `REJECTED` releases it).
- The K01 test harness (`kotlin("test")`, single Gradle module).
- Showcase position: before `exercise_01_foundation.md` Milestone 4; this kata is the submission-time reservation decision, without the database.

## Blog & curriculum links

- Primary: `posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md` — "Start With Domain Transition Tests" and "Test Outcomes, Not Implementation Details".
- Secondary: `posts/series-1-kotlin/01-kotlin-for-java-developers.md` — `val` as the default; immutable domain values at boundaries.
- Coach-assessment gap: Day 3 ("immutable data, collection transformations, value objects, invariants") and the challenge's inventory-verification requirement. The Postgres race version is `posts/series-2-postgres/03-inventory-reservation.md` (P04) — out of scope here by design.

## Background & motivation

Inventory is where pharmacy math meets product truth. A patient's prescription has multiple lines — Amoxicillin 21, Ibuprofen 30, Lisinopril 30 — and stock must be reserved *atomically*: either every line is satisfied or none is. Partial reservation is a support ticket. The pure-function framing is the training-wheels version: the function takes a snapshot and returns a new snapshot, so tests are deterministic, there is no shared mutable `stock` map to synchronize, and the *behavior* is separated from the *database*.

This kata deliberately ignores: concurrency (the race for the last unit — P04), PostgreSQL constraints and `FOR UPDATE` (P-series), transactions, and even K03's state integration beyond the semantic contract. The point is the *math* and the *signature*. If the pure math is wrong, no locking scheme will fix it.

## Learning objectives

- Model inventory as an immutable snapshot (`Map<medicationId, qty>`) and return new snapshots instead of mutating.
- Implement all-or-nothing multi-line reservation with a sealed outcome that names the shortage.
- Implement the inverse `release` operation and prove round-trip invariants.
- Write table-driven tests (parameterized-style) covering the boundary cases: exact fit, over-reserve, zero stock, unknown medication, duplicate lines.
- Explain in one sentence why pure functions do not solve concurrent reservations.

## Warm-up

Read `posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md` sections "Start With Domain Transition Tests" and "Test Outcomes, Not Implementation Details". Before coding, write the reserve contract as a single sentence on paper: "Given stock X and a request R, reserve returns either the reduced snapshot or a shortage listing {medication → missing}, never a partial reduction."

## System specification

**Scope in:** an immutable inventory snapshot type, `reserve` and `release` pure functions, a shortage outcome, and the full boundary test set.

**Scope out:** persistence, locking, transactions, Kafka/Rabbit (R-series), reservation rows, time-dependent stock (expiry), the state-machine integration beyond the contract.

**Functional requirements (minimal):**

- Seed snapshot for the five catalog meds: Amoxicillin, Ibuprofen, Lisinopril, Metformin, Atorvastatin with small deterministic quantities (e.g. 5–30).
- `reserve(snapshot, lines)` returns `ReservationResult.Reserved(newSnapshot)` or `ReservationResult.InsufficientStock(shortages)`; on shortage, the snapshot is unchanged.
- Multi-line prescriptions reserve atomically — no line is reduced unless all lines fit.
- `release(snapshot, lines)` adds quantities back and is the declared inverse of reserve (validated by a round-trip test).
- Duplicate medication lines in one prescription are handled by an explicit, documented rule (merge or reject — your choice, tested).

**Constraints:** single module, in-memory, no `var` in `src/main`, no Spring. Pure functions only: same input, same output, no side channels.

## Step-by-step code-along

**Step 1 — The snapshot type**

**Do:** define inventory as a plain `Map<String, Int>` of medicationId to available quantity, plus a `MedicationCatalog` data class holding the five seeded meds with names (Amoxicillin, Ibuprofen, Lisinopril, Metformin, Atorvastatin) and their starting quantities. Provide a `seedInventory(): Map<String, Int>` function that returns the deterministic snapshot.

**Run:** `./gradlew test` with a test asserting the seed contains all five meds with positive quantities.

**Observe:** a `Map<String, Int>` is the honest minimum. You do not need a class; you need a snapshot you can pass around and return. K01's lesson applies: read-only interface at the boundary — build new maps, never mutate the input.

**Kotlin idiom for Java veterans:** Java would reach for a mutable `ConcurrentHashMap` or a service with `synchronized` reserve. Here the function signature *is* the concurrency story: nothing can be corrupted because nothing is mutated. `map + otherMap` (or `map.toMutableMap()` inside a function that returns a fresh map) is the idiomatic "new state" move.

**Step 2 — Reserve, one line at a time**

**Do:** write the core pure function:

```kotlin
fun reserve(snapshot: Map<String, Int>, lines: List<PrescriptionItem>): ReservationResult =
    reserveAll(snapshot, lines, 0)
```

Keep an internal `reserveAll` that walks lines recursively or with an explicit loop, carrying the *working* snapshot, and on the first shortage returns `InsufficientStock(shortages)` — discarding the working copy so the input snapshot is untouched.

```kotlin
sealed interface ReservationResult {
    data class Reserved(val inventory: Map<String, Int>) : ReservationResult
    data class InsufficientStock(val shortages: Map<String, Int>) : ReservationResult
}
```

**Run:** tests for the single-line cases first: exact fit reserves; over-reserve returns a shortage naming the missing quantity; unknown medication id counts as a shortage.

**Observe:** the working-copy discard is the all-or-nothing property. On failure, the caller keeps the previous snapshot — the reservation "never happened".

**Decision (if any):** `ReservationResult.Reserved(inventory)` carries the new snapshot; `InsufficientStock(shortages)` carries `medicationId → missing qty`. Would an empty `shortages` map on the success path be simpler? Decide, then keep the type honest either way.

**Step 3 — Multi-line atomicity**

**Do:** a prescription with three lines: Amoxicillin 10, Ibuprofen 5, Metformin 20 against a seed where Metformin has only 15. Run reserve.

**Run:** assert the result is `InsufficientStock` with `shortages["metformin"] == 5` **and** that the snapshot is unchanged for *all* meds — including the two that fit.

**Observe:** this is the test an interviewer will ask about ("what happens to the lines that were available?"). The answer: nothing — atomicity means the patient's order is all-or-nothing, because a partial reservation would either ghost-reserve stock or over-promise.

**Decision (if any):** duplicate lines — merge quantities before reserving (e.g. two Amoxicillin 10 lines become one 20) vs reject duplicate medications. Merging is friendlier to a patient's handwritten form; rejecting is simpler. Pick one, test it, and name the patient-facing consequence.

**Step 4 — Release as the inverse**

**Do:** implement `release(snapshot, lines)` that adds quantities back, and a round-trip test:

```kotlin
@Test
fun `release restores what reserve took`() {
    val before = seedInventory()
    val requested = listOf(PrescriptionItem("amoxicillin", 10))
    val (reserved) = reserve(before, requested) as ReservationResult.Reserved
    val after = release(reserved.inventory, requested)
    assertEquals(before, after)
}
```

**Run:** the round-trip plus a release-with-unknown-med case (document the chosen behavior: add a new entry vs ignore).

**Observe:** `assertEquals(before, after)` on maps works because Kotlin map equality is structural. The round-trip is the invariant that links K03's `REJECTED` path to stock restoration — the state machine calls the release contract when a pharmacist rejects.

**Decision (if any):** on release of a med with zero stock left (e.g. release 10 when 0 available), do you allow a positive quantity (it was previously reserved) or reject? This is the "can stock go negative transiently?" question — answer it in a comment; the database check constraint in Exercise 1 will encode the final rule.

**Step 5 — Make the suite table-driven**

**Do:** refactor the boundary cases into one `@ParameterizedTest` with a `@MethodSource` (or a plain `listOf` of case objects, mirroring K03's matrix style) covering: exact fit, one-over, way-over, zero stock for a med, unknown med, empty line list, duplicate lines.

**Run:** `./gradlew test` green.

**Observe:** the parameterized shape is exactly what `posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md` recommends — one obvious difference per case. K05 will build on this pattern properly.

## Try this

Deliberate experiment — **the mutation bug**:

1. Implement reserve by first doing `val working = snapshot.toMutableMap()` and mutating `working[medId] = working[medId]!! - qty` *without* the atomicity guard (return success as soon as the first line fits).
2. Run the Step 3 test: it fails — the map lost the not-yet-processed lines' guarantee, or worse, an early return leaks a partially-mutated map.
3. Now fix it the pure way and observe the difference: no mutation, no partial state, nothing to leak. 
4. In one sentence, record what the failed test taught you about *why* purity and atomicity are the same discipline here.

## Trade-off fork

**Option A — pure functions over immutable snapshots (this kata's default).**
Pros: trivially testable, thread-safe by construction, replayable for the future status-history/outbox mental model, and the same snapshot can be handed to K05 builders. Cons: allocations per reserve; copying a large catalog is not free; the "current stock" lives outside the function, so a caller must carry it around (which is honest).

**Option B — a mutable `Inventory` class with internal state and `reserve`/`release` mutating methods.**
Pros: familiar Java service shape, one object is "the stock", no snapshot bookkeeping in the caller. Cons: shared mutable state needs synchronization or becomes a race in Exercise 1; tests need setup/teardown discipline; the mutation bug from Try this becomes a *runtime* bug, not a compile-time one.

Choose one and write 3–5 lines justifying it for a 2–5 hour submission judged on failure handling and simplicity — and name the lost benefit. Note which option `exercise_01_foundation.md`'s read-check-write inventory path implicitly resembles, and why the *database* version still needs P03/P04 regardless of your choice.

## Hints

**Hint 1 (mild):** Kotlin has no mutable-map `compute` ceremony requirement — `working[med] = working.getValue(med) - qty` inside a function that returns a *new* map is idiomatic. And `getValue` beats `!!` for the "med must exist in catalog" case: it throws a named `NoSuchElementException` if your catalog is inconsistent.

**Hint 2 (stronger):** for the round-trip test, watch for the duplicate-lines rule: if you chose merge, `release` must apply the same normalization or the round-trip breaks. Put the normalization in one shared helper used by both reserve and release — a duplicated rule between the two is exactly the bug a reviewer will find.

## Checkpoint / success criteria

You may leave when:

- `reserve` is atomic across multi-line prescriptions (the Step 3 test proves snapshot unchanged on shortage).
- `release` round-trips against `reserve` for every med in the seed.
- Boundary suite covers: exact fit, over-reserve with named shortage, zero stock, unknown med, empty lines, duplicates (per your rule).
- No `var` in `src/main`; no mutation of any input map.
- You can state in one sentence why purity does not equal concurrency safety (P04 exists for a reason).

## Bottleneck & reflection questions

- A patient's prescription has 3 lines, one of which is short. What does the *patient* experience at the API in `exercise_01_foundation.md` Milestone 2 — and why must the other two lines not be reduced?
- Where does `release` plug into the state machine from K03? (Name the transition.)
- The pure snapshot model says nothing about two pharmacists racing for the last Lisinopril unit. What does `posts/series-2-postgres/03-inventory-reservation.md` (P04) add, and why is the pure function still worth having?
- "Zero stock" here is a map value of 0. Where does the database version draw the line between "clean insufficient-stock outcome" and "check-constraint failure"? (`exercise_01_foundation.md` Milestone 3 says the constraint is a safety net, not the algorithm.)
- Your reserve is deterministic and replayable. What later feature (status history, outbox relay, event sourcing) benefits from that property — and what does it not give you?

## Handoff

- Next elective: `K05_test_data_builders.md` — builders that make these reserve/release tests read like prose.
- Related showcase exercise: `showcase_projects/pharmacy-fulfillment/exercise_01_foundation.md` — Milestones 3–4 (inventory seed, submission-time reservation, rejection restore).
- Interview line you should be able to say aloud: "Inventory reserve is a pure, all-or-nothing function: it returns a new snapshot on success or a named shortage on failure, never a partial reservation; release is its inverse and I prove the round-trip in tests. Purity makes the math deterministic and testable — and it is explicitly not a concurrency claim, because the race for the last unit is settled by the database, not by the function."

## Optional stretch

Add a `MedicationCatalog`-aware overload that validates medication IDs against the catalog before reserving, returning a distinct `UnknownMedication(ids)` outcome instead of lumping unknowns into shortages. Update the boundary suite. Then write two paragraphs: when would unknown-medication handling belong *before* inventory (at submission validation) and what changes for the patient if it is discovered late?
