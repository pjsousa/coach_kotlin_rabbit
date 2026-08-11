# Reliability Pharmacy Prescription Fulfillment System - Exercise

## Objective

Continue directly from `exercise_01_foundation.md`. Preserve the patient workflow and API shape where practical, then make the asynchronous workflow safe under at-least-once delivery and concurrent work.

The goal is not to create a distributed platform. The goal is to make a small pharmacy service whose failure behavior a Product Engineer interviewer can probe without finding an unexplained dual-write or duplicate-processing hole.

## Starting Point

Use the completed Foundation implementation. Before changing it:

1. Run its tests.
2. Demonstrate the happy path.
3. Read its limitation notes.
4. Reproduce or simulate at least one direct-publish failure window.

Do not redesign the product or add a frontend. This exercise is about persistence and message reliability.

## Background and Motivation

The Foundation implementation can lose work if a database transaction and RabbitMQ publication are treated as one operation without a coordination pattern. It can also repeat side effects when a worker crashes before acknowledging a message.

RabbitMQ and PostgreSQL do not provide one shared transaction in this design. Use explicit at-least-once behavior and make duplicates safe.

## System Specification

### Required reliability behavior

- A prescription state change and its outbound workflow event are committed together in PostgreSQL.
- A relay publishes committed outbox rows to RabbitMQ.
- The relay handles publication uncertainty without silently losing the event.
- Consumers acknowledge only after durable business effects are committed.
- Consumer redelivery and publisher duplication are safe.
- Transient failures retry with a bounded policy.
- Permanent or poison messages reach a dead-letter path.
- Inventory cannot become negative under concurrent reservation attempts.
- State transitions are conditional on the expected previous state.

### Required data concepts

Extend the Foundation model with appropriate equivalents of:

- an outbox record with stable event identity and publication state or attempt data;
- an inbox/idempotency record with a uniqueness constraint;
- status or workflow data needed to identify the current business state;
- retry or dead-letter metadata where it belongs.

Do not add fields solely to make a diagram look sophisticated. Each field must support a failure or operational decision.

### RabbitMQ requirements

Document and implement:

- durable exchanges and queues where appropriate;
- routing keys and bindings;
- publisher confirms;
- manual consumer acknowledgement;
- prefetch and consumer concurrency choices;
- bounded retries;
- dead-letter exchange or equivalent dead-letter topology;
- handling for unroutable or rejected messages.

### Target metric

In a local Docker environment, aim to sustain approximately 10 prescription workflow submissions per second without negative inventory or unsafe duplicate side effects. Measure what you can and state the machine and test conditions.

## Time-box Guidance

### First 30 minutes

- Reproduce the Foundation failure window.
- Draw the outbox, relay, exchange, queue, consumer, and inbox flow.
- Decide which event is the first reliability target.

### Next 60 minutes

- Implement the outbox transaction.
- Implement or improve the relay.
- Add conditional PostgreSQL updates and inventory protection.

### Final 30 minutes

- Add consumer idempotency and acknowledgement behavior.
- Add one retry/DLQ path.
- Run failure tests and document remaining gaps.

If time is short, prioritize one complete reliable event path over a partial reliability framework across every transition.

## Step-by-Step Exercise Guide

### 1. Write the failure matrix

List the point of failure and expected result for:

- database transaction fails;
- database commit succeeds and relay crashes before publication;
- broker confirms publication and relay crashes before marking the row;
- publication is unroutable;
- consumer crashes before business commit;
- consumer commits and crashes before acknowledgement;
- transient downstream failure;
- permanent malformed message.

Key decision: state which component is allowed to retry and which component must not acknowledge.

Study: RabbitMQ publisher confirms, manual acknowledgements, and outbox posts in `artifacts/blog-plan.md`.

Verify: every row in the matrix has a no-loss, duplicate-safe, or explicitly operator-recovered outcome.

### 2. Add transactional outbox persistence

Change the application transaction so the business state change and outbound event record commit together. Include a stable event ID and enough payload or reference data for the relay to publish the event.

Key decision: choose how rows are claimed by one or more relay workers. Explain what happens when a relay crashes after publication but before local bookkeeping.

Do not make the outbox promise exactly-once publication. The consumer must tolerate duplicates.

Study: `artifacts/blog-plan.md`, Series 2 and Series 3 showcase plans.

Verify: inspect PostgreSQL after a successful state transition and show that the outbox record exists in the same committed outcome.

### 3. Implement publisher confirmation behavior

Configure the publisher path so it can distinguish a broker confirmation from a local send attempt. Decide how unroutable messages are surfaced and retried.

