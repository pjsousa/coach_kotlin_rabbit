# Interview Blog Curriculum Plan

Status: in_progress

## Writing Status

| Series | Article | Status | Expected file |
| --- | --- | --- | --- |
| Kotlin | Kotlin for Java Developers: The Mental Model Shift | written | `posts/series-1-kotlin/01-kotlin-for-java-developers.md` |
| Kotlin | Nullability, Results, and Domain Errors in Kotlin | written | `posts/series-1-kotlin/02-nullability-results-domain-errors.md` |
| Kotlin | State Machines with Sealed Kotlin Types | written | `posts/series-1-kotlin/03-state-machines-with-sealed-types.md` |
| Kotlin | Kotlin Testing for a Java Engineer | written | `posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md` |
| Kotlin | From Java Service to Idiomatic Kotlin Prescription Domain | written | `posts/series-1-kotlin/05-showcase-kotlin-prescription-domain.md` |
| PostgreSQL | PostgreSQL Schema Design for a Workflow Product | written | `posts/series-2-postgres/01-schema-design.md` |
| PostgreSQL | Transactions, Isolation, and Atomic State Changes | written | `posts/series-2-postgres/02-transactions-isolation.md` |
| PostgreSQL | Inventory Reservation Without Overselling | written | `posts/series-2-postgres/03-inventory-reservation.md` |
| PostgreSQL | Indexes, Query Plans, and Queue-Facing Reads | written | `posts/series-2-postgres/04-indexes-query-plans.md` |
| PostgreSQL | Testing PostgreSQL Behavior for Real | written | `posts/series-2-postgres/05-testing-postgresql.md` |
| PostgreSQL | A Correct Pharmacy Persistence Model Under Concurrent Demand | written | `posts/series-2-postgres/06-showcase-concurrent-persistence.md` |
| RabbitMQ | AMQP Topology for Pharmacy Workflow Messages | written | `posts/series-3-rabbitmq/01-amqp-topology.md` |
| RabbitMQ | Publisher Confirms and the Outbox Relay | written | `posts/series-3-rabbitmq/02-publisher-confirms-outbox.md` |
| RabbitMQ | Manual Acknowledgements, Prefetch, and Consumer Concurrency | planned | `posts/series-3-rabbitmq/03-ack-prefetch-concurrency.md` |
| RabbitMQ | Retries, Dead Letters, and Poison Messages | planned | `posts/series-3-rabbitmq/04-retries-dead-letters.md` |
| RabbitMQ | Idempotent Consumers and Ordering Guarantees | planned | `posts/series-3-rabbitmq/05-idempotency-ordering.md` |
| RabbitMQ | RabbitMQ Operational Testing | planned | `posts/series-3-rabbitmq/06-operational-testing.md` |
| RabbitMQ | Reliable Prescription Fulfillment with RabbitMQ | planned | `posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md` |
| Product/SSE | Designing the Minimum Patient-First API | planned | `posts/series-4-product-sse/01-patient-first-api.md` |
| Product/SSE | SSE Correctness: IDs, Replay, Ordering, and Isolation | planned | `posts/series-4-product-sse/02-sse-correctness.md` |
| Product/SSE | Testing Realtime Patient Experiences | planned | `posts/series-4-product-sse/03-testing-realtime.md` |
| Product/SSE | Scoping a Two-Hour Versus Five-Hour Challenge | planned | `posts/series-4-product-sse/04-time-box-scoping.md` |
| Product/SSE | From Prescription Submission to Patient Notification: A Defensible Product Slice | planned | `posts/series-4-product-sse/05-showcase-patient-notification.md` |
| Interview | How to Walk Through a Take-Home System | planned | `posts/series-5-interview/01-take-home-walkthrough.md` |
| Interview | Explaining Tradeoffs Without Overclaiming | planned | `posts/series-5-interview/02-tradeoffs.md` |
| Interview | The Proprietary Database Leadership Story | planned | `posts/series-5-interview/03-proprietary-database-story.md` |
| Interview | Defending the Pharmacy Challenge in a Product Engineer Interview | planned | `posts/series-5-interview/04-showcase-interview-defense.md` |

