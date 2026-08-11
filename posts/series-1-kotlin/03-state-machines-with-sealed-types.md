# State Machines with Sealed Kotlin Types

Prescription fulfillment is a state machine, whether the code acknowledges it or not. An order moves through a constrained sequence such as:

```text
SUBMITTED -> AWAITING_APPROVAL -> APPROVED -> PACKAGING -> READY -> FULFILLED
                              \-> REJECTED
```

The value of naming this explicitly is not academic. The pharmacy must not approve a rejected prescription, fulfill an order that was never packaged, or tell a patient that an order is ready before the packaging work succeeds.

## An Enum Is A Useful Start

An enum is often the right persisted representation:

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

It works well with a PostgreSQL column, status filters, and a simple API. It does not, by itself, restrict transitions. This method compiles even though it accepts an order in any state:

```kotlin
fun approve(order: Prescription): Prescription =
    order.copy(status = PrescriptionStatus.APPROVED)
```

The caller could approve a fulfilled order. The type is closed, but the transition rules are not.

## Put Transitions Behind Behavior

A first improvement is to make the transition an operation that validates the current state:

```kotlin
fun Prescription.approve(): ApprovalOutcome = when (status) {
    PrescriptionStatus.AWAITING_APPROVAL ->
        ApprovalOutcome.Approved(copy(status = PrescriptionStatus.APPROVED))
    else -> ApprovalOutcome.InvalidState
}
```

This provides a clear local rule. It does not solve concurrent requests. Two application instances can both read `AWAITING_APPROVAL` and both calculate `APPROVED`. The PostgreSQL update must still include the expected current state:

```text
UPDATE prescription
SET status = 'APPROVED'
WHERE id = :id AND status = 'AWAITING_APPROVAL'
```

The affected-row count decides which request won. Domain types and database conditions work together.

## Sealed Types For States With Different Data

An enum is convenient when every state has the same data shape. A sealed hierarchy becomes useful when states have different legal data:

```kotlin
sealed interface FulfillmentState {
    data object AwaitingApproval : FulfillmentState
    data class Rejected(val reason: String) : FulfillmentState
    data class Packaging(val workerId: String) : FulfillmentState
    data class Ready(val shelf: String) : FulfillmentState
    data object Fulfilled : FulfillmentState
}
```

Now a rejected state must carry a reason and a ready state can carry a shelf location. A `when` expression can be exhaustive over the known alternatives.

This model can be excellent inside the domain, but it may be more complexity than a two-to-five-hour take-home needs. Persisting a sealed hierarchy requires a mapping strategy. The database may still use a status column plus optional fields or a JSON payload. Do not introduce a complicated persistence mapping merely to demonstrate a language feature.

## Separate Commands From State

The API should express an intent, not accept arbitrary state changes from a client:

```text
POST /prescriptions/{id}/approval
POST /prescriptions/{id}/rejection
POST /prescriptions/{id}/packaging-complete
POST /prescriptions/{id}/fulfillment
```

The server decides whether each command is legal. Avoid a generic endpoint such as:

```text
PUT /prescriptions/{id} { "status": "FULFILLED" }
```

That endpoint gives the caller too much control and makes authorization, validation, auditability, and transition rules harder to reason about.

The same distinction applies to messages. A `PrescriptionApproved` event says what happened. A `ApprovePrescription` command asks a component to do something. Keeping these concepts separate helps prevent a consumer from treating a replayed event as a new command.

## State History Is Not The Same As Current State

The current status is optimized for answering “what should the patient see now?” A status-history table answers “how did it get there?” They have different purposes:

- The current row supports a fast status lookup.
- History supports debugging, support questions, and event replay.
- An outbox row supports reliable publication to RabbitMQ.
- An inbox row supports idempotent consumption.

For a short challenge, history can be minimal. The important design statement is that the patient status endpoint should have one authoritative source and that asynchronous events should not silently invent a different state.

## Illegal Transitions Are Normal Business Outcomes

Suppose two pharmacist clients approve the same prescription. One should succeed. The other should receive a conflict-like result, not an unhandled null pointer or a misleading success response.

Suppose a packaging worker receives a duplicate approval event. The consumer should use its event identity and business state to decide whether the duplicate is already handled. It should not blindly perform the side effect again.

Suppose a patient reconnects after missing a status event. The status stream needs an event ID and a replay strategy. The state machine remains the same; the delivery mechanism must catch up to the current state or replay the missing transitions.

## Keep Domain Rules Close To The Use Case

Do not scatter transition rules across controllers, message listeners, SQL repositories, and test fixtures. A useful structure is:

1. The controller or message listener validates transport input.
2. The application service invokes one named transition use case.
3. The domain checks whether the transition is legal.
4. The repository performs an atomic conditional write.
5. The transaction records status and an outbox event together.
6. The adapter maps the outcome to HTTP or acknowledgement behavior.

This is not a demand for a large architecture. It is a way to keep the important invariant visible while using a small number of components.

## Avoid False Type Safety

Kotlin can make illegal local states harder to create, but it cannot guarantee global workflow correctness by itself:

- Another service may write the database.
- Two requests may race.
- A message can be delivered more than once.
- A process can crash between external side effects.
- A client may replay a command.

Use Kotlin modeling for local clarity, PostgreSQL constraints and conditional writes for shared state, and RabbitMQ idempotency for asynchronous effects.

## Interview Questions To Rehearse

- When is an enum enough, and when is a sealed hierarchy worth the mapping cost?
- Where should the transition rule live?
- Why is a conditional database update necessary if the Kotlin domain checks the state first?
- What is the difference between a command and an event?
- Why store current status separately from status history?
- How should a duplicate message interact with the state machine?

## Interview Takeaway

Model prescription states explicitly, expose intent-based transitions, and enforce the same invariant at the database boundary because Kotlin types alone cannot coordinate concurrent processes.
