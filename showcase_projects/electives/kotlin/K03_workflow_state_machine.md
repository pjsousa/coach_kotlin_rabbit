# K03 Workflow State Machine — Code-Along Elective

## Objective

Represent the prescription lifecycle as an explicit state machine — enum baseline, sealed states with per-state data, named transition operations returning sealed outcomes, exhaustive `when` mapping to patient messages — and prove legal/illegal transitions with tests that need no Spring.

## Time box

~1.5–2 hours. Core (Wave 1). The sealed-vs-enum trade-off fork is worth a full 15 minutes; do not let the tests eat it.

## Prerequisites

- `K01_prescription_value_objects.md` (the `Prescription` value you extend).
- `K02_nullable_patient_lookup.md` (sealed outcome discipline — you will copy the shape).
- Showcase position: before `exercise_01_foundation.md` Milestone 1. This kata *is* Milestone 1's state vocabulary, minus persistence.

## Blog & curriculum links

- Primary: `posts/series-1-kotlin/03-state-machines-with-sealed-types.md` — follow its structure: enum first, transitions behind behavior, sealed types for state-with-data, commands vs state.
- Secondary: `posts/series-1-kotlin/02-nullability-results-domain-errors.md` — why illegal transitions are outcomes, not exceptions.
- Coach-assessment gap: Day 3 ("enums versus sealed hierarchies, invariants") and the Day 1 prompt "how does the prescription model prevent invalid medication quantities and illegal state transitions".

## Background & motivation

The pharmacy workflow *is* a state machine whether the code admits it or not: SUBMITTED → AWAITING_APPROVAL → APPROVED → PACKAGING → READY_FOR_COLLECTION → FULFILLED, with REJECTED off to the side. Interviewers on a Product Engineer loop probe exactly this: can you approve a rejected prescription? fulfill something never packaged? The blog's strongest point: a Kotlin type can make illegal *local* states hard to create, but concurrent requests and other writers are a database problem — the local model and the conditional UPDATE must agree.

This kata deliberately ignores: persistence of the state, the conditional `UPDATE` (P03), API endpoints (S-series), RabbitMQ commands/events (R-series), and history tables (P05). In-memory transition rules only. The goal is a transition table you can read aloud and tests that encode it.

## Learning objectives

- Model a closed set of states as an enum and later as a sealed hierarchy with per-state payloads.
- Encode legal transitions as named operations returning sealed outcomes — never a public status setter.
- Write exhaustive `when` expressions that the compiler forces you to keep complete.
- Prove legal and illegal transitions with focused unit tests; express the transition graph as a table.
- State precisely which guarantees the local model provides and which it does not (concurrency).

## Warm-up

Read `posts/series-1-kotlin/03-state-machines-with-sealed-types.md` sections "An Enum Is A Useful Start" and "Put Transitions Behind Behavior". Then, on paper, draw the full transition table for the seven states and every command (submit, approve, reject, startPackaging, completePackaging, fulfill). Mark which commands are legal from which states, and which are terminal.

## System specification

**Scope in:** the prescription status enum, a sealed `FulfillmentState` alternative, transition operations with sealed outcomes, a patient-facing message mapping, and the full transition-test matrix.

**Scope out:** persistence, concurrent-write safety, HTTP, events/commands on a broker, status history, SSE.

**Functional requirements (minimal):**

- States: `SUBMITTED`, `AWAITING_APPROVAL`, `APPROVED`, `PACKAGING`, `READY_FOR_COLLECTION`, `FULFILLED`, `REJECTED` (this vocabulary is mandated by `exercise_01_foundation.md` — do not rename).
- Transition operations: `approve`, `reject`, `startPackaging`, `completePackaging`, `fulfill` — each returns a sealed outcome; a `Prescription` in an illegal state returns the invalid-state outcome and the state is unchanged.
- Rejection carries a reason; `PACKAGING` may carry a worker id; `READY_FOR_COLLECTION` may carry a shelf/queue hint.
- A `patientMessage(state)` exhaustive `when` maps every state to a patient-safe sentence.

**Constraints:** single module, in-memory, immutable `Prescription` values, no `var`, no Spring. State is a property of the value; transitions produce new values via `copy`.

## Step-by-step code-along

**Step 1 — The enum baseline**

**Do:** add `enum class PrescriptionStatus { SUBMITTED, AWAITING_APPROVAL, APPROVED, PACKAGING, READY_FOR_COLLECTION, FULFILLED, REJECTED }` and a `status: PrescriptionStatus` property on `Prescription` (default `SUBMITTED`).

**Run:** `./gradlew test` — the K01 tests must still pass (the new field has a default, so `copy` and equality stay stable).

