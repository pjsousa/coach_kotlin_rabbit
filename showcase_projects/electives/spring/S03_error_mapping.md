# S03 Error Mapping — Code-Along Elective

## Objective

Replace the ad-hoc nullables and framework defaults from S01/S02 with a sealed set of domain outcomes mapped deliberately to HTTP statuses — `400` validation, `404` missing, `409` conflict, `500` unexpected — so the API has an explicit error contract with a consistent JSON body. The primary objective is to feel why Kotlin's sealed types turn "which errors can happen" from documentation into a compiler-checked fact.

## Time box

~1.5–2 hours, core track. Suggested split: sealed outcomes 30 min, service refactor 30 min, controller mapping + approve endpoint 30 min, error body + validation mapping 20 min, mapping unit test 15 min, fork notes 5 min.

## Prerequisites

- **Hard prerequisite: `../kotlin/K02_nullable_patient_lookup.md`** — sealed `Result`/outcome hierarchies and the "null vs sealed vs exception" split are taught there; this kata applies them at the HTTP boundary.
- `S02_layered_slice.md` — you are changing the service's return types, which the layering makes local.
- Showcase position: **before Exercise 1**. Its error contract table is the exact deliverable you are now building at small scale.

## Blog & curriculum links

- Primary: `posts/series-1-kotlin/02-nullability-results-domain-errors.md` — "Sealed Results Represent a Closed Set of Outcomes", "Exceptions Have a Place", "Make Invalid States Harder To Reach".
- Secondary: `posts/series-4-product-sse/01-patient-first-api.md` — "Error Contracts: Four Answers" (400/404/409/500 is the contract table you implement here).
- Coach-assessment gap attacked: Kotlin beginner friction + the interview answer "which failures are which" (the current system-design answer was thin on explicit staff-command errors).

## Background & motivation

The blog post lists four distinguishable outcomes — missing prescription, illegal transition, validation failure, infrastructure failure — and says treating them all as `null` or all as exceptions makes the API harder to explain. That is exactly the state S01/S02 left you in: Spring's defaults answer with whatever body the framework happened to produce. This kata forces the decision in one place, at small scale, so Ex1's larger state machine (K03) inherits a proven pattern instead of being the place you learn it. It deliberately ignores idempotency, retries, database errors, and stack-trace scrubbing of 500s — Ex1 still documents those.

## Learning objectives

1. Model a closed set of command outcomes with a `sealed interface` + data objects, with no HTTP types in the domain.
2. Split concerns the Kotlin way: `null` for pure absence, sealed outcomes for expected command alternatives, exceptions only for what the operation cannot safely complete.
3. Map outcomes to status codes in one place using an exhaustive `when` — and watch the compiler force you to handle every case.
4. Produce a consistent error body (`status`, `code`, `message`, optional `fieldErrors`) including validation-field errors.
5. Add one illegal-transition case (double approve) and return `409` with a deliberate body.
6. Unit-test the mapping as a pure function without Spring.

## Warm-up

Re-read the K02 sections "Sealed Results Represent a Closed Set of Outcomes" and "Exceptions Have a Place" (4 min), plus the "Error Contracts: Four Answers" section of series-4/01. Probe: write down, for the current S02 code, which of the four answers is currently answered by *what* — you will find 404 is Spring's default, 400 is a framework body, and 409 doesn't exist yet. That inventory is your work list.

## System specification

- **Scope in:** sealed outcome types for `submit`, `byId`, and a new `approve`; controller mapping via exhaustive `when`; consistent `ApiError` JSON body; validation errors mapped into `fieldErrors`; a pure mapping function with a unit test.
- **Scope out:** Postgres, RabbitMQ, SSE, auth, retries, idempotency, log-scrubbing of 500s, per-actor error contracts (staff errors stay the same shape for now).
- **Functional requirements (minimal):**
  - `POST /prescriptions` → `201` (view), `400` (field errors), `409` (duplicate `clientSubmissionKey` if you implement the conflict now).
  - `GET /prescriptions/{id}` → `200`, `404`.
  - `POST /prescriptions/{id}/approve` → `200` (view), `404`, `409` when not in `SUBMITTED`.
  - Every error response is JSON with `status`, `code`, `message`; validation adds `fieldErrors`.
