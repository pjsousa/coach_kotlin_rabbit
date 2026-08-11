# Coach Assessment

## Context

- Candidate: Lead Backend Engineer
- Target: Product Engineer
- Level being prepared for: Lead-level technical expectations applied to a product engineering role
- Preparation budget: 30 days x 2 hours = 60 hours
- Primary language: Kotlin
- Stack: Kotlin, Spring Boot, RabbitMQ, PostgreSQL, REST, Docker, and SSE as an advanced capability
- Domain: Pharmacy/healthcare fulfillment
- Resume status: unavailable; assessment is based on self-report and diagnostic answers

## Target Challenge

The challenge asks for a prescription fulfillment system covering:

1. Prescription submission.
2. Inventory verification.
3. Pharmacist approval or rejection.
4. Packaging queue processing.
5. Fulfillment and patient notification.
6. Synchronous patient status visibility.

The solution must use Kotlin and RabbitMQ, document technology decisions, and remain appropriately simple for a 2-5 hour exercise. The stated evaluation areas are patient experience, simplicity, system design, and failure handling.

## Diagnostic Summary

### Strong Existing Signals

- Lead-level backend ownership and team-enabling experience.
- Good distributed-systems instincts: at-least-once delivery, stable event IDs, outbox, inbox/idempotency, manual acknowledgements, and DLQ awareness.
- Strong product prioritization: patient workflow first, simulated pharmacist/packaging workers instead of overbuilding internal UIs.
- Good concurrency intuition: conditional state transitions and checking affected-row counts.
- Strong behavioral example involving a proprietary database, a seven/eight-person team, parallel implementation, reproducible test data, CI validation against the real database, and production scale of approximately 100,000 event writes/hour with capacity planned for approximately 500,000.
- Existing transferable experience with Kafka Streams, Google Pub/Sub, SQL Server, C#, Spring, and production backend systems.

### High-Risk Gaps

- No Kotlin experience yet. Kotlin syntax, nullability conventions, sealed hierarchies, coroutines, structured concurrency, Java interop, and idiomatic testing must be learned through implementation rather than memorized.
- RabbitMQ knowledge is currently conceptual and tutorial-level. The main gaps are publisher confirms, exchange and binding design, delivery tags, acknowledgement timing, prefetch, retry topology, redelivery behavior, quorum/durable queue choices, and operational failure modes.
- PostgreSQL-specific behavior is not yet familiar, despite strong SQL Server and transaction foundations. Focus on PostgreSQL DDL, indexes, isolation, row locks, conditional updates, `RETURNING`, migrations, connection pooling, and query plans.
- Patient status over SSE has not yet been designed or proven. Main risks are reconnects, `Last-Event-ID`, event ordering, authorization/filtering, replay, and avoiding RabbitMQ competing-consumer mistakes.
- The current system-design answer omitted some explicit pharmacist and packaging commands and initially proposed making each patient SSE connection a RabbitMQ consumer. This must be corrected in the exercise architecture.
- The current scope instinct includes generating a UI early. For interview signal, reliable backend behavior, tests, README, and tradeoff explanation should come first.

## Diagnostic Ratings

These are starting estimates, not final interview grades.

| Area | Starting assessment | Confidence | Priority |
| --- | --- | --- | --- |
| Backend ownership and leadership | Strong lead signal | High | Maintain and rehearse |
| Distributed-systems reasoning | Strong conceptual foundation | High | Convert concepts into RabbitMQ evidence |
| Kotlin | Beginner | High | Critical |
| Java-to-Kotlin translation | Not yet demonstrated | High | Critical |
| RabbitMQ | Basic/tutorial familiarity, strong transferable messaging reasoning | High | Critical |
| PostgreSQL | Strong transferable SQL background, PostgreSQL implementation gap | High | High |
| REST/API design | Solid starting point | Medium | Apply to challenge |
| Product prioritization | Strong | High | Maintain |
| SSE/realtime delivery | Beginner design stage | High | High, after baseline workflow |
| Testing strategy | Strong general instincts | Medium | Make failure and concurrency tests concrete |
| Behavioral communication | Strong evidence, needs tighter structure and metrics | High | Rehearse |

## Strategic Focus

- Spend the first third of the plan making Kotlin comfortable enough that language friction does not obscure system design.
- Spend the largest technical block on RabbitMQ reliability and the pharmacy workflow.
- Use PostgreSQL to transfer existing SQL Server knowledge into the exact concurrency and persistence patterns needed by the challenge.
- Build the system progressively: correct synchronous baseline, asynchronous workflow, reliability patterns, then SSE.
- Treat SSE as an advanced learning and proof milestone. The 2-hour submission should not depend on SSE being complete.
- Practice explaining every decision in terms of patient experience, simplicity, failure behavior, and time budget.