**Observe:** this compiles and stores a closed vocabulary, but it accepts anything: `prescription.copy(status = PrescriptionStatus.FULFILLED)` from any state. The enum is a vocabulary, not a rule.

**Kotlin idiom for Java veterans:** in Java you'd write a `State` enum plus a switch in the service. Kotlin makes the *expression* form natural — `val s = when (status) { ... }` — so the rule can live as data flow instead of imperative branches.

**Step 2 — Transitions behind behavior**

**Do:** write the first transition as an operation on `Prescription`, validating the current state:

```kotlin
fun Prescription.approve(): TransitionOutcome = when (status) {
    PrescriptionStatus.AWAITING_APPROVAL ->
        TransitionOutcome.Approved(copy(status = PrescriptionStatus.APPROVED))
    else -> TransitionOutcome.InvalidState(status)
}
```

Define the shared sealed outcome once (`TransitionOutcome` with `Approved(next: Prescription)`, `Rejected(next: Prescription, reason: String)`, `InvalidState(current: PrescriptionStatus)`, plus `NotFound`-free — lookup is K02's job). Implement `reject(reason)` the same way, with `require(reason.isNotBlank())`.

**Run:** tests for: approve from `AWAITING_APPROVAL` succeeds; approve from `REJECTED` returns `InvalidState`; reject requires a reason.

**Observe:** the `else` branch keeps the `when` total — every state not `AWAITING_APPROVAL` is illegal, and the returned outcome names the *current* state so the caller can explain the conflict. No setter exists; the only way to move states is through these operations.

**Decision (if any):** carry the whole next `Prescription` in the outcome (`Approved(next)`) vs only the new status. Carrying the next prescription makes the outcome self-sufficient for the caller and for K04's inventory handoff — lean that way.

**Step 3 — The full transition matrix**

**Do:** implement `startPackaging` (legal only from `APPROVED`), `completePackaging` (only from `PACKAGING`), and `fulfill` (only from `READY_FOR_COLLECTION`). Then write the matrix as a single test file where every (command, current-state) pair is a case. A table-driven shape:

```kotlin
@Test
fun `full transition matrix`() {
    val cases = listOf(
        Triple(Command.APPROVE, PrescriptionStatus.SUBMITTED, false),
        Triple(Command.APPROVE, PrescriptionStatus.AWAITING_APPROVAL, true),
        Triple(Command.FULFILL, PrescriptionStatus.PACKAGING, false),
        // ...
    )
    cases.forEach { (command, from, legal) ->
        // build prescription with `from`, run command, assert outcome legality
    }
}
```

**Run:** `./gradlew test` — every cell of your paper table must appear.

**Observe:** this test file *is* the transition table in executable form. When an interviewer asks "which transitions are legal?", you run one test instead of hunting through services.

**Decision (if any):** reject is legal only from `AWAITING_APPROVAL` (per `exercise_01_foundation.md`) — or also from `SUBMITTED`? The exercise allows `SUBMITTED` to be brief/transient; pick one and note the patient-facing consequence.

**Step 4 — Sealed states with payloads**

**Do:** model a parallel sealed hierarchy where states that carry data say so in the type:

```kotlin
sealed interface FulfillmentState {
    data object Submitted : FulfillmentState
    data object AwaitingApproval : FulfillmentState
    data object Approved : FulfillmentState
    data class Packaging(val workerId: String) : FulfillmentState
    data class Ready(val queueHint: String) : FulfillmentState
    data object Fulfilled : FulfillmentState
    data class Rejected(val reason: String) : FulfillmentState
}
```

Add a `state: FulfillmentState` variant of the model (separate from the enum — keep both for comparison) and port the transitions. Note what changed: `reject` now *must* supply a reason (the type demands it), and `completePackaging` can only run where a `workerId` exists.

**Run:** port the matrix tests to the sealed model; add one case that reads the payload (e.g. rejection reason) from the outcome.

**Observe:** the type now prevents *constructing* a rejection without a reason and *packaging* without a worker. That is the blog's "states with different data" payoff — and it is also where the persistence mapping cost begins.

**Step 5 — Patient-facing messages**

**Do:** an exhaustive `when` (or extension function) mapping every status/state to a sentence a patient understands, e.g. `AWAITING_APPROVAL -> "Waiting for pharmacist approval"`, `READY_FOR_COLLECTION -> "Please collect your prescription"`.

**Run:** a test asserting each state maps to a non-blank message; then temporarily remove one branch and confirm the compiler stops you.

**Observe:** this is the *patient experience* layer of the state machine — the blog's `patientMessage` example. The exhaustive `when` is how Kotlin turns "someone forgot a state" into a compile error instead of a `null` patient message in production.

## Try this

Deliberate experiment — **the compiler as reviewer**:

1. Add a new state to the sealed hierarchy, e.g. `data object HeldForReview : FulfillmentState`.
2. Run the build.
3. Observe: every exhaustive `when` — `patientMessage`, the transitions, your matrix test if it switches — fails to compile until you handle the new state.
4. Now do the *same* thing to the enum version. The `when` with an `else` branch compiles silently; the matrix test may even pass.
5. Note the asymmetry in one sentence: sealed = compiler-enforced completeness; enum + else = reviewer-enforced. The blog's "compiler tells us a new state has not been handled" only holds for the sealed case.

## Trade-off fork

**Option A — enum + transition functions (persisted-friendly).**
Pros: one column maps to PostgreSQL (P01/P03), no mapping layer, the matrix test still proves the graph, `exercise_01_foundation.md` explicitly blesses it ("An enum persisted as a status column is enough at this level"). Cons: per-state data (reason, workerId, shelf) lives in separate fields or is lost; the type does not force completeness — `else` branches hide new states.

**Option B — sealed hierarchy with payloads.**
Pros: per-state data is type-enforced, exhaustive `when` gives compile-time completeness (you just proved it in Try this), richer domain clarity in the walkthrough. Cons: persistence needs a mapping strategy (status column + JSON payload, or several nullable columns — the blog warns against "complicated persistence mapping merely to demonstrate a language feature"); more moving parts inside a 2–5 hour submission.

Choose one and write 3–5 lines of justification, naming the lost benefit of the other. Both survive the interview; the *reasoning* is what is graded. If you choose B, sketch the mapping you would use in Exercise 1 before moving on.

## Hints

**Hint 1 (mild):** the `TransitionOutcome` shape from K02 is your template — one sealed interface, one payload-carrying success per command, one `InvalidState(current)` alternative shared across commands. Do not create a new sealed type per command.

**Hint 2 (stronger):** if your matrix test gets verbose, note that `when` over a *sealed* state has no `else` branch available to hide mistakes — that is a feature. And if `copy(status = ...)` feels like a way to cheat the rules, remember K01: `copy` bypasses nothing you put in `init` — but it *does* bypass your transition functions. The operations are the only public door.

## Checkpoint / success criteria

You may leave when:

- The full (command × state) matrix test passes and every illegal transition returns `InvalidState` with the current state.
- Rejection requires a reason; `completePackaging` is impossible before `startPackaging`.
- `patientMessage` covers every state exhaustively (compile-enforced or test-enforced, per your fork).
- You can draw the state graph on a whiteboard and point at the exact test that proves each arrow — and the exact database mechanism (conditional UPDATE) that the local model does not provide.

## Bottleneck & reflection questions

- Two pharmacist tabs approve the same prescription: which one wins in this in-memory kata? What evidence would the *real* system need (hint: `exercise_01_foundation.md` says the conditional UPDATE decides, see P03 later)?
- The patient sees "Waiting for pharmacist approval". Which component must never be able to change that message without going through the state machine? (Say "the persistence adapter" out loud.)
- Where does rejection-with-reason become a *patient-visible* outcome, and what does the reason have to do with failure handling?
- Your `TransitionOutcome` has no "technical failure" variant — is that a gap in the sealed model or correct? (K02's taxonomy says exceptions; name the boundary.)
- `exercise_01_foundation.md` Milestone 1 asks you to decide `SUBMITTED` as a real state or a history artifact — which way does your Step 3 decision point, and why does it matter for the status `GET`?

## Handoff

- Next elective: `K04_inventory_pure_functions.md` — reserve/release math that must agree with this state machine's `AWAITING_APPROVAL`/`REJECTED` semantics.
- Related showcase exercise: `showcase_projects/pharmacy-fulfillment/exercise_01_foundation.md` — Milestone 1's "state machine and domain outcomes" is this kata plus persistence.
- Interview line you should be able to say aloud: "I model prescription workflow as an explicit state machine behind named operations: transitions are the only way to change state, illegal transitions return a sealed invalid-state outcome instead of exceptions, an exhaustive `when` maps every state to what the patient should hear, and I never pretend the in-memory rules serialize concurrent requests — the conditional database update owns that."

## Optional stretch

Implement a tiny `History`-style log in-memory: every successful transition appends `(from, to, actor, at)` to an immutable list held inside the `Prescription` (or a wrapper). Extend the matrix test to assert that an illegal transition appends *nothing*. You have just prototyped the status-history table that `exercise_01_foundation.md` Milestone 3 will persist — P05 makes it real.