## Candidate Context

- Candidate: Lead Backend Engineer
- Target: Product Engineer
- Interview domain: pharmacy/healthcare fulfillment
- Preparation goal: become interview-ready in 60 hours while building a Kotlin/RabbitMQ/PostgreSQL challenge
- Authoritative assessment: `artifacts/coach-assessment.md`
- Primary language: Kotlin, approached from a senior Java background
- Stack: Kotlin, Spring Boot, RabbitMQ, PostgreSQL, REST, Docker, and SSE

The curriculum intentionally consolidates related keywords into focused posts. The 60-hour budget favors implementation and interview rehearsal over a large reading backlog.

## Series 1: Kotlin Without Java Habits

### Series goal

Build enough idiomatic Kotlin fluency to model the pharmacy domain clearly and explain Kotlin choices during code review. This directly addresses the highest-risk gap in `artifacts/coach-assessment.md`.

### Post plan

1. **Kotlin for Java Developers: The Mental Model Shift** *(written: `posts/series-1-kotlin/01-kotlin-for-java-developers.md`)*
   - Overview: expressions, `val`/`var`, constructors, properties, data classes, collections, and the Java idioms that should not be translated mechanically.
   - Interview value: demonstrates deliberate Kotlin adoption rather than Java syntax with Kotlin punctuation.
   - Kickoff prompt: Write a concise, practical post for a lead backend engineer preparing for a Product Engineer interview in a pharmacy domain. Read `artifacts/coach-assessment.md`. Explain Kotlin's core mental model using a prescription domain example. Cover `val`/`var`, data classes, properties, collection choices, and Java interop. Use small Kotlin examples and avoid generic language-tour fluff.

2. **Nullability, Results, and Domain Errors in Kotlin** *(written: `posts/series-1-kotlin/02-nullability-results-domain-errors.md`)*
   - Overview: nullable types, safe calls, sealed results, exhaustive `when`, `require`, `check`, and exception boundaries.
   - Interview value: turns “not found” and “invalid transition” into explicit API contracts.
   - Kickoff prompt: Write an interview-relevant Kotlin post aligned with `artifacts/coach-assessment.md`. Model prescription lookup and approval outcomes using nullable values, sealed hierarchies, and exceptions. Explain which failures belong in each model, with practical examples and tradeoffs. Target an experienced Java engineer new to Kotlin; avoid textbook repetition.

3. **State Machines with Sealed Kotlin Types** *(written: `posts/series-1-kotlin/03-state-machines-with-sealed-types.md`)*
   - Overview: state modeling, legal transitions, immutability, value objects, and preventing invalid workflow operations.
   - Interview value: connects Kotlin type design to pharmacy workflow correctness.
   - Kickoff prompt: Write a practical post for the target Product Engineer interview explaining how Kotlin sealed types and immutable domain models can represent prescription workflow states. Tie every concept to approval, packaging, fulfillment, and rejection. Include guiding examples, pitfalls, and interview tradeoffs, but no complete challenge solution.

4. **Kotlin Testing for a Java Engineer** *(written: `posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md`)*
   - Overview: JUnit 5, Kotlin assertions, Mockito-Kotlin or equivalent choices, test data builders, parameterized tests, and behavior-focused tests.
   - Interview value: supports offline code review and challenge confidence.
   - Kickoff prompt: Write a concise technical post based on `artifacts/coach-assessment.md` about testing Kotlin Spring services as an experienced Java engineer. Cover idiomatic test setup, state-transition tests, test data builders, and avoiding brittle mocks. Use the pharmacy domain and explain what should be tested at unit versus integration level.

### Showcase article *(written: `posts/series-1-kotlin/05-showcase-kotlin-prescription-domain.md`)*

**From Java Service to Idiomatic Kotlin Prescription Domain**

Kickoff prompt: Unify the four posts into one coherent walkthrough of modeling and testing a prescription domain in Kotlin for a pharmacy Product Engineer challenge. Explicitly connect nullability, sealed outcomes, state transitions, immutability, and tests. Do not present a production-complete solution; emphasize reasoning, tradeoffs, and interview explanation.

