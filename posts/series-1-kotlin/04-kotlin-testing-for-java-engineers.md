# Kotlin Testing for a Java Engineer

The pharmacy challenge is small enough that tests should be part of the design, not an afterthought. A reviewer should be able to run the project, observe the happy path, and see evidence for the important failure cases.

An experienced Java engineer already knows JUnit, mocks, integration tests, and test pyramids. The Kotlin work is mostly learning how to express those tests clearly and deciding where a real PostgreSQL or RabbitMQ instance is required.

## Start With Domain Transition Tests

State transitions are cheap to test without Spring, a database, or RabbitMQ. A test should make the business rule obvious:

```kotlin
@Test
fun `rejected prescription cannot be approved`() {
    val order = prescription(status = PrescriptionStatus.REJECTED)

    val result = order.approve()

    assertEquals(ApprovalOutcome.InvalidState, result)
}
```

The exact assertion library is less important than the test's focus. This test proves a domain rule. It does not prove that a PostgreSQL update is conditional or that a RabbitMQ message is acknowledged correctly.

Useful state cases include:

- submission with valid medication lines;
- rejection from the approval state;
- approval from an invalid state;
- packaging only after approval;
- fulfillment only after packaging is complete;
- repeated commands and duplicate event handling;
- invalid quantities and missing medication IDs.

Prefer a small test data builder to repeating a large object literal:

```kotlin
fun prescription(
    status: PrescriptionStatus = PrescriptionStatus.AWAITING_APPROVAL
) = Prescription(
    id = "order-1",
    patientId = "patient-1",
    status = status,
    items = listOf(PrescriptionItem("amoxicillin", 1))
)
```

Defaults should represent a valid, common case. A test that changes one relevant property is easier to understand than a fixture that hides every field.

## Test Outcomes, Not Implementation Details

A service test can verify that an approval returns the expected outcome and records the right collaborators being called. Be careful with excessive interaction assertions:

```kotlin
verify(exactly = 1) { publisher.publish(any()) }
```

That may be useful when publication is a business requirement, but it can become brittle if the implementation changes from one publisher call to a batch or an outbox write. Ask what behavior the test protects.

For the challenge, high-value service tests include:

- a valid submission persists the order and expected lines;
- insufficient inventory returns a clear outcome;
- approval creates the next workflow event or outbox entry;
- an invalid transition does not publish a misleading event;
- a technical failure is surfaced for retry rather than converted into success.

## Where Mocks Stop Being Convincing

Mocks are useful for a fast unit suite. They cannot prove:

- PostgreSQL constraints and transaction behavior;
- affected-row behavior under concurrent updates;
- migration correctness;
- RabbitMQ exchange bindings and routing;
- manual acknowledgement and redelivery;
- dead-letter configuration;
- serialization compatibility.

Those behaviors require integration tests against real dependencies, ideally using Docker or Testcontainers. For a two-hour submission, one end-to-end test through the real stack is more valuable than dozens of mocked listener tests.

## Test The Outbox And Consumer Together With Their Database

The important outbox guarantee is that the business change and outbox row commit together. A useful integration test should force or simulate the relay path:

1. Create or approve a prescription through the service.
2. Verify the PostgreSQL state and outbox row in one transaction outcome.
3. Run the relay against RabbitMQ.
4. Let the worker consume the message.
5. Verify the next business state.

Then test the failure windows. If a message is published and the relay crashes before marking the outbox row, the message may be published again. The consumer must remain safe. If the consumer commits its business effect and crashes before acknowledgement, RabbitMQ may redeliver. The inbox uniqueness constraint must make the second delivery harmless.

These are not “RabbitMQ tests” or “database tests” in isolation. They are cross-boundary behavior tests.

## Test SSE As A Protocol, Not A Browser Demo

If SSE is included, a browser showing changing text is not proof of correctness. The test should cover:

- the event is scoped to the requested prescription and authorized patient;
- events include stable IDs or sequence numbers;
- reconnecting with `Last-Event-ID` does not lose an update;
- events for one prescription are ordered;
- one patient cannot observe another patient's events;
- a disconnected client does not cause the worker queue to lose business work.

The challenge can still use a simple GET status endpoint as the baseline. SSE then becomes an explicit enhancement with its own integration tests.

## A Practical Test Set For The Time Box

For the submitted challenge, prioritize:

1. Domain state-transition tests.
2. One end-to-end happy path from submission through fulfillment.
3. An outbox or duplicate-delivery test if reliability patterns are included.
4. A PostgreSQL or RabbitMQ integration test for the highest-risk invariant.
5. SSE reconnect and isolation tests if SSE is submitted as a feature.

Add structured logs and a few metrics alongside the failure paths. Full tracing is useful in production, but it is less valuable than proving the workflow works.

## Tests As Documentation

Tests should make assumptions visible. A test named `prescription is fulfilled` is less useful than `approved prescription moves through packaging before it becomes ready`. Names should communicate the workflow and the reason for the assertion.

The README should point to the important tests and explain how to run them. A reviewer should not need to reverse-engineer the architecture from test setup.

## Interview Questions To Rehearse

- Which tests should run without infrastructure?
- Which claims require real PostgreSQL or RabbitMQ?
- How would you test an outbox relay crash window?
- Why is a browser demo insufficient evidence for SSE correctness?
- What is the highest-risk invariant in the pharmacy system?
- If time is cut in half, which tests remain?

## Interview Takeaway

Use fast Kotlin tests for domain rules, real PostgreSQL and RabbitMQ tests for boundary semantics, and protocol-level SSE tests for reconnect, ordering, and patient isolation.
