# Optimization Pharmacy Prescription Fulfillment System - Exercise

## Objective

Start explicitly from the completed Foundation implementation and preserve its recognizable architecture, API behavior, state vocabulary, and patient journey. Improve the persistence and code-level behavior so the local system can target approximately 10 prescription workflow submissions per second without negative inventory or unsafe duplicate side effects in the declared workflow scope.

This is a local engineering target, not a production capacity claim. The exercise is about PostgreSQL correctness under concurrent demand, query shape, transaction duration, connection use, and evidence from the real database. It is not the full RabbitMQ reliability exercise. Keep the Foundation direct database-write and publish limitation visible, and do not quietly add the transactional outbox, relay confirms, retry topology, dead-letter handling, or SSE before Exercise 3.

For a two-hour challenge mode, reproduce the Foundation bottleneck, make state transitions conditional, make the single-line inventory decision atomic, and run one real PostgreSQL race test. For a five-hour mode, add multi-line lock-order evidence, justified indexes, query plans, connection-pool observations, and a measured local workload. The broader 30-day, 60-hour preparation period should extend the evidence and interview rehearsal, not turn this exercise into a different architecture.

## Starting Point

Use `showcase_projects/pharmacy-fulfillment/exercise_01_foundation.md` as the contract for the starting behavior. Before changing it, run the Foundation unit and API tests, demonstrate patient submission through fulfillment, verify the owner-scoped status `GET`, inspect the basic RabbitMQ route, and read the Foundation failure matrix. Reproduce at least one Foundation bottleneck or race in a controlled local test.

The expected starting system has a straightforward Kotlin/Spring Boot application, PostgreSQL migrations and seed data, a basic RabbitMQ workflow, simulated pharmacist/packager/fulfillment actions, and a patient status endpoint. It may still have a direct write-then-publish path, a read-check-write inventory path, unconditional state writes, insufficient indexes, long transactions, and no reliable message deduplication. Those are inputs to this exercise, not reasons to rewrite the product.

Read the PostgreSQL sources before choosing a fix: `posts/series-2-postgres/01-schema-design.md`, `posts/series-2-postgres/02-transactions-isolation.md`, `posts/series-2-postgres/03-inventory-reservation.md`, `posts/series-2-postgres/04-indexes-query-plans.md`, `posts/series-2-postgres/05-testing-postgresql.md`, and `posts/series-2-postgres/06-showcase-concurrent-persistence.md`. Keep the Foundation API and product scope aligned with `posts/series-4-product-sse/01-patient-first-api.md` and `posts/series-4-product-sse/04-time-box-scoping.md`.

## Background & Motivation

A service can look correct in a sequential demo while two pharmacist commands approve the same prescription, two submissions claim the last medication unit, or every queue read scans a growing table. A Kotlin in-memory check and a Spring transaction annotation do not automatically protect a shared PostgreSQL row. The database operation must express the invariant at the point where it wins or loses.

Exercise 2 therefore separates several ideas that are often collapsed in an interview:

- Database race safety means concurrent PostgreSQL commands cannot violate the modeled state or inventory invariant.
- Message-delivery reliability means the broker and consumer recover from publication uncertainty, redelivery, retry, and poison messages.
- Query optimization means an existing behavior is served with less work after inspecting real query shapes and plans.
- Architectural redesign means introducing a new handoff such as an outbox or a separate projection.

This exercise proves the first and third categories and some command-level duplicate protection. It does not prove the second category. A conditional database transition may make a duplicate workflow command harmless at the state boundary, but without a stable message identity and an inbox it does not prove that every broker-delivered side effect is safe. That remaining distinction is a required handoff to Exercise 3.

## System Specification

### Preserved product behavior

Keep the Foundation patient and staff contracts recognizable: patient submission, owner-scoped status `GET`, pharmacist queue and approval/rejection, packaging progression, collection readiness, and fulfillment. Keep the same state machine unless a measured defect requires a documented correction. Keep simulated staff interfaces and no frontend.

The patient status `GET` remains the correctness baseline. It reads the authoritative current prescription state and may read a bounded timeline. Exercise 2 does not introduce SSE or a separate status projection. A faster query is useful only if it returns the same truthful state as Foundation.

### Optimization target and measurement boundary

Target approximately 10 prescription workflow submissions per second in the local Docker Compose environment, using a stated machine, PostgreSQL/RabbitMQ versions, seed size, test duration, concurrency, request mix, and warm-up period. The target must be measured against the actual application path, not a repository benchmark that bypasses HTTP, PostgreSQL, or the basic workflow.

The target passes only when accepted submissions and their modeled workflow effects do not produce negative inventory, duplicate prescription records for retried submission keys, double-winning state transitions, or unsafe duplicate database side effects. Report rejected commands and expected conflicts separately from infrastructure errors. Do not convert the result into a claim about production throughput, multi-node scaling, or a future cloud deployment.

### Persistence and concurrency responsibilities

Verify or improve versioned migrations and constraints for prescriptions, lines, inventory, reservations, current status, and status history. Positive quantities, foreign keys, legal statuses, unique line rules, non-negative inventory, and submission-key uniqueness should be database-visible decisions. Use PostgreSQL `READ COMMITTED` deliberately: a conditional update can wait for a competing writer and re-evaluate its predicate against the newest committed row version.

Use conditional state writes that include the expected previous state and interpret affected-row counts or returned rows as the outcome. Keep approval, rejection, packaging, and fulfillment transitions from applying history or inventory effects when their state predicate did not win. Use an atomic inventory reservation decision that computes from the current quantity and rejects insufficient stock without relying on a stale application read. For multiple medication lines, make the all-or-nothing rule and stable lock order explicit.