## Series 2: PostgreSQL Persistence and Concurrency

### Series goal

Transfer the candidate's SQL Server knowledge to PostgreSQL patterns required for safe inventory, workflow transitions, and outbox persistence.

### Post plan

1. **PostgreSQL Schema Design for a Workflow Product**
   - Overview: prescriptions, medication lines, inventory, status history, outbox, inbox, constraints, and migration discipline.
   - Interview value: demonstrates simple persistence design without over-modeling.
   - Kickoff prompt: Using `artifacts/coach-assessment.md`, write a practical PostgreSQL schema-design post for the pharmacy challenge. Cover tables, keys, foreign keys, uniqueness, status history, outbox, inbox, and migrations. Compare relevant PostgreSQL choices with SQL Server assumptions and focus on interview tradeoffs.

2. **Transactions, Isolation, and Atomic State Changes**
   - Overview: `READ COMMITTED`, transaction boundaries, conditional updates, affected-row counts, and `RETURNING`.
   - Interview value: proves concurrency reasoning in concrete PostgreSQL terms.
   - Kickoff prompt: Write an interview-focused PostgreSQL post for a senior Java engineer moving to Kotlin/Spring. Explain transaction boundaries for prescription approval, rejection, and inventory reservation. Use conceptual SQL examples, discuss `READ COMMITTED`, and explain when a conditional update is enough versus when a broader transaction is required.

3. **Inventory Reservation Without Overselling**
   - Overview: atomic decrement, row locking, optimistic versus pessimistic approaches, deadlocks, and failure recovery.
   - Interview value: directly addresses a high-risk pharmacy invariant.
   - Kickoff prompt: Write a practical post aligned with the pharmacy challenge explaining how PostgreSQL prevents two orders from claiming the last medication unit. Cover atomic updates, `SELECT FOR UPDATE`, affected rows, transaction boundaries, and deadlock tradeoffs. Include concurrency questions a Product Engineer interviewer might ask.

4. **Indexes, Query Plans, and Queue-Facing Reads**
   - Overview: indexes for patient status and pharmacist queues, composite/partial indexes, `EXPLAIN ANALYZE`, pagination, and connection pooling.
   - Interview value: shows performance judgment without premature tuning.
   - Kickoff prompt: Write a concise PostgreSQL performance post for the target challenge. Show how to choose indexes based on real patient and pharmacist query shapes, how to validate with `EXPLAIN ANALYZE`, and what not to optimize prematurely. Connect to Spring connection pooling and Kotlin repository code only where helpful.

5. **Testing PostgreSQL Behavior for Real**
   - Overview: Testcontainers, migrations, seeded scenarios, integration tests, and the limits of in-memory fakes.
   - Interview value: connects to the candidate's proprietary-database leadership example.
   - Kickoff prompt: Write an interview-relevant post using the database validation story in `artifacts/coach-assessment.md`. Explain when an in-memory test double is useful, when real PostgreSQL is authoritative, and how deterministic seed/prune tooling supports CI. Use the pharmacy workflow as the example.

### Showcase article

**A Correct Pharmacy Persistence Model Under Concurrent Demand**

Kickoff prompt: Unify the PostgreSQL posts into one scenario covering prescription creation, inventory reservation, approval, status history, outbox insertion, and concurrent requests. Explicitly connect constraints, transactions, locks, indexes, and integration tests. Keep the design minimal enough for a 2-5 hour challenge and explain what is intentionally omitted.

## Series 3: RabbitMQ Beyond the Tutorial

### Series goal

Turn strong conceptual messaging knowledge into precise RabbitMQ implementation and operational understanding.

### Post plan

1. **AMQP Topology for Pharmacy Workflow Messages**
   - Overview: exchanges, queues, bindings, routing keys, direct/topic/fanout choices, durability, and separation of work from notification.
   - Interview value: supports the system-design walkthrough.
   - Kickoff prompt: Write a practical RabbitMQ topology post for the Kotlin/Spring pharmacy challenge described in `artifacts/coach-assessment.md`. Design approval, packaging, fulfillment, and status-message flows. Explain exchange and queue choices, worker competition, and why patient updates should not be implemented as competing consumers on a shared work queue.