## 60-Hour Checklist

Each block is two hours. The interactive prompt is intended for a separate practice session.

### Days 1-5: Kotlin Foundation

#### Day 1: Kotlin syntax and Java translation

- Target: expressions, functions, classes, properties, constructors, visibility, data classes, collections.
- Keyword bank: Kotlin Java interop, data class, primary constructor, val var, Kotlin collections, effective Kotlin.
- Interview prompt: Act as a Kotlin reviewer translating a small Java domain model. Challenge accidental Java idioms and ask for simpler Kotlin choices.

#### Day 2: Null safety and domain errors

- Target: nullable types, safe calls, Elvis, smart casts, `require`, `check`, sealed results, exception boundaries.
- Keyword bank: Kotlin null safety, sealed class exhaustive when, Result type, domain exception.
- Interview prompt: Act as a Kotlin purist. Review an order lookup and approval API and probe whether each failure is absence, invalid state, or infrastructure failure.

#### Day 3: Collections, immutability, and modeling

- Target: immutable data, collection transformations, value objects, enums versus sealed hierarchies, invariants.
- Keyword bank: Kotlin immutable collections, sealed interface, value class, data class copy, collection performance.
- Interview prompt: Act as a skeptical architect. Ask how the prescription model prevents invalid medication quantities and illegal state transitions.

#### Day 4: Java interop and Spring Kotlin style

- Target: platform types, nullability at Java boundaries, constructor injection, configuration, extension functions, testing conventions.
- Keyword bank: Kotlin Spring Boot constructor injection, platform types, Jackson Kotlin, JPA Kotlin pitfalls, MockK versus Mockito Kotlin.
- Interview prompt: Act as a Java-to-Kotlin migration reviewer. Identify unsafe platform types, mutable state, and unnecessary framework ceremony.

#### Day 5: Coroutines fundamentals

- Target: `suspend`, dispatchers, structured concurrency, cancellation, blocking JDBC/client calls, coroutine testing.
- Keyword bank: Kotlin suspend function, structured concurrency, coroutine dispatcher blocking IO, Spring coroutine support, runTest.
- Interview prompt: Act as a Kotlin concurrency interviewer. Give a service using blocking PostgreSQL and RabbitMQ clients and ask where coroutine boundaries belong.

### Days 6-10: PostgreSQL and Persistence

#### Day 6: Schema and migrations

- Target: prescription, medication, prescription items, inventory, status history, outbox, inbox tables.
- Keyword bank: PostgreSQL schema design, foreign key, unique constraint, Flyway, PostgreSQL identity.
- Interview prompt: Act as a data architect. Review the minimum schema and reject unnecessary tables or missing invariants.

#### Day 7: Transactions and isolation

- Target: transaction boundaries, `READ COMMITTED`, atomic conditional updates, commit/rollback, connection pooling.
- Keyword bank: PostgreSQL READ COMMITTED, transaction isolation, HikariCP, conditional update, PostgreSQL RETURNING.
- Interview prompt: Act as a PostgreSQL specialist. Challenge the approval and inventory reservation transaction boundaries.

#### Day 8: Concurrency and locking

- Target: row locks, lost updates, optimistic versus pessimistic approaches, inventory reservation, affected-row checks.
- Keyword bank: PostgreSQL SELECT FOR UPDATE, atomic decrement, lost update, optimistic locking, deadlock.
- Interview prompt: Act as a production incident interviewer. Present two simultaneous orders competing for the last medication unit.

#### Day 9: Query performance

- Target: indexes, composite indexes, `EXPLAIN`, pagination, query shape, avoiding accidental N+1 access.
- Keyword bank: PostgreSQL EXPLAIN ANALYZE, composite index, partial index, query plan, keyset pagination.
- Interview prompt: Act as a performance reviewer. Ask which patient-status and pharmacist-queue queries need indexes and why.

#### Day 10: Persistence integration

- Target: repository boundaries, migrations, Testcontainers PostgreSQL, deterministic seed/prune tooling, integration tests.
- Keyword bank: Testcontainers PostgreSQL Kotlin, Spring Boot test slices, database integration test, test data builder.
- Interview prompt: Act as a code reviewer. Ask which behaviors must be tested against real PostgreSQL rather than an in-memory fake.

### Days 11-17: RabbitMQ Deep Dive

#### Day 11: AMQP model