Compare an atomic conditional update, a row lock, and a version-based optimistic approach. Choose the weakest mechanism that expresses each invariant. Explain the deadlock surface, especially for multi-medication operations, and use a small bounded retry only for a safely retryable PostgreSQL deadlock or serialization condition if the chosen design needs it. A deadlock retry is database command recovery; it is not RabbitMQ retry architecture.

### Query, pagination, and connection responsibilities

Name the actual patient status, patient history, pharmacist queue, packaging queue, and any queue-facing database read before adding indexes. Use primary keys where they already serve a single-row lookup. Add composite or partial indexes only where a query shape and measured plan justify them. Use `EXPLAIN ANALYZE` against real PostgreSQL after representative seeding, and inspect estimated versus actual rows, scans, sorts, buffers, and planning/execution time.

Use a bounded queue read and choose offset or keyset pagination deliberately; a pharmacist queue with a stable ordering is a natural place to assess keyset behavior. Observe connection-pool wait, transaction hold time, query count, and any N+1 access. Keep external RabbitMQ calls outside database transactions. Do not fix pool exhaustion by merely increasing the pool before measuring why connections are held.

### Messaging and deferred reliability responsibilities

Keep the Foundation's basic RabbitMQ topology and direct publication path recognizable while optimizing the database work around it. Conditional database transitions may stop two concurrent commands from both changing a prescription, but Exercise 2 must not describe that as an idempotent consumer. There is still no transactional outbox, publisher-confirm relay, bounded broker retry, dead-letter path, inbox uniqueness, or SSE path in this exercise.

When measuring the 10-submission target, include repeated patient submission keys and competing workflow commands where they belong to the application contract. If the test injects duplicate broker deliveries, report precisely which current-state guard makes the database transition a no-op and which external side effects remain unprotected. Do not add a fake inbox solely to make the metric pass; record that architectural change for Exercise 3.

### Failure boundaries and observability

Continue to document the Foundation direct write/publish crash window. Exercise 2 may reduce the chance or duration of database contention, but it cannot make PostgreSQL and RabbitMQ one atomic transaction. Add enough structured local logging and measurements to correlate a request with its prescription, transaction outcome, affected-row result, inventory outcome, query timing, pool wait, and deadlock retry. Do not imply that a fast database response means a message was routed or processed.

### Kotlin guidance for a Java engineer

Preserve the Foundation's idiomatic Kotlin direction: immutable command and response values, read-only collections at boundaries, named transition operations, nullable repository results for ordinary absence, and sealed outcomes for expected business conflicts. Do not load a mutable entity, modify its status, and save it merely because that resembles a Java service; let the repository operation expose whether the conditional write won.

Keep a clear public Spring application-service transaction boundary. Spring's `@Transactional` support is proxy-based, so self-invocation, private method assumptions, and transaction annotations on a method that is never entered through the managed proxy must be reviewed. The transaction should include related PostgreSQL writes but not RabbitMQ calls, slow HTTP calls, or arbitrary sleeps.

Blocking JDBC and broker clients still need threads that can block safely. A coroutine does not make blocking I/O non-blocking. Use the repository's established synchronous style unless there is a concrete coroutine boundary, and if coroutines are introduced, name the dispatcher, cancellation behavior, and transaction context. Use real threads or executors for PostgreSQL concurrency tests, not sequential calls that only resemble a race. Keep Kotlin tests readable with small builders, descriptive names, parameterized state cases where useful, and assertions against outcomes and committed rows. Avoid Java-to-Kotlin mechanical translation, deep scope-function chains, and premature collection or pool tuning; first prove the invariant and query shape.

## Milestone Plan

Complete the milestones in the specified order. The dependencies are deliberate: measuring a query before writing it down, or tuning a pool before measuring hold time, produces weak evidence.

| Order | Milestone | Depends on | Must-have work | Optional stretch |
| --- | --- | --- | --- | --- |
| 1 | Reproduce and measure Foundation bottlenecks | Foundation behavior and evidence | Baseline replay, race reproduction, query/request timing, failure classification | A small repeatable load harness and database lock observation |
| 2 | Make state transitions conditional and race-safe | Milestone 1 state/race evidence | Expected-state predicates, affected-row mapping, no duplicate history on a lost race | Returned-row transition data and command idempotency analysis |
| 3 | Make inventory reservation atomic | Milestones 1-2, inventory model | Current-value conditional reservation, all-or-nothing lines, release discipline | Deadlock retry and reservation lifecycle race tests |
| 4 | Review transaction boundaries and lock behavior | Milestones 2-3 | `READ COMMITTED` reasoning, short transactions, lock order, row-lock versus optimistic decision | A controlled deadlock test and lock-wait measurements |
| 5 | Add indexes based on actual query shapes | Measured reads from Milestone 1 | Patient/queue/history indexes only where justified | Keyset pagination for a realistic queue client |
| 6 | Inspect query plans and connection-pool behavior | Milestone 5 indexes and seeded data | `EXPLAIN ANALYZE`, statistics, pool hold/wait evidence, no external call in transaction | N+1 elimination evidence and plan regression notes |
| 7 | Run concurrency and load tests against the target | All database changes | Real PostgreSQL races, approximately 10 submissions/second attempt, inventory and duplicate-effect assertions | Mixed workload, repeated runs, contention graphs |
| 8 | Document remaining problems requiring architectural changes in Exercise 3 | All prior evidence | Proof ledger, direct publish gap, broker duplicate gap, retry/DLQ/SSE handoff | A risk-ranked production roadmap and interview walkthrough |