2. **Publisher Confirms and the Outbox Relay**
   - Overview: broker confirms, routing versus processing, persistent messages, relay crash windows, and duplicate publication.
   - Interview value: addresses the primary dual-write failure mode.
   - Kickoff prompt: Write an interview-focused RabbitMQ post explaining publisher confirms and a transactional outbox relay for the pharmacy challenge. Distinguish database commit, broker acceptance, routing, and consumer processing. Cover stable event IDs and why duplicate publication remains possible.

3. **Manual Acknowledgements, Prefetch, and Consumer Concurrency**
   - Overview: delivery tags, ack/nack/reject, redelivery, prefetch, concurrency, and acknowledgement after durable business effects.
   - Interview value: supports exact failure walkthroughs.
   - Kickoff prompt: Write a practical post for a senior Java engineer learning RabbitMQ in Kotlin. Explain manual acknowledgement timing, prefetch, consumer concurrency, redelivery, and crash windows using packaging workers. Include pitfalls and questions an interviewer may use to expose vague understanding.

4. **Retries, Dead Letters, and Poison Messages**
   - Overview: transient versus permanent errors, retry queues, TTL, dead-letter exchanges, bounded retries, and replay operations.
   - Interview value: failure handling is explicitly assessed by the challenge.
   - Kickoff prompt: Write a technically substantial RabbitMQ post for the pharmacy workflow. Compare immediate requeue, delayed retry queues, and dead-letter exchanges. Explain how to classify failures, bound retries, inspect poison messages, and safely replay them. Avoid claiming exactly-once delivery.

5. **Idempotent Consumers and Ordering Guarantees**
   - Overview: inbox tables, unique event IDs, per-prescription ordering, parallelism, and exactly-once myths.
   - Interview value: connects RabbitMQ delivery semantics to PostgreSQL transactions.
   - Kickoff prompt: Write an interview-prep post aligned with `artifacts/coach-assessment.md` on idempotent RabbitMQ consumers. Use a prescription event sequence and explain how an inbox constraint, transaction, and post-commit acknowledgement interact. Discuss what ordering can and cannot be guaranteed.

6. **RabbitMQ Operational Testing**
   - Overview: Testcontainers, topology checks, redelivery, duplicate delivery, retry limits, DLQ assertions, and observability.
   - Interview value: turns design claims into proof.
   - Kickoff prompt: Write a practical post explaining how to test RabbitMQ behavior in a Kotlin/Spring challenge. Distinguish mocks from broker integration tests and cover redelivery, duplicate publication, manual ack, retries, and DLQ behavior. Keep the scope realistic for a short take-home exercise.

### Showcase article

**Reliable Prescription Fulfillment with RabbitMQ**

Kickoff prompt: Unify the RabbitMQ posts into one end-to-end pharmacy scenario: a PostgreSQL transaction creates an outbox event, a relay publishes it, workers process it with manual acknowledgements and an inbox, transient failures retry, poison messages dead-letter, and patient status is fanned out separately. Explain every crash window and the operational evidence needed to defend the design in an interview.

## Series 4: Product Workflow, Realtime Status, and Scope

### Series goal

Build and defend a simple patient-first product while proving the advanced SSE requirements without confusing backend work queues and client notification streams.

### Post plan

1. **Designing the Minimum Patient-First API**
   - Overview: prescription submission, status lookup, pharmacist actions, packaging actions, error contracts, and scope.
   - Interview value: directly targets the challenge's user-experience and simplicity criteria.
   - Kickoff prompt: Write a practical API-design post for the pharmacy Product Engineer challenge. Define the smallest useful REST surface for patients and simulated staff workers. Explain why status GET is the correctness baseline and how to avoid overbuilding internal UIs.

