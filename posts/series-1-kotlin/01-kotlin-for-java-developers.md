# Kotlin for Java Developers: The Mental Model Shift

Kotlin is easy to start and surprisingly easy to write badly if your mental model remains “Java, but shorter.” For a pharmacy fulfillment service, that distinction matters. The domain has state transitions, asynchronous boundaries, persistence, and user-facing status. Kotlin can make those contracts clearer, but only if the code uses the language's model deliberately.

## Start With Readability, Not Cleverness

Kotlin makes common declarations compact:

```kotlin
data class Prescription(
    val id: String,
    val patientId: String,
    val items: List<PrescriptionItem>
)

data class PrescriptionItem(
    val medicationId: String,
    val quantity: Int
)
```

The primary constructor is also the property declaration. `val` means the property reference cannot be reassigned after construction; it does not make every object reachable from that property deeply immutable. `List` is a read-only view type, not a guarantee that the underlying list can never be changed elsewhere.

The practical design rule is still useful: make domain values immutable at their boundary, and create a new value when a state change occurs.

```kotlin
data class PrescriptionState(val value: State) {
    fun approved(): PrescriptionState = copy(value = State.APPROVED)
}
```

The method above is not enough by itself. It allows approval from every prior state. The important lesson is that concise syntax does not replace domain rules. We will make those rules explicit in the next post.

## `val` Is the Default

Java code often uses mutable fields plus getters and setters because that is the conventional bean shape. Kotlin lets a service expose a read-only property by default:

```kotlin
class PrescriptionView(
    val id: String,
    val status: String
)
```

Use `var` when the object genuinely owns a mutable lifecycle. Do not use it merely because a framework or an old Java convention expects setters. In a workflow system, uncontrolled mutation makes it harder to answer an important question: who changed the prescription from `PENDING` to `APPROVED`, and under what rule?

This does not mean every class should be a data class. A data class is a good fit for values transferred across a boundary or compared by value. A service, repository, or stateful coordinator usually has behavior and lifecycle that deserve a normal class.

## Expressions Make Control Flow Visible

Many Kotlin constructs return values. A simple status mapping can stay local and explicit:

```kotlin
fun patientMessage(status: Status): String = when (status) {
    Status.SUBMITTED -> "Prescription received"
    Status.AWAITING_APPROVAL -> "Waiting for pharmacist approval"
    Status.PACKAGING -> "Prescription is being packaged"
    Status.READY -> "Please collect your prescription"
    Status.REJECTED -> "Prescription could not be fulfilled"
}
```

For an enum, an exhaustive `when` gives the compiler an opportunity to tell us that a new state has not been handled. A Java developer can achieve similar discipline with a switch and tests, but Kotlin makes the expression-oriented form natural.

Do not turn every block into a clever expression. The goal is that the workflow is easy to review. A multi-step transaction with logging, persistence, and event publication may be clearer as named statements than as a chain of `let`, `also`, and `run` calls.

## Collections: Read-Only Does Not Mean Persistent

Kotlin distinguishes read-only collection interfaces from mutable collection interfaces:

```kotlin
fun medicationIds(order: Prescription): List<String> =
    order.items.map { it.medicationId }
```

Prefer the narrowest type a function needs. If a function only reads items, accept `List<PrescriptionItem>`. If it must modify a collection, make that requirement visible with `MutableList` or, more commonly, construct a new list.

The distinction is an API-design signal, not a replacement for database constraints. A caller can still hold a mutable list that is exposed through a read-only interface. PostgreSQL remains responsible for enforcing persistence invariants such as foreign keys, unique event IDs, and non-negative inventory.

## Properties Are Not Just Public Fields

Kotlin properties compile to JVM accessors where appropriate. Java callers generally see getter and setter methods, which makes Kotlin practical in a Spring ecosystem. That interoperability does not mean all Java framework assumptions disappear.

Be deliberate at boundaries:

- Java libraries may expose platform types whose nullability Kotlin cannot prove.
- Serialization libraries need to understand Kotlin constructors and default values.
- Framework proxies and reflection can affect choices around classes, visibility, and final methods.
- Database libraries still have blocking behavior unless an explicitly non-blocking client is used.

The right response is not to avoid Kotlin features. It is to keep framework boundaries narrow and convert external data into well-defined domain types early.

## A Java Habit To Avoid: Mutable Anemic Workflow Objects

This Java-shaped model is easy to write:

```java
order.setStatus(APPROVED);
repository.save(order);
publisher.publish(order);
```

It hides whether the transition was legal, whether the database update won a concurrency race, and what happens when publication fails. Kotlin's concise syntax cannot fix that design. A better boundary is a use-case operation whose result represents the business outcome and whose transaction owns the database changes:

```kotlin
fun approve(orderId: OrderId): ApprovalOutcome
```

The implementation still needs PostgreSQL and RabbitMQ reliability patterns. However, an explicit use-case boundary gives the code review a place to discuss those decisions.

## Practical Translation Rules

When moving a Java service to Kotlin:

1. Start with `val`, immutable domain values, and constructor injection.
2. Replace `Optional` with a deliberate nullable or result contract at each boundary.
3. Use data classes for values, not automatically for every class.
4. Use exhaustive `when` for closed domain alternatives.
5. Keep collection mutability visible.
6. Treat Java and framework boundaries as places where nullability and blocking behavior need explicit review.
7. Prefer named operations that express business intent over chains of collection or scope functions.

## Interview Questions To Rehearse

- What does `val` guarantee, and what does it not guarantee?
- Why is a data class useful for a prescription item but not necessarily for a service?
- Are Kotlin read-only collections deeply immutable?
- How does an exhaustive `when` help when adding a new prescription state?
- What risks exist when Kotlin consumes a Java API with unknown nullability?
- Why does shorter code not automatically mean a safer state transition?

## Interview Takeaway

Kotlin is valuable here not because it removes boilerplate, but because explicit immutability, nullability, closed alternatives, and narrow APIs make workflow decisions easier to see and review.