The two-hour stopping point is after Milestone 3 with one real PostgreSQL concurrency test and a written statement that RabbitMQ reliability remains deferred. The five-hour version should reach Milestone 8, but it should cut optional query polish before cutting the inventory race evidence. During the broader preparation period, extend each milestone with the cited post, a fresh-container run, a code review rehearsal, and a written distinction between what PostgreSQL proves and what RabbitMQ still does not prove.

### Milestone 1: Reproduce and measure Foundation bottlenecks

**Objective:** Establish a trustworthy before-and-after comparison and make the Foundation's races observable.

**What to implement:** Inspect the Foundation queries, transaction boundaries, basic inventory path, queue reads, connection usage, and direct publish timing. Decide the workload and sample size. Implement only measurement seams or test fixtures needed to reproduce a bottleneck. Test concurrent approval, concurrent last-unit reservation, repeated submission, and the happy path. Measure request latency, database time, queue-facing query time, pool wait if available, and failure counts. Document the baseline and its limits.

**Decisions:** Decide whether the first race is state transition or inventory; decide which measurements are end-to-end versus repository-only; decide how a duplicate command is identified; decide what constitutes an unsafe duplicate effect.

**Directional hints:** Start with a failing or suspicious behavior, not an index wish list. Use real PostgreSQL connections for races. Keep broker publication in the baseline record so a database improvement is not mistaken for a reliability improvement.

**Relevant blog post or concepts:** `posts/series-2-postgres/05-testing-postgresql.md`, `posts/series-2-postgres/06-showcase-concurrent-persistence.md`, `posts/series-2-postgres/04-indexes-query-plans.md`, and `posts/series-3-rabbitmq/02-publisher-confirms-outbox.md`.

**Verification evidence:** A baseline table with environment, seed size, sample count, timing, error categories, and a reproducible race or query observation. Include the Foundation behavior result so later optimization cannot change the product silently.

**Exit criteria:** At least one bottleneck and one correctness weakness are reproduced or credibly evidenced, and the target workload is defined before changes begin.

### Milestone 2: Make state transitions conditional and race-safe

**Objective:** Ensure one concurrent command wins a transition and a losing command produces a deliberate outcome without applying related effects.

**What to implement:** Inspect every state-changing repository operation. Decide the expected previous state for approval, rejection, packaging, ready, and fulfillment. Implement conditional writes, affected-row or returned-row handling, conflict classification, and history creation only after the transition wins. Test two real concurrent commands against the same prescription. Measure contention and winner/loser results. Document why this protects database state but not message delivery in general.

**Decisions:** Decide whether zero affected rows is enough or whether a follow-up read is needed only for `404` versus `409` classification; decide where history sequence allocation occurs; decide how repeated commands are returned to API and worker callers.

**Directional hints:** A preliminary read can improve an error message but must not decide whether the write is safe. Under PostgreSQL `READ COMMITTED`, the conditional write is the arbiter after waiting for a competing writer. Keep the transaction around the state, history, reservation, and any other facts that belong to one command.

**Relevant blog post or concepts:** `posts/series-2-postgres/02-transactions-isolation.md`, `posts/series-1-kotlin/03-state-machines-with-sealed-types.md`, and `posts/series-2-postgres/06-showcase-concurrent-persistence.md`.

**Verification evidence:** Two real concurrent approvals produce one winning transition and one conflict; only one history record and one modeled downstream effect are committed. Repeat the evidence for at least one worker transition.

**Exit criteria:** No ordinary state command relies on an unconditional status save, and a losing race cannot append a second history or downstream database effect.

### Milestone 3: Make inventory reservation atomic

**Objective:** Prevent concurrent submissions from claiming more medication than is available while preserving the all-or-nothing prescription rule.

**What to implement:** Inspect the current inventory read/check/write path and reservation lifecycle. Decide whether a single medication uses an atomic conditional update and when a row lock is justified. Implement a reservation decision based on the current database quantity, affected-row outcome mapping, reservation records, rejection release, and fulfillment consumption. Test two contenders for one unit, many contenders for several units, insufficient stock, all-or-nothing multi-line rollback, duplicate release, and repeated submission. Measure final available quantity, successful reservations, conflicts, and transaction duration. Document why the chosen approach is not a broker idempotency mechanism.

**Decisions:** Decide which quantities are reserved at submission; decide line ordering for multi-medication requests; decide whether insufficient stock is a business conflict rather than a retry; decide how a lost HTTP response and a repeated submission key behave.

**Directional hints:** Put the quantity predicate and the decrement decision at the database boundary. Process multiple inventory rows in a stable global order. Do not decrement again when a reservation becomes consumed. Use a database constraint as a second line of defense, not as the normal insufficient-stock algorithm.

**Relevant blog post or concepts:** `posts/series-2-postgres/03-inventory-reservation.md`, `posts/series-2-postgres/02-transactions-isolation.md`, `posts/series-2-postgres/01-schema-design.md`, and `posts/series-2-postgres/06-showcase-concurrent-persistence.md`.

**Verification evidence:** Real PostgreSQL tests show one winner for the last unit, never a negative final quantity, no partial multi-line reservation, one release, and no second decrement at fulfillment. Assertions read committed state from a fresh connection where practical.

**Exit criteria:** Inventory safety is proven for the tested database command path, and the chosen mechanism and its deadlock implications are written down.

### Milestone 4: Review transaction boundaries and lock behavior