Key decision: decide when the outbox row is considered published and what metadata is retained for diagnosis. A row marked published before confirmation creates a loss window; a row retried after uncertain confirmation can create a duplicate.

Verify: force or simulate a relay crash/uncertain result and show that the system favors no loss, with duplicate publication handled downstream.

### 4. Make state transitions atomic

Replace read-then-write transitions with database operations that include the expected current state. Check affected rows and map a lost race to a deliberate business outcome.

Key decision: choose where a status-history insert and outbox insert belong relative to the conditional current-state update.

Study: PostgreSQL transactions, isolation, conditional updates, and concurrency from `artifacts/blog-plan.md`.

Verify: run competing approval or packaging commands and prove that only one request wins a single transition.

### 5. Protect inventory

Implement an atomic reservation decision so concurrent requests cannot both claim the last unit. Decide whether reservation and later release are required for your chosen workflow.

Key decision: compare an atomic conditional update with an explicit row lock. Explain the deadlock and retry implications of your choice.

Verify: run concurrent reservations against a small inventory quantity and assert that inventory never becomes negative and accepted reservations match available stock.

### 6. Add idempotent consumers

Introduce an inbox or equivalent record keyed by the consumer identity and stable event ID. Coordinate the idempotency record and the business effect in one database transaction.

Key decision: decide whether a duplicate that already committed should be acknowledged, retried, or sent to a dead-letter path. The answer should depend on whether the prior business effect is known to have committed.

Study: `posts/series-1-kotlin/03-state-machines-with-sealed-types.md` and the idempotent-consumer plan.

Verify: deliver the same event twice and prove that the business side effect happens once while both deliveries eventually reach a safe acknowledgement outcome.

### 7. Add bounded retries and a DLQ

Classify failures as retryable or permanent. Add a bounded retry route with delay appropriate to the local implementation, then route exhausted or malformed messages to a dead-letter destination.

Key decision: prevent immediate infinite requeue loops. Document how an operator would inspect and replay a dead-letter message.

Verify: inject a transient failure and observe retry; inject a permanent failure and observe dead-lettering; assert that a poison message does not block unrelated work indefinitely.

### 8. Add integration evidence

Use real local PostgreSQL and RabbitMQ instances for the highest-risk tests. Retain fast Kotlin unit tests for domain rules.

Minimum new evidence:

- outbox row and business state commit behavior;
- duplicate publication or redelivery;
- conditional transition race;
- inventory race;
- retry and DLQ path;
- end-to-end workflow still works.

Verify: document which guarantees are proven by mocks, unit tests, broker tests, and database tests.

## Required Decisions

Document:

- why the system is at-least-once;
- when the consumer acknowledges;
- how duplicate event IDs are stored and checked;
- how relay uncertainty is handled;
- how retryable and permanent failures differ;
- how inventory concurrency is protected;
- why the design does not claim exactly-once delivery;
- which reliability features remain incomplete due to time.

## Tests and Evidence

At minimum, produce:

- a failure matrix;
- real PostgreSQL and RabbitMQ integration coverage for one full reliable workflow;
- a duplicate-delivery test;
- a concurrency test for state or inventory;
- a retry and dead-letter test;
- logs containing order ID and event ID for failure diagnosis;
- a measured or bounded-load result for the 10 submissions/second target.

## Bottleneck and Reflection Questions

- What if the broker confirms a message and the relay crashes before updating the outbox row?
- What if the consumer commits the inbox and business effect but its acknowledgement is lost?
- Why is an inbox uniqueness constraint not sufficient if it is committed separately from the business effect?
- What happens to a poison message after the retry limit?
- Can two different event types for one prescription be processed out of order?
- Which guarantees are local to one prescription and which are global?

## Success Criteria

- Foundation behavior still works.
- Business state and outbound event creation are committed together.
- Broker uncertainty favors no loss and tolerates duplicates.
- Consumers acknowledge after durable effects.
- Duplicate events do not repeat business side effects.
- Retryable failures are bounded and permanent failures are dead-lettered.
- Concurrent inventory reservations cannot create negative stock.
- Tests and README explain the guarantees without claiming exactly-once.

## Interview Defense Checklist

Be ready to explain:

- every crash window in the outbox and consumer paths;
- why publisher confirms do not mean business processing succeeded;
- why acknowledgement timing matters;
- how PostgreSQL constraints and transactions complement RabbitMQ delivery;
- how the design stays simple enough for the original challenge;
- what production features you would add next and why.