- **Constraints:** no HTTP imports in the domain package; one mapping location per command; single module; in-memory storage.

## Step-by-step code-along

1. **Define the outcomes**
   - **Do:** in the domain package, `sealed interface SubmitOutcome { data class Created(val prescription: Prescription): SubmitOutcome; data object Conflict: SubmitOutcome }` and `sealed interface ApproveOutcome { data class Approved(val prescription: Prescription): ApproveOutcome; data object NotFound: ApproveOutcome; data object InvalidState: ApproveOutcome }`. Use `data object` for singletons.
   - **Run:** `./gradlew compileKotlin`.
   - **Observe:** the compiler now knows the closed set of outcomes. Kotlin idiom call-out for Java veterans: this is the enum-with-behavior of your C#/Kotlin past, but open to new cases *at compile time* — when you add a subtype later, every exhaustive `when` breaks until you handle it. That is the safety you are paying for.

2. **Refactor the service**
   - **Do:** change `submit` to return `SubmitOutcome` and add `approve(id)` returning `ApproveOutcome`. Keep `byId` returning `Prescription?` — plain absence stays nullable per K02; do not create an outcome for a simple lookup.
   - **Run:** compile. Expect the controller to break — that is the compiler telling you the mapping is now unhandled.
   - **Observe:** the rule in action: nullable = absence; sealed = expected alternatives *with payloads or semantics*; exception = "I cannot safely complete this".

3. **Map outcomes in the controller**
   - **Do:** in the controller, `when (val outcome = service.submit(request)) { is Created -> ResponseEntity(outcome.prescription.toView(), CREATED); is Conflict -> ResponseEntity(ApiError(409, "DUPLICATE_SUBMISSION", "..."), CONFLICT) }`. Same for `approve` — include the `InvalidState` branch returning `409`.
   - **Run:** `bootRun`; curl the happy path, a double approve, a missing id.
   - **Observe:** three statuses now come from *your* decision, not the framework's. If you compile without covering a branch, the compiler refuses — try it once by omitting `Conflict` and read the error. This is the Kotlin moment interviewers probe.

4. **Consistent error body**
   - **Do:** `data class ApiError(val status: Int, val code: String, val message: String, val fieldErrors: List<FieldError> = emptyList())`. Map Spring's `MethodArgumentNotValidException`/`ConstraintViolationException` into `fieldErrors` with `field` and `message` entries. Use an `@ExceptionHandler` *only* for the validation exceptions and the truly-unexpected case.
   - **Run:** re-send the bad payloads from S01's Try-this.
   - **Observe:** the body is now uniform: `{"status":400,"code":"VALIDATION_FAILED","message":...,"fieldErrors":[{"field":"items","message":...}]}`. A client can code against one shape.

5. **Keep 500 honest**
   - **Do:** nothing special for 500 — let the unexpected exception bubble to Spring's handler — but write one comment/line in the controller stating the boundary: "expected outcomes are sealed; anything else is a bug or an outage, and returns 500 by default."
   - **Run:** trigger a deliberate failure (e.g. temporarily make the repository throw) and curl it.
   - **Observe:** you get a 500 with a generic body. Note: it may leak a stack trace — that is a known gap to document in S06, not to fix here.

6. **Unit-test the mapping**
   - **Do:** a plain JUnit test class (no Spring) that feeds each outcome to your mapping function and asserts the status: `Created → 201`, `Conflict → 409`, `NotFound → 404`, `InvalidState → 409`.
   - **Run:** `./gradlew test`.
   - **Observe:** the mapping is a pure function — fast, deterministic, no context. This is the "test outcomes, not implementation details" claim from series-1/04 made concrete.