**Objective:** Make the commit boundary, isolation assumptions, lock order, and retryable database failures explicit.

**What to implement:** Inspect service and repository transaction annotations, connection ownership, related writes, external calls, and multi-row access order. Decide the application command boundary and whether conditional updates, `SELECT FOR UPDATE`, or optimistic versions fit each operation. Implement shorter transaction scope, consistent lock ordering, and only the bounded database retry needed for a safe deadlock or serialization failure. Test rollback of related facts and a controlled lock/deadlock scenario. Measure lock wait, transaction duration, deadlock count, and retry count. Document that these are database measurements, not broker recovery evidence.

**Decisions:** Decide whether a row lock is needed for any multi-step decision; decide which SQL state is safe to retry; decide where retry exhaustion becomes a business or technical outcome; decide how Spring proxy entry is verified.

**Directional hints:** `READ COMMITTED` is a useful default when paired with precise writes, not a universal safety guarantee. Never hold a database lock while publishing to RabbitMQ or calling HTTP. If a transaction catches a database exception, do not keep issuing statements in a transaction PostgreSQL has marked failed.

**Relevant blog post or concepts:** `posts/series-2-postgres/02-transactions-isolation.md`, `posts/series-2-postgres/03-inventory-reservation.md`, and `posts/series-2-postgres/05-testing-postgresql.md`.

**Verification evidence:** A transaction-boundary diagram, rollback test for state/history/inventory facts, real lock or deadlock evidence, and a written retry classification. The test must show what was committed after a fresh read, not only what the service returned.

**Exit criteria:** Every related workflow fact has one intentional commit boundary, lock order is shared by all inventory commands, and database retry is bounded and distinguished from insufficient stock.

### Milestone 5: Add indexes based on actual query shapes

**Objective:** Improve reads without adding write cost that has no workload justification.

**What to implement:** Inspect and record the exact SQL query shapes for owner status/history, patient prescription lists if present, pharmacist approval queue, packaging queue, and any database-backed queue poll. Decide equality columns, ordering columns, partial predicates, and pagination strategy. Implement only the migrations justified by those shapes. Test correctness of result order and limits. Measure query timing and result counts before and after. Document each index's read benefit and write cost.

**Decisions:** Decide whether the single-row status `GET` already has primary-key coverage; decide whether a partial queue index is justified by the seeded distribution; decide whether a patient list exists at this level; decide whether keyset pagination is worth the API change.

**Directional hints:** An index responds to a query, not to a table name. A sequential scan on a tiny local table may be correct. Do not add covering indexes, partitions, or a second index with reversed ordering without evidence. Keep migration changes forward-only and reviewable.

**Relevant blog post or concepts:** `posts/series-2-postgres/04-indexes-query-plans.md`, `posts/series-2-postgres/01-schema-design.md`, and `posts/series-4-product-sse/01-patient-first-api.md`.

**Verification evidence:** A query-shape inventory, migration diff, before/after plan or timing evidence, and a result-set test showing stable ordering and bounded queue reads. Include indexes deliberately not added and why.

**Exit criteria:** Every new index has a real query shape, and the patient and staff read contracts remain unchanged.

### Milestone 6: Inspect query plans and connection-pool behavior

**Objective:** Prove that query and pool changes address measured work rather than assumptions.

**What to implement:** Inspect representative PostgreSQL data and statistics. Decide which plans need `EXPLAIN ANALYZE` with buffers and which timings need end-to-end measurement. Implement plan-driven query or index changes, remove N+1 reads, and keep transactions free of broker/network waits. Test migrations and queries against the same PostgreSQL version used locally. Measure estimated versus actual rows, scan/sort behavior, planning/execution time, pool wait, active connections, transaction hold time, and error rates. Document what the local plan does and does not prove.

**Decisions:** Decide whether a slow result is a query, index, statistics, connection hold, or application mapping issue; decide whether pool size should remain at the repository default; decide whether queue reads need keyset pagination.

**Directional hints:** Pool exhaustion is often a hold-time problem, not a pool-size problem. Run statistics maintenance after seeding before judging a plan. Use a fresh database read to observe committed results and avoid benchmarking a fake repository.

**Relevant blog post or concepts:** `posts/series-2-postgres/04-indexes-query-plans.md`, `posts/series-2-postgres/05-testing-postgresql.md`, and the pooling discussion in `posts/series-2-postgres/02-transactions-isolation.md`.

**Verification evidence:** Captured plan summaries, pool metrics or logs, a query-count comparison for any N+1 fix, and a statement of whether the plan improvement changed correctness, latency, or only explainability.

**Exit criteria:** The system has measured query and pool behavior, no external call is hidden inside the transaction, and no tuning is justified only by intuition.

### Milestone 7: Run concurrency and load tests against the target

**Objective:** Evaluate the optimized implementation under the local target workload and prove the core invariants survive.

**What to implement:** Inspect the final request path and seed strategy. Decide a reproducible run with warm-up, a defined duration, concurrent clients, unique and repeated submission keys, inventory contention, and workflow advancement. Implement or configure the load and concurrency tests against real PostgreSQL and the actual service. Test the last-unit race, double transition, repeated submission, all-or-nothing inventory, and the full happy path during load. Measure achieved submissions per second, successful and conflicting outcomes, error rate, inventory minimum, duplicate record/effect counts, database latency, pool wait, and queue delay. Document machine and version context.

**Decisions:** Decide how to isolate test data between runs; decide whether the target is measured through HTTP, the service boundary, or both; decide how to classify expected inventory conflicts; decide which result invalidates the target.