2. **SSE Correctness: IDs, Replay, Ordering, and Isolation**
   - Overview: SSE framing, event IDs, `Last-Event-ID`, reconnects, monotonic sequence numbers, authorization, and fan-out.
   - Interview value: supports the candidate's explicit advanced learning goal.
   - Kickoff prompt: Write an interview-relevant SSE post for the pharmacy challenge. Explain how to provide patient-specific status updates without making each SSE connection a competing RabbitMQ worker. Cover event IDs, reconnect replay, ordering, authorization, and proving no cross-patient leakage. Compare a status GET baseline with an SSE enhancement.

3. **Testing Realtime Patient Experiences**
   - Overview: reconnect tests, missed-event detection, ordering assertions, isolation tests, and operational diagnostics.
   - Interview value: demonstrates that realtime UX is proven, not merely demoed.
   - Kickoff prompt: Write a practical post describing how to test SSE for prescription status. Cover reconnect with `Last-Event-ID`, event sequence assertions, patient authorization, concurrent patients, and the difference between Postman API tests and a real SSE integration client.

4. **Scoping a Two-Hour Versus Five-Hour Challenge**
   - Overview: MVP slicing, stretch features, failure handling, documentation, and resisting premature UI work.
   - Interview value: directly rehearses time-boxed product judgment.
   - Kickoff prompt: Write a concise Product Engineer interview post based on the pharmacy challenge and `artifacts/coach-assessment.md`. Compare a two-hour correct backend slice with a five-hour reliability and SSE version. Explain what to defer, how to document limitations, and why generated UI should follow backend proof.

### Showcase article

**From Prescription Submission to Patient Notification: A Defensible Product Slice**

Kickoff prompt: Unify the product workflow posts into a realistic walkthrough of the pharmacy system. Start with a minimal patient-first API, add asynchronous staff workflow, then add SSE only after correctness is established. Explicitly show scope decisions, failure behavior, event ordering, and how the candidate would explain the result in a live walkthrough.

## Series 5: Interview Communication for Product Engineers

### Series goal

Convert technical work and leadership experience into clear answers for code review, system design, and behavioral interviews.

### Post plan

1. **How to Walk Through a Take-Home System**
   - Overview: user journey, architecture, invariants, failure modes, tests, and known limitations.
   - Interview value: rehearses the live walkthrough.
   - Kickoff prompt: Write a practical interview-preparation post using the pharmacy challenge and the assessment report. Provide a concise walkthrough structure that starts with patient value, then architecture, data invariants, messaging semantics, tests, and deliberate omissions. Avoid generic presentation advice.

2. **Explaining Tradeoffs Without Overclaiming**
   - Overview: assumptions, alternatives, chosen design, sacrificed properties, and exactly-once/real-time language.
   - Interview value: prevents shallow or overly confident answers.
   - Kickoff prompt: Write an interview-focused post for a lead backend engineer targeting Product Engineer. Use RabbitMQ, PostgreSQL, SSE, and the pharmacy domain to demonstrate precise tradeoff language. Cover at-least-once delivery, ordering scope, replay, and simplicity. Include examples of weak versus strong explanations.

3. **The Proprietary Database Leadership Story**
   - Overview: STAR structure, dependency risk, parallel team enablement, test strategy, production impact, and learning.
   - Interview value: turns the candidate's strongest behavioral evidence into a crisp answer.
   - Kickoff prompt: Write a practical behavioral interview post based only on the experience documented in `artifacts/coach-assessment.md`. Shape the proprietary-database story into a credible STAR answer with concrete impact: eight developers unblocked, production use by approximately 50 users, 100,000 event writes/hour, and testing planned for 500,000. Do not invent details.

### Showcase article

**Defending the Pharmacy Challenge in a Product Engineer Interview**

Kickoff prompt: Unify the communication posts into a mock walkthrough that connects patient value, Kotlin decisions, PostgreSQL invariants, RabbitMQ failure handling, SSE limitations, test evidence, and scope tradeoffs. End with behavioral follow-ups based on the proprietary-database story. Keep the answer realistic and intellectually honest.

## Approval Gate

The curriculum is approved. Five Series 1 articles are written and the remaining 22 articles are planned. Continue one article at a time and update the `Writing Status` table immediately after each article is completed. Do not overwrite an article marked `written`.
