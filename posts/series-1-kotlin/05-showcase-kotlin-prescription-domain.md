# From Java Service to Idiomatic Kotlin Prescription Domain

The pharmacy challenge is not mainly a syntax exercise. It is a compact workflow system with a user-visible state machine, database concurrency, asynchronous messages, and failure boundaries. Kotlin helps make those boundaries explicit when the design uses the language deliberately.

## Begin With A Value Model

A prescription contains identity, a patient, medication lines, and a current state:

```kotlin
data class Prescription(
    val id: String,
    val patientId: String,
    val items: List<PrescriptionItem>,
    val status: PrescriptionStatus
)

data class PrescriptionItem(
    val medicationId: String,
    val quantity: Int
)
```

This is a useful boundary model because the values are visible and easy to construct in tests. `val` communicates that callers should not mutate the object in place. It does not make the database transaction safe, and it does not make the list deeply immutable. Those guarantees belong to other layers.

The model should validate basic local invariants such as a positive quantity and a non-empty medication ID. Cross-request invariants, such as inventory never becoming negative, require PostgreSQL.

## Represent The Workflow Explicitly

An enum is a reasonable persisted representation:

```kotlin
enum class PrescriptionStatus {
    SUBMITTED,
    AWAITING_APPROVAL,
    APPROVED,
    PACKAGING,
    READY,
    FULFILLED,
    REJECTED
}
```

The application should expose named operations rather than accept arbitrary status updates. `approve`, `reject`, `startPackaging`, and `fulfill` are easier to authorize, test, and explain than a generic `setStatus` method.

Expected business outcomes should be explicit:

```kotlin
sealed interface ApprovalOutcome {
    data class Approved(val id: String) : ApprovalOutcome
    data object NotFound : ApprovalOutcome
    data object InvalidState : ApprovalOutcome
}
```

The application can map these outcomes to API responses while keeping the domain independent of HTTP. A database outage remains an infrastructure failure, not an `InvalidState` result.

## Keep The Transition Safe At The Database Boundary

The Kotlin operation can verify that the loaded order is awaiting approval. That is useful for a clear error message, but it is not enough under concurrency. The repository must perform a conditional update whose predicate includes the expected state. The affected-row count identifies the winner.

If approval also writes status history and an outbox event, those writes belong in the same PostgreSQL transaction. The transaction then establishes a durable fact:

```text
the order is APPROVED and the APPROVED event is ready for publication
```

The RabbitMQ relay handles publication later. Kotlin's immutable object represents the intended transition; PostgreSQL commits the shared truth.

## Test The Rules At The Right Level

The transition function can be tested without Spring or infrastructure. These tests should cover legal and illegal transitions and should use small builders so the relevant difference is obvious.

The service and repository integration tests need real PostgreSQL for constraints, migrations, affected-row behavior, and concurrency. The message workflow needs a real RabbitMQ broker to prove exchange bindings, acknowledgements, redelivery, and dead-letter behavior.

This division makes the test suite both fast and credible:

- Kotlin unit tests protect local domain rules.
- PostgreSQL integration tests protect shared state invariants.
- RabbitMQ integration tests protect delivery semantics.
- One end-to-end test protects the patient journey.

## Do Not Let The UI Define The Architecture

The patient first needs a reliable way to ask for the current status. A simple `GET /prescriptions/{id}` endpoint should be the correctness baseline. It can return the current status and a patient-safe message.

SSE can then provide a better waiting-room experience. It should not be implemented by attaching every patient connection directly to the worker queue. A competing RabbitMQ consumer could consume another patient's work message. Instead, the application can fan out status events separately, with event IDs, authorization, and a replay strategy.

The progression is important:

1. Make the current status correct.
2. Make workflow messages reliable.
3. Add status history or an event projection suitable for replay.
4. Add SSE with `Last-Event-ID` and isolation tests.

That sequence gives the challenge a defensible two-hour baseline and a meaningful five-hour extension.

## What A Reviewer Should Hear

An effective walkthrough sounds like this:

1. “The patient is the primary user, so the first slice creates a prescription and exposes status.”
2. “The domain uses explicit commands and outcomes so invalid workflow operations are visible.”
3. “PostgreSQL conditionally applies transitions and commits business state with an outbox event.”
4. “RabbitMQ is at-least-once, so consumers use stable event IDs and an inbox constraint.”
5. “The baseline status endpoint is authoritative; SSE is a fan-out enhancement with replay and ordering tests.”
6. “The implementation intentionally omits a full pharmacist UI and broad service decomposition to preserve a reliable time-boxed submission.”

This explanation connects language choices to product value and failure handling instead of listing technologies.

## Kotlin Lessons Worth Carrying Forward

- Use `val` and immutable values as defaults, but do not confuse them with transaction safety.
- Use nullable types for absence and sealed outcomes for expected business alternatives.
- Use exhaustive `when` to make closed domain decisions visible.
- Keep framework and Java boundaries narrow so platform types and blocking behavior are easy to inspect.
- Prefer clear named operations over clever scope-function chains in transaction-heavy workflows.
- Let the database and broker enforce guarantees that local types cannot provide.

## Final Checklist

Before moving beyond the Kotlin foundation, the engineer should be able to:

- read and write basic Kotlin without translating every line from Java;
- explain `val`, data classes, read-only collections, nullable types, and sealed outcomes;
- model legal prescription transitions and explain why the database still needs a conditional update;
- write focused domain tests and identify where real infrastructure is required;
- explain why patient SSE connections are not RabbitMQ work consumers;
- describe the two-hour baseline and five-hour extension.

## Interview Takeaway

An idiomatic Kotlin pharmacy domain is not merely shorter Java: it makes business alternatives and state transitions visible, while PostgreSQL, RabbitMQ, and tests provide the guarantees that language types cannot.