## Try this

Remove one branch from the controller's `when` (say `InvalidState`) and compile — the compiler fails and *names the missing case*. Restore it. Then send `POST /prescriptions/{id}/approve` twice on the same id and read both responses: the second is a deliberate `409` with your `code`, not a framework accident. Finally, send an id that is not a UUID — observe whether Spring rejects it before your mapping (400 from conversion) and decide whether that is the right answer for a patient-facing API.

## Trade-off fork

**Option A — sealed outcomes mapped in the controller** (Kotlin-style): the domain never throws for business cases; the mapping is a pure function; exhaustiveness is compiler-enforced.

**Option B — custom exceptions + `@RestControllerAdvice`** (Spring-style): familiar to any Java team; `@ExceptionHandler` per type; exceptions carry status codes; the controller stays thin.

Pick one and write 3–5 lines justifying it. Name the lost benefits: B makes "which errors exist" live in exception classes scattered by convention, and the mapping is only discoverable by reading the advice; A couples the controller to outcome types and grows a `when` per command. Nudge: A — for Ex1, "illegal transition" is a first-class domain outcome you will want the compiler to track.

## Hints

- **Hint 1:** `data object` requires Kotlin 1.9+; if your toolchain complains, use `object` + override `equals`/`hashCode` via `data class` with a marker — or just upgrade the Kotlin plugin. Sealed types in one file keep the compiler fast and the hierarchy obvious.
- **Hint 2:** For validation mapping: `MethodArgumentNotValidException.bindingResult.fieldErrors` gives you field + default message; map it in one `map { FieldError(it.field, it.defaultMessage) }` line. If your 400 still shows Spring's default body, your `@ExceptionHandler` is in the wrong class or not picked up — put it in a `@RestControllerAdvice` *or* in the controller, not both.

## Checkpoint / success criteria

- Four answers exist and are deliberate: `400` (with `fieldErrors`), `404`, `409` (with a business `code`), `500` (default, honest).
- No HTTP types in the domain package; every `when` over outcomes is exhaustive.
- The mapping function is unit-tested with no Spring context.
- You can say which of the four answers is a *contract* and which is still a framework default.

## Bottleneck & reflection questions

1. **Failure handling:** which of the four answers would a client be wrong to retry, and how does your `code` field tell them?
2. **Patient experience:** a patient double-submits and gets `409 DUPLICATE_SUBMISSION`. Is that a truthful message for a person (vs a retry loop for a client)?
3. **System design:** the mapping lives in the controller today. Would it survive Ex1's RabbitMQ consumers, which have no HTTP to map to?
4. **Simplicity:** did the sealed-outcome version cost more lines than the exception version? If so, what did the extra lines buy, and is that a good trade at interview?
5. What happens when Ex1 adds a Postgres failure — is it one of your four answers, and which? (P01 will make this real.)

## Handoff

- **Next:** `S04_api_tests.md` — you now have a contract worth asserting; the tests pin the four answers. Then `S05_config_and_profiles.md` and capstone `S06_timebox_readme.md`.
- **Related showcase:** `../../pharmacy-fulfillment/exercise_01_foundation.md` — its error contract ("invalid input → validation response, missing → not found, illegal transition → conflict, outage → technical failure") is this kata at Ex1 scale; the `code` vocabulary you chose carries over.
- **Interview line to say aloud:** "Expected business outcomes are modeled as sealed domain types and mapped to 400/404/409 in one exhaustive place; nullable stays reserved for plain absence; exceptions mean the operation cannot safely complete — that split is compiler-checked, and it is the same contract Ex1 asserts."

## Optional stretch

Add the `409` duplicate-submission branch for `clientSubmissionKey` on `submit` (store keys in a second map) and extend the mapping unit test to cover it — then write two lines on the race this has in-memory (two simultaneous first-seen keys) and where Ex1 would close it (unique constraint in P01).