- Target: exchanges, queues, bindings, routing keys, direct/topic/fanout choices, durable topology.
- Keyword bank: RabbitMQ exchange queue binding, direct exchange, topic exchange, fanout exchange, durable queue.
- Interview prompt: Act as a RabbitMQ architect. Design the topology for approval, packaging, and status events.

#### Day 12: Publishing guarantees

- Target: publisher confirms, mandatory publishing, unroutable messages, persistent messages, outbox relay behavior.
- Keyword bank: RabbitMQ publisher confirms, confirm select, mandatory flag, persistent message, unroutable message.
- Interview prompt: Act as a reliability interviewer. Probe the difference between a broker confirm, a routed message, and a processed message.

#### Day 13: Consumer acknowledgements

- Target: manual ack, nack, reject, requeue, delivery tags, prefetch, consumer concurrency.
- Keyword bank: RabbitMQ basic ack nack reject, delivery tag, prefetch, consumer acknowledgement.
- Interview prompt: Act as a RabbitMQ operations engineer. Present crashes before and after business commit and ask for exact ack behavior.

#### Day 14: Retries and DLQ

- Target: transient versus permanent failure, delayed retry topology, dead-letter exchange, poison messages, retry limits.
- Keyword bank: RabbitMQ dead letter exchange, TTL retry queue, poison message, exponential backoff, quorum queue.
- Interview prompt: Act as a skeptical reviewer. Reject designs that use unlimited immediate requeue and ask how poison messages are isolated.

#### Day 15: Idempotency and ordering

- Target: stable event IDs, inbox constraints, per-order ordering, duplicate processing, parallelism tradeoffs.
- Keyword bank: idempotent consumer, inbox pattern, RabbitMQ message ordering, partitioning by key, exactly once myth.
- Interview prompt: Act as a distributed-systems interviewer. Ask what ordering is required for one prescription and what can safely run in parallel.

#### Day 16: Outbox implementation

- Target: polling relay, claiming rows, retries, publish status, duplicate relay publication, cleanup.
- Keyword bank: transactional outbox relay, outbox polling, SKIP LOCKED, publisher confirm outbox, outbox cleanup.
- Interview prompt: Act as a principal engineer. Probe relay concurrency, crash windows, observability, and operational recovery.

#### Day 17: RabbitMQ integration testing

- Target: Docker/Testcontainers broker, topology assertions, redelivery, duplicate delivery, DLQ tests.
- Keyword bank: Testcontainers RabbitMQ, RabbitMQ integration test, redelivery count, dead letter integration test.
- Interview prompt: Act as a test strategist. Ask which broker behaviors cannot be proven with mocks.

### Days 18-22: Build The Core Challenge

#### Day 18: Product and API slice

- Target: assumptions, REST endpoints, state machine, patient status contract, error model.
- Keyword bank: REST resource modeling, state transition API, problem details HTTP, API contract test.
- Interview prompt: Act as a product manager. Challenge every endpoint and ask whether it improves the patient journey.

#### Day 19: Submission and inventory

- Target: prescription creation, validation, inventory verification/reservation, PostgreSQL transaction.
- Keyword bank: transactional inventory reservation, aggregate boundary, validation error, PostgreSQL atomic update.
- Interview prompt: Act as a domain reviewer. Probe duplicate submissions, insufficient inventory, and retry behavior.

#### Day 20: Approval and packaging workflow

- Target: pharmacist approval/rejection, outbox event, RabbitMQ packaging queue, worker contract.
- Keyword bank: workflow state machine, RabbitMQ routing key, transactional event publication, worker command.
- Interview prompt: Act as a skeptical architect. Ask what happens if approval is retried or the packaging worker is unavailable.

#### Day 21: Fulfillment and patient status baseline

- Target: packaging completion, fulfillment, authoritative status GET, simulated pharmacist/packager clients.
- Keyword bank: workflow orchestration, status projection, simulated consumer, end-to-end test.
- Interview prompt: Act as a product-focused interviewer. Walk through a patient waiting and ask what they can observe at every step.

#### Day 22: Test and document the MVP

- Target: happy-path end-to-end flow, state tests, curl/Postman collection, README, architecture diagram.
- Keyword bank: executable API documentation, ADR, end-to-end test, architecture decision record.
- Interview prompt: Act as an offline code reviewer. Review the MVP for clarity, scope control, and explainability.

### Days 23-26: Reliability and SSE

#### Day 23: Outbox and idempotent consumers

- Target: add outbox, relay, stable event IDs, inbox uniqueness, safe acknowledgement.
- Keyword bank: outbox inbox pattern, idempotent RabbitMQ consumer, unique event ID, acknowledge after commit.
- Interview prompt: Act as a failure-injection interviewer. Walk through every crash window and demand a recovery result.