**Directional hints:** A ten-per-second result with a mocked database is not evidence for this exercise. Run enough repetitions to expose a race and report variance. Keep the target local and honest; do not infer production scaling from a laptop or a single-node Compose broker.

**Relevant blog post or concepts:** `posts/series-2-postgres/05-testing-postgresql.md`, `posts/series-2-postgres/03-inventory-reservation.md`, `posts/series-2-postgres/06-showcase-concurrent-persistence.md`, and `posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md`.

**Verification evidence:** A load report, real PostgreSQL concurrency test output, committed-state assertions, invariant summary, and a clear pass/partial/fail statement for the approximate 10 submissions-per-second local target.

**Exit criteria:** The target run has no negative inventory or unsafe duplicate database effects in its stated scope, and every limitation of the load test is recorded.

### Milestone 8: Document remaining problems requiring architectural changes in Exercise 3

**Objective:** Finish with a precise handoff instead of allowing persistence optimization to masquerade as messaging reliability.

**What to implement:** Inspect all Foundation limitations and the Optimization evidence. Decide which problems are solved by conditional writes and which require a new boundary. Implement the final proof ledger and handoff record. Test that Foundation API behavior and the optimized evidence still pass. Measure nothing new unless a claim is ambiguous. Document the remaining direct publish crash window, lack of publisher confirms, lack of bounded broker retry and dead letters, lack of consumer inbox/idempotency, ordering limits, observability gaps, and absence of SSE.

**Decisions:** Decide whether a duplicate broker delivery is safe only because a current-state predicate rejects it, or whether an external side effect remains unsafe; decide which event first needs an outbox; decide whether a status projection is needed for SSE; decide which operational metric is missing.

**Directional hints:** Keep the handoff risk-ranked. Exercise 3 should start with the transactional outbox, not with a frontend or a cache. State that a database race result does not prove a message was routed or processed.

**Relevant blog post or concepts:** `posts/series-3-rabbitmq/02-publisher-confirms-outbox.md`, `posts/series-3-rabbitmq/05-idempotency-ordering.md`, `posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md`, and `posts/series-4-product-sse/02-sse-correctness.md`.

**Verification evidence:** A guarantee matrix with columns for mechanism, test, proven behavior, unproven behavior, and next exercise. Include a preserved Foundation happy-path result and the final local target report.

**Exit criteria:** The optimized implementation is recognizably the Foundation product with stronger PostgreSQL behavior, and Exercise 3 has a concrete reliability and SSE starting point.

## Step-by-Step Exercise Guide

### Step 1: Reproduce and measure Foundation bottlenecks

**Objective:** Establish a baseline that makes both performance and correctness regressions visible.

**What to implement:** Inspect the Foundation service path, repository operations, migrations, queue reads, transaction annotations, and direct publish timing. Decide a local workload, seed size, warm-up, sample count, and data-isolation method. Implement only the fixtures or measurement seams needed to reproduce the read-check-write race, double transition, repeated submission behavior, or slow query. Test the happy path and the selected races with real PostgreSQL. Measure request, database, queue-read, pool-wait, and error timings. Document the environment and the limits of each measurement.

**Decisions:** Decide whether the first bottleneck is correctness or latency; decide which observations need HTTP and which can use a real repository; decide how expected conflicts are separated from defects; decide what an unsafe duplicate effect means for this product.

**Directional hints:** Reproduce before optimizing. A sequential call repeated twice is not a concurrency test. Keep the original RabbitMQ publish path in the baseline so later database improvements are not mistaken for reliable messaging.

**Relevant blog post or concepts:** `posts/series-2-postgres/05-testing-postgresql.md`, `posts/series-2-postgres/06-showcase-concurrent-persistence.md`, and `posts/series-2-postgres/04-indexes-query-plans.md`.

**Verification evidence:** A baseline report, a real PostgreSQL race or query observation, and a list of behavior assertions that must remain unchanged after optimization.

**Exit criteria:** The target workload and at least one reproduced weakness are recorded before production changes begin.

### Step 2: Make state transitions conditional and race-safe

**Objective:** Move the state machine's concurrency decision into PostgreSQL while retaining the same API outcomes.

**What to implement:** Inspect every status write and its related history or reservation write. Decide the expected current state for each command. Implement conditional state updates, affected-row or returned-row mapping, and transactionally related facts only after a transition wins. Test concurrent approvals and worker transitions with real connections. Measure winner/loser rates and lock wait. Document why the result protects shared database state but is not an inbox.

**Decisions:** Decide how a zero-row result is classified as missing versus conflict; decide whether a follow-up read is used only for error wording; decide how history sequence numbers are allocated; decide how a repeated command is represented to HTTP and worker callers.

**Directional hints:** Let the conditional write, not a prior Kotlin read, decide the race. Under `READ COMMITTED`, PostgreSQL can wait and re-evaluate the predicate. Do not append an event or history record after a transition that did not affect a row.

**Relevant blog post or concepts:** `posts/series-2-postgres/02-transactions-isolation.md`, `posts/series-1-kotlin/03-state-machines-with-sealed-types.md`, and `posts/series-2-postgres/06-showcase-concurrent-persistence.md`.

**Verification evidence:** Two concurrent commands produce one committed transition and one deliberate conflict, with one history record and one modeled downstream database effect.

**Exit criteria:** Every ordinary state write includes its expected previous state and handles a losing race explicitly.

### Step 3: Make inventory reservation atomic

**Objective:** Protect the pharmacy's most important shared invariant: accepted reservations never exceed available stock.

