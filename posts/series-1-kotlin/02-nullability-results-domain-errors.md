# Nullability, Results, and Domain Errors in Kotlin

An experienced Java developer usually knows the difference between an absent value, a rejected operation, and an infrastructure failure. Kotlin makes those distinctions visible in function signatures, but only if we use its type system intentionally.

For the pharmacy workflow, these are different outcomes:

- A prescription ID does not exist.
- A prescription exists but cannot be approved because it is already rejected.
- Approval is valid, but PostgreSQL is unavailable.
- Approval is persisted, but publishing the next workflow event needs a retry.

Treating all four as `null` or all four as exceptions makes the API harder to use and the operational behavior harder to explain.

## Nullable Types Are Explicit Absence

Kotlin puts the question mark in the type:

```kotlin
fun findPrescription(id: String): Prescription?
```

The caller must handle the possibility of `null` before accessing the value. This is safer than a Java reference that may be null despite its declaration. It also communicates a narrow meaning: the lookup may not return a value.

```kotlin
val prescription = repository.findPrescription(id)
    ?: return LookupOutcome.NotFound
```

Use a nullable return when absence is an expected and uncomplicated outcome. A repository lookup is a common example. Do not use null to encode every kind of failure. `null` does not tell the caller whether the record was absent, the database timed out, or a permission check failed.

Avoid using `!!` as a way to silence the compiler:

```kotlin
val patientId = prescription!!.patientId
```

The operator turns a type-system warning into a runtime exception. If the value is required at that point, validate it at the boundary or make the function contract guarantee it.

## Sealed Results Represent a Closed Set of Outcomes

A sealed hierarchy is useful when the caller should handle a known set of business outcomes:

```kotlin
sealed interface ApprovalOutcome {
    data class Approved(val orderId: String) : ApprovalOutcome
    data object NotFound : ApprovalOutcome
    data object InvalidState : ApprovalOutcome
}
```

The caller can use an exhaustive `when`:

```kotlin
fun message(outcome: ApprovalOutcome): String = when (outcome) {
    is ApprovalOutcome.Approved -> "Approval recorded"
    ApprovalOutcome.NotFound -> "Prescription not found"
    ApprovalOutcome.InvalidState -> "Prescription cannot be approved"
}
```

This is valuable at the domain boundary because adding a new outcome can force its callers to make a decision. It is not magic completeness. A sealed type only describes the alternatives included in that hierarchy. It does not account for a network timeout or a database process dying unless the application chooses to model those as a result too.

Do not create a large sealed hierarchy for every internal detail. Model the outcomes that the caller can act on. Let infrastructure failures follow the application's normal error and retry policy.

## `Result` Is Not the Same as a Domain Model

Kotlin's `Result<T>` can represent success or failure, and it can be useful for a boundary where the operation's failure is naturally exceptional or technical. It is not automatically the best public contract for a business workflow.

Compare these two ideas:

```kotlin
fun approve(id: String): Result<Prescription>
```

and:

```kotlin
fun approve(id: String): ApprovalOutcome
```

The first says that the operation may succeed or fail, but the failure type may be too vague. The second says that “not found” and “invalid state” are expected business decisions. A service can still throw or return a separate technical failure when PostgreSQL or RabbitMQ is unavailable.

The choice depends on the boundary:

- A repository lookup can return `Entity?`.
- A domain operation with expected alternatives can return a sealed outcome.
- A command adapter may translate technical failures into an HTTP error or retry response.
- An unexpected programming error should not be disguised as an ordinary business outcome.

## Exceptions Have a Place

Exceptions are appropriate when the caller cannot reasonably continue the current operation or when the failure is outside the expected business alternatives. Examples include a database connection failure, a serialization error, or a violated internal invariant.

They should not be the only way to represent normal workflow decisions. If a patient tries to approve an already rejected prescription, that is not necessarily an exceptional process failure. It is an expected invalid transition that may become a conflict response.

At an HTTP boundary, the application can translate outcomes deliberately:

| Internal outcome | Possible HTTP behavior |
| --- | --- |
| Not found | `404 Not Found` |
| Invalid state | `409 Conflict` |
| Invalid input | `400 Bad Request` |
| Database unavailable | `503 Service Unavailable` or retry policy |
| Unexpected defect | `500 Internal Server Error` |

The exact mapping is a product decision. The important point is that the service does not lose the distinction before it reaches the adapter.

## Avoid Overusing Scope Functions

Kotlin's `let`, `also`, `run`, and `apply` are useful, but nested chains can hide error behavior:

```kotlin
repository.findPrescription(id)
    ?.let { validate(it) }
    ?.also { publish(it) }
```

What happens when validation fails? Does `publish` run? Is the return value the prescription, a nullable value, or a result? Can the reader see which operation owns the transaction?

For business workflows, explicit statements are often better:

```kotlin
val prescription = repository.findPrescription(id)
    ?: return ApprovalOutcome.NotFound

if (!prescription.canBeApproved()) {
    return ApprovalOutcome.InvalidState
}

return approveAndRecord(prescription)
```

This is not anti-functional Kotlin. It is a readability choice at a boundary where persistence and messaging semantics matter.

## Make Invalid States Harder To Reach

The most useful type design is the one that reduces accidental invalid operations. A single mutable `status` string makes every caller responsible for remembering the legal transition graph. A sealed state model can make the graph visible, though the database must still enforce concurrency.

For example, the application may distinguish `PendingApproval` from `ReadyForPackaging` rather than passing arbitrary strings between methods. The state model should remain aligned with the persisted representation and should not pretend that in-memory types alone prevent another process from changing the row.

Kotlin types improve local reasoning. PostgreSQL conditional updates and transactions protect the shared system. RabbitMQ acknowledgement and idempotency protect asynchronous processing. These are complementary guarantees, not substitutes for one another.

## Interview Questions To Rehearse

- When is `T?` a better return type than a sealed result?
- Why is an invalid state transition not always an exception?
- What does exhaustive `when` guarantee for a sealed hierarchy?
- Where should a database outage be represented and handled?
- Why can Kotlin's `Result` be too generic for a domain API?
- Why do Kotlin types not eliminate the need for conditional database updates?

## Interview Takeaway

Use nullable types for absence, sealed outcomes for expected business alternatives, and exceptions for failures that the current operation cannot handle normally; make that distinction visible at the API boundary.