#### Day 24: Retry and DLQ operations

- Target: retry classification, delayed retries, DLQ inspection/replay, poison message handling, metrics.
- Keyword bank: retryable exception, dead letter replay, retry count header, RabbitMQ operations metrics.
- Interview prompt: Act as the on-call engineer. Give a stuck packaging queue and ask for diagnosis and remediation.

#### Day 25: SSE fundamentals

- Target: SSE protocol, connection lifecycle, event IDs, keepalive, authorization, application fan-out.
- Keyword bank: Server-Sent Events, Last-Event-ID, SSE reconnect, Spring WebFlux SSE, SSE authorization.
- Interview prompt: Act as a realtime-systems interviewer. Challenge cross-patient isolation, reconnects, and event ordering.

#### Day 26: SSE proof and recovery

- Target: replay from status events, reconnect tests, ordered sequence numbers, no message leakage, god-mode fan-out.
- Keyword bank: SSE integration test, event replay, monotonic sequence, event stream authorization, fan-out.
- Interview prompt: Act as a security and reliability reviewer. Try to make the patient see another patient’s event or miss an update.

### Days 27-30: Interview Readiness

#### Day 27: Code review rehearsal

- Target: explain structure, Kotlin choices, test boundaries, failure handling, and known limitations.
- Keyword bank: Kotlin code review, Spring Boot architecture review, technical debt framing, tradeoff analysis.
- Interview prompt: Act as a demanding offline reviewer. Identify the highest-risk issue and ask for a focused remediation plan.

#### Day 28: System-design rehearsal

- Target: whiteboard the pharmacy system, scope 2 versus 5 hours, scaling, failure modes, observability.
- Keyword bank: system design interview messaging, pharmacy fulfillment workflow, reliability tradeoffs, capacity planning.
- Interview prompt: Act as a skeptical architect and product manager. Interrupt vague claims and require invariants, metrics, and tradeoffs.

#### Day 29: Behavioral rehearsal

- Target: structure the proprietary-database story, technical leadership, ambiguity, disagreement, failure, and learning.
- Keyword bank: STAR leadership interview, technical decision story, ambiguity ownership, stakeholder tradeoff.
- Interview prompt: Act as a product-engineering hiring manager. Probe impact, communication, reversibility, and what the candidate learned.

#### Day 30: Full mock interview

- Target: timed walkthrough, system design, Kotlin/RabbitMQ deep dive, behavioral questions, final gap list.
- Keyword bank: product engineer mock interview, Kotlin RabbitMQ system design, technical challenge walkthrough.
- Interview prompt: Act as a high-pressure Product Engineer interviewer for a pharmacy platform. Run one question at a time and do not rescue shallow answers.

## Recommended Build Progression

1. **Foundation:** Kotlin Spring Boot REST service, PostgreSQL persistence, explicit prescription state transitions, simulated workflow actions, and a correct patient status GET endpoint.
2. **Optimization/reliability:** RabbitMQ topology, outbox relay, publisher confirms, manual acknowledgements, retries, DLQ, inbox/idempotency, and integration tests.
3. **Advanced showcase:** SSE with event IDs, replay/reconnect, ordered per-prescription events, patient isolation, a god-mode operational view, observability, and documented tradeoffs.

## Behavioral Story To Rehearse

Use the proprietary-database project as a STAR story:

- Situation: a required proprietary database had no local/container option, weak documentation, and delayed access.
- Task: keep a team of seven/eight productive while reducing the risk of discovering data-layer problems too late.
- Action: defined database-agnostic contracts, built an in-memory engine and deterministic seed/prune tooling, enabled local/UI/CI testing, and validated the service against the real database through REST and later ODBC.
- Result: eight developers worked after the first sprint; the system reached production for approximately 50 users and 100,000 event writes/hour, with testing planned for approximately 500,000.
- Learning: distinguish fast local feedback from authoritative integration validation, and make external dependency risk visible early.

## Deferred Topics

Given 60 hours, defer unless the challenge or interviewer explicitly requires them:

- Full frontend design system.
- Kubernetes deployment details beyond local Docker and operational discussion.
- Cloud-specific GCP deployment.
- Broad microservice decomposition.
- Exactly-once claims.
- Advanced PostgreSQL tuning unrelated to the workload.
- Full distributed tracing implementation.

## Confidence And Reassessment

The assessment has high confidence in the messaging and leadership reasoning observed, and lower confidence in actual Kotlin implementation ability because no Kotlin code has been written yet. Reassess after the first Kotlin foundation exercise and after the first RabbitMQ integration test. Revise the remaining schedule if either reveals more severe friction than expected.