**What to implement:** Inspect the Foundation inventory path, reservation table, rejection release, fulfillment transition, and repeated submission behavior. Decide the atomic single-line operation, all-or-nothing multi-line rule, stable medication order, and use of row locks where a single conditional update is insufficient. Implement the database-level reservation outcome, reservation lifecycle, and deliberate error mapping. Test last-unit races, N-racer contention, insufficient stock, partial multi-line rollback, repeated submission, duplicate release, and fulfillment consumption. Measure final quantity, reservations, conflicts, transaction time, deadlock count, and retry count. Document the difference between reservation and verification.

**Decisions:** Decide whether a zero affected-row inventory update means insufficient stock or unknown medication after error classification; decide when stock is claimed; decide which PostgreSQL deadlock conditions can be retried; decide how the database constraint acts as a backstop.

**Directional hints:** Compute the new quantity from the current row and include the availability predicate in the write. Stable order matters when a prescription touches several inventory rows. A reservation consumed later is a status change, not another decrement.

**Relevant blog post or concepts:** `posts/series-2-postgres/03-inventory-reservation.md`, `posts/series-2-postgres/02-transactions-isolation.md`, and `posts/series-2-postgres/06-showcase-concurrent-persistence.md`.

**Verification evidence:** Real PostgreSQL assertions show no negative stock, no over-claiming, no partial reservation, one release, and no second fulfillment decrement across repeated and concurrent attempts.

**Exit criteria:** The inventory invariant is protected by a database operation and proven by a real race test, not only by a Kotlin branch.

### Step 4: Review transaction boundaries and lock behavior

**Objective:** Ensure database facts commit together without holding locks or pool connections across external work.

**What to implement:** Inspect Spring proxy entry, transaction annotations, connection usage, repository calls, lock order, and any RabbitMQ or HTTP call inside a transaction. Decide the application command boundary and conditional-update versus row-lock versus optimistic approach for each multi-step decision. Implement shorter transactions, consistent row order, and bounded database retry only where safe. Test rollback of state/history/inventory facts and a controlled lock or deadlock case. Measure transaction hold time, lock wait, pool wait, and retry outcomes. Document the isolation assumptions.

**Decisions:** Decide which database exception is safe to retry as a whole command; decide how retry exhaustion is exposed; decide how to verify that the transaction was entered through a Spring-managed proxy; decide which locks are held and in what order.

**Directional hints:** `READ COMMITTED` plus precise predicates is not the same as serializability. Never call RabbitMQ while holding inventory locks. Once PostgreSQL marks a transaction failed, do not continue issuing statements in it.

**Relevant blog post or concepts:** `posts/series-2-postgres/02-transactions-isolation.md`, `posts/series-2-postgres/03-inventory-reservation.md`, and `posts/series-2-postgres/05-testing-postgresql.md`.

**Verification evidence:** A transaction diagram, fresh-connection committed-state assertions, lock/deadlock evidence, and a written distinction between database retry and broker retry.

**Exit criteria:** Related facts share an intentional commit boundary, all inventory paths follow one lock order, and no external call extends a database transaction.

### Step 5: Add indexes based on actual query shapes

**Objective:** Reduce read work for patient and staff workflows without adding unexplained write cost.

**What to implement:** Inspect the actual status, patient-list, history, approval-queue, packaging-queue, and queue-facing repository reads. Decide the predicate, equality columns, ordering columns, partial conditions, and pagination contract for each. Implement only justified migration changes. Test result order, limits, and cursor behavior if used. Measure before/after timings and plan structure. Document the index decision and the query it serves.

**Decisions:** Decide whether the primary key already serves the status `GET`; decide whether a partial queue index is worthwhile for the local distribution; decide whether keyset pagination improves queue correctness enough to change the contract; decide which tempting indexes are deliberately omitted.

**Directional hints:** Write the query before the index. A sequential scan on a small table may be the right plan. Do not optimize a hypothetical SSE read in Exercise 2 or add covering indexes without evidence.

**Relevant blog post or concepts:** `posts/series-2-postgres/04-indexes-query-plans.md`, `posts/series-2-postgres/01-schema-design.md`, and `posts/series-4-product-sse/01-patient-first-api.md`.

**Verification evidence:** A query-shape inventory, migration review, plan/timing comparison, stable queue-order test, and omitted-index rationale.

**Exit criteria:** Each index can be defended by a real query shape and the Foundation API remains unchanged.

### Step 6: Inspect query plans and connection-pool behavior

**Objective:** Validate that the optimized reads and transaction scope behave as intended on real PostgreSQL.

**What to implement:** Inspect representative seeded data, statistics, repository query counts, transaction duration, Hikari or repository pool signals, and external-call placement. Decide which plans require `EXPLAIN ANALYZE` with buffers and which measurements need the full service. Implement plan-driven query, mapping, or index changes and remove any N+1 pattern found. Test with the same database version used by Docker Compose. Measure estimated versus actual rows, scans, sorts, buffers, execution time, pool wait, active connections, and transaction hold. Document what is improved and what is not proven.

**Decisions:** Decide whether the issue is plan choice, stale statistics, query count, connection hold, or pool sizing; decide whether the default pool is adequate; decide whether a pagination cursor is worth its API complexity.

**Directional hints:** Pool size is not throughput. Diagnose what holds connections first. Analyze seeded data before judging estimates. Keep the status `GET` a direct authoritative read rather than hiding it behind a speculative cache.

**Relevant blog post or concepts:** `posts/series-2-postgres/04-indexes-query-plans.md`, `posts/series-2-postgres/05-testing-postgresql.md`, and `posts/series-2-postgres/02-transactions-isolation.md`.

**Verification evidence:** Plan summaries, pool observations, query-count evidence, and a fresh-connection assertion that the optimized read returns the same committed patient state.

**Exit criteria:** Query and pool changes are evidence-driven, and external messaging remains outside the database transaction.

### Step 7: Run concurrency and load tests against the target

**Objective:** Test the optimized service under the local target rather than a synthetic repository-only benchmark.

**What to implement:** Inspect the final API path, seed data, and test cleanup. Decide warm-up, duration, concurrency, request mix, expected conflicts, repeated submission keys, inventory contention, and workflow completion criteria. Implement the load and concurrency run through the actual service with real PostgreSQL and the preserved basic RabbitMQ path. Test state races, inventory races, repeated commands, and the full patient journey during the run. Measure achieved submissions per second, successful/conflicting/error counts, p50/p95/p99 where useful, inventory minimum, duplicate records/effects, pool wait, and queue delay. Document machine, versions, run count, and variance.

**Decisions:** Decide what makes the target a pass or partial pass; decide how to isolate data between repetitions; decide how expected stock conflicts are reported; decide whether a duplicate broker test is a database guard observation or a reliability claim.

**Directional hints:** The target is approximately 10 workflow submissions per second, not a promise of exactly 10. Do not let a mock database or a bypassed queue count. A passing rate with a negative inventory or unsafe duplicate effect is a failed correctness run.

**Relevant blog post or concepts:** `posts/series-2-postgres/05-testing-postgresql.md`, `posts/series-2-postgres/03-inventory-reservation.md`, `posts/series-2-postgres/06-showcase-concurrent-persistence.md`, and `posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md`.

**Verification evidence:** A reproducible load report, real PostgreSQL race output, committed-state assertions, invariant summary, and a clear local target result.

**Exit criteria:** The optimized path meets or honestly reports its approximate local target, with no negative inventory or unsafe duplicate database effect in the declared scope.

### Step 8: Document remaining problems requiring architectural changes in Exercise 3

**Objective:** Hand off the remaining reliability and notification risks without overclaiming what PostgreSQL optimization solved.

**What to implement:** Inspect the final Foundation comparison, database evidence, RabbitMQ behavior, and patient requirements. Decide which problems need an outbox, relay, publisher confirms, manual-ack policy, retries, DLQ, inbox, ordered projection, and SSE. Implement a guarantee matrix and risk-ranked handoff. Test that all preserved behavior still passes. Measure no new capacity unless a prior result needs clarification. Document direct publish failure, message duplicate risk, retry/DLQ absence, observability gaps, and missing SSE replay/isolation.

**Decisions:** Decide which duplicate broker scenarios are safe only at the conditional state boundary; decide which external side effects remain unsafe; decide which event should be the first outbox candidate; decide why SSE must wait for a durable status fact path.

**Directional hints:** Do not add an inbox or outbox just to make the handoff sound complete. The point is to distinguish a persistence fix from an architectural reliability fix. Rank the outbox before realtime notifications because a notification path built on unreliable facts only makes failure more visible.

**Relevant blog post or concepts:** `posts/series-3-rabbitmq/02-publisher-confirms-outbox.md`, `posts/series-3-rabbitmq/05-idempotency-ordering.md`, `posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md`, and `posts/series-4-product-sse/02-sse-correctness.md`.

**Verification evidence:** A final guarantee matrix, preserved Foundation happy-path result, local target report, and a written Exercise 3 starting checklist.

**Exit criteria:** Exercise 2 is a recognizable Foundation product with proven PostgreSQL optimization and an honest architectural handoff.

## Required Decisions

- Which Foundation endpoints, states, patient behavior, staff behavior, and local assumptions are preserved exactly.
- Which schema constraints and migrations protect facts shared across requests, and which rules remain application-only.
- How PostgreSQL `READ COMMITTED` affects competing conditional updates and why an affected-row result is the business outcome.
- Which transitions use a conditional update, which require `SELECT FOR UPDATE`, and whether an optimistic version is useful anywhere.
- How atomic inventory reservation computes from current quantity, handles multiple lines, releases once, and avoids a second fulfillment decrement.
- Which global lock order is used, what deadlock or serialization states are safe to retry, and why insufficient inventory is not retried.
- Which indexes match actual patient, staff, history, and queue-facing reads, and why each one is worth its write cost.
- What `EXPLAIN ANALYZE` shows before and after, how statistics are controlled, and what a local plan cannot prove.
- Which pagination strategy is appropriate for the queue and how connection-pool hold time is measured.
- Which tests require real PostgreSQL migrations, connections, threads, constraints, and committed-state reads.
- How the approximate 10 submissions-per-second local target is generated, measured, and bounded without becoming a production claim.
- How database race safety differs from message-delivery reliability, command protection differs from duplicate broker delivery, and query optimization differs from architectural redesign.
- Why Exercise 2 still does not implement a transactional outbox, publisher confirms, bounded broker retries, dead letters, an inbox, or SSE.
- Which Kotlin nullability, immutability, sealed outcomes, Spring proxy, blocking-I/O, coroutine, and testing choices keep the optimized code idiomatic rather than mechanically Java-shaped.

## Tests and Evidence

Retain all Foundation evidence, then add evidence that is authoritative for PostgreSQL behavior:

- Fresh-database migration and deterministic seed tests against the PostgreSQL version used by the local stack.
- Constraint tests for invalid quantities, invalid statuses, orphan lines, duplicate lines or submission keys, and invalid reservation states.
- Two real concurrent state commands showing one conditional transition winner and one deliberate losing outcome.
- Last-unit and N-racer inventory tests showing no negative quantity and successful reservations no greater than seeded stock.
- All-or-nothing multi-line reservation and rollback tests.
- Rejection release and fulfillment-consumption tests showing no double restoration or decrement.
- Lock-order, deadlock, or safe database-retry evidence where the chosen design needs it.
- Query-shape records and `EXPLAIN ANALYZE` evidence against representative real PostgreSQL data.
- Queue-facing pagination and ordering evidence, plus a query-count check for any N+1 change.
- Connection-pool wait/hold observations and evidence that transactions do not contain RabbitMQ or HTTP calls.
- A local load report targeting approximately 10 prescription workflow submissions per second, with machine, versions, duration, concurrency, warm-up, sample size, rate, error categories, inventory minimum, duplicate-effect count, and percentile timings.
- An explicit proof ledger separating PostgreSQL race safety from RabbitMQ publisher, routing, redelivery, retry, dead-letter, and consumer-processing behavior.

Use real RabbitMQ only to preserve the Foundation happy-path route and to show that the optimized database work did not bypass messaging. Do not label a mocked publisher, a conditional database update, or a passing load run as proof of publisher confirmation, message routing, at-least-once recovery, bounded retries, dead-lettering, or idempotent consumption. Those claims belong to Exercise 3.

## Bottleneck & Reflection Questions

- What did the Foundation measurement show, and how do you know the change addressed the cause rather than the symptom?
- Under PostgreSQL `READ COMMITTED`, what does the losing conditional update see after the winning transaction commits?
- Why is a service-level read followed by an unconditional save unsafe even inside a transaction?
- What exactly does zero affected rows mean, and when is a follow-up read appropriate only for error classification?
- Why does an atomic inventory update protect the last unit better than a Kotlin check followed by a write?
- What does the non-negative inventory constraint add if the conditional update is correct?
- When is `SELECT FOR UPDATE` clearer than a conditional update, and what lock and deadlock cost does it add?
- Why is a version-column optimistic approach often a poor fit for a hot inventory row?
- How do multiple medication lines create a deadlock, and what does stable ordering change?
- Which database errors are safe to retry, and why is insufficient inventory a valid outcome rather than a transient failure?
- Why do status readers normally not wait behind a PostgreSQL row update in this design?
- Which query shapes justify each index, and why can a sequential scan be correct on a small table?
- What does `EXPLAIN ANALYZE` prove, and what does it not prove about a different dataset or production hardware?
- Why can a pool timeout indicate a long transaction or blocking call rather than a pool that is too small?
- How does the 10-per-second local measurement prove the optimized path, and what production claims must not be inferred from it?
- If a duplicate RabbitMQ message arrives, which database transition guard helps, and what side effect still requires an inbox in Exercise 3?
- Why does a faster query not close the direct database-to-broker publish gap?
- What is the first architectural change Exercise 3 should make, and why does it precede SSE?

## Success Criteria

- The optimized exercise starts from and preserves the Foundation patient and staff behavior.
- Conditional state transitions prevent two concurrent commands from both winning the same transition.
- Affected-row or returned-row results become deliberate domain outcomes, and losing commands do not append duplicate modeled facts.
- Atomic inventory reservation prevents negative stock and over-claiming under the tested concurrent workload.
- Multi-line reservation behavior is all-or-nothing, uses a documented lock order, and has a deadlock/retry decision.
- Migrations and schema constraints express the relevant cross-request invariants.
- Indexes are tied to actual query shapes and validated with real PostgreSQL `EXPLAIN ANALYZE` evidence.
- Queue-facing reads have a deliberate pagination and ordering strategy.
- Connection-pool hold and wait behavior is measured, and external calls are not hidden inside database transactions.
- Real PostgreSQL integration tests, not only mocks or in-memory fakes, prove concurrency and rollback claims.
- The local run targets approximately 10 prescription workflow submissions per second without negative inventory or unsafe duplicate database effects in its stated scope.
- The result does not claim that publisher confirmation, routing, consumer processing, retries, dead letters, or SSE are solved.
- The handoff to Exercise 3 explicitly identifies the direct publish crash window, broker duplicate-delivery gap, operational observability gap, and missing patient notification path.

## Interview Defense Checklist

- State the Foundation behavior you preserved before describing any optimization.
- Walk through two concurrent approvals under `READ COMMITTED` and identify the one affected-row winner.
- Walk through two submissions racing for the last unit and explain why the loser gets a domain outcome rather than a negative quantity.
- Explain why the transaction includes related inventory, history, and current-state facts but excludes RabbitMQ publication.
- Compare conditional updates, row locks, and optimistic versions using the invariant and contention profile.
- Describe the global lock order and the bounded database retry decision for deadlocks.
- Name every index by the query shape that justifies it and interpret the relevant plan evidence.
- Explain why a pool timeout can be a transaction hold-time problem and how you measured it.
- Describe the local 10-per-second target, its test conditions, what it proves, and what it does not prove.
- Distinguish database race safety from message-delivery reliability and query tuning from architectural redesign.
- Explain why a duplicate broker delivery is not automatically safe without an inbox, even if a conditional state update loses the race.
- Explain why Exercise 3 starts with an outbox and confirms before adding bounded retries and SSE.
- Defend the Kotlin choices around nullability, immutable values, sealed outcomes, transaction proxies, blocking I/O, and real concurrency tests.
