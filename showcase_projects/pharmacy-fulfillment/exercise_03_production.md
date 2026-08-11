# Production-Grade Pharmacy Prescription Fulfillment System - Exercise

## Objective

Start explicitly from the optimized Exercise 2 implementation. Preserve its patient and staff behavior, PostgreSQL invariants, real-database evidence, and local target of approximately 10 prescription workflow submissions per second without negative inventory or unsafe duplicate database effects. Add production-grade local reliability and patient-specific realtime notifications without introducing cloud services, managed infrastructure, a full frontend, or an unjustified microservice decomposition.

The production-grade local target is twofold: preserve Exercise 2 behavior and achieve a measured p95 patient status `GET` below 250 ms under a stated local workload. In addition, prove that patient SSE events are ordered, reconnecting clients can replay or catch up with event IDs and `Last-Event-ID`, authorization is enforced, and one patient cannot observe another patient's events. These are local engineering targets and correctness demonstrations, not production capacity claims.

For a two-hour challenge mode, implement one complete outbox-to-worker path with publisher-confirm evidence, manual acknowledgement after commit, an inbox uniqueness test, and document the remaining retry, DLQ, and SSE work. For a five-hour mode, complete the ten milestones with a narrow but real broker and SSE test suite. The broader 30-day, 60-hour preparation period should extend failure injection, recovery rehearsal, measurement, and interview defense rather than add a frontend.

## Starting Point

Use `showcase_projects/pharmacy-fulfillment/exercise_02_optimization.md` as the explicit starting point. Before changing the architecture, rerun its Foundation-preservation tests, real PostgreSQL migration and concurrency tests, the inventory race evidence, and the local approximately 10 submissions-per-second report. Record the existing query plans, pool observations, API behavior, and deferred direct-publish limitation.

The expected starting implementation has an authoritative current prescription state, status history, inventory reservations, conditional transitions, atomic inventory handling, justified indexes, real PostgreSQL integration evidence, and the original basic RabbitMQ path. Do not replace the patient API or re-model the product just because reliability introduces new tables and queues. The new work should close known failure windows around that recognizable system.

Read the RabbitMQ sources before designing the topology: `posts/series-3-rabbitmq/01-amqp-topology.md`, `posts/series-3-rabbitmq/02-publisher-confirms-outbox.md`, `posts/series-3-rabbitmq/03-ack-prefetch-concurrency.md`, `posts/series-3-rabbitmq/04-retries-dead-letters.md`, `posts/series-3-rabbitmq/05-idempotency-ordering.md`, `posts/series-3-rabbitmq/06-operational-testing.md`, and `posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md`. Read the separate patient notification sources `posts/series-4-product-sse/01-patient-first-api.md`, `posts/series-4-product-sse/02-sse-correctness.md`, `posts/series-4-product-sse/03-testing-realtime.md`, `posts/series-4-product-sse/04-time-box-scoping.md`, and `posts/series-4-product-sse/05-showcase-patient-notification.md` before treating SSE as a feature.

## Background & Motivation

The optimized system can make a PostgreSQL state transition safe and still lose the next workflow message if it commits the database change and then crashes before publishing. It can publish a message and still lose the business effect if a worker acknowledges before its transaction commits. It can acknowledge after commit and then receive a duplicate when the channel dies in between. Production readiness is the discipline of naming each boundary and choosing which outcome is recoverable.

RabbitMQ and PostgreSQL do not share a transaction in this local architecture. The honest design is at-least-once delivery with durable database coordination and idempotent business effects. A publisher confirmation says the broker accepted responsibility for a publish; it does not prove routing, consumer processing, or business completion. A manual acknowledgement says a consumer has finished its delivery; it does not prove another subscriber saw the fact. The exercises must never claim exactly-once delivery.

SSE is a separate patient notification path. It is not a competing consumer of a packaging or notification work queue. RabbitMQ facts feed a durable, ordered status projection. Authorized SSE connections read and replay that projection, while the REST status `GET` remains the correctness baseline. This separation protects patient isolation, reconnect behavior, and the ability to cut SSE without losing the product.

## System Specification

### Actors and product behavior

The patient submits a prescription and reads its current state through the existing patient API. The pharmacist approves or rejects through the existing staff commands. Packaging and fulfillment workers consume work messages from dedicated competing-consumer queues. A relay moves committed integration events from PostgreSQL to RabbitMQ. A status projection consumer receives facts separately from work, applies them in per-prescription order, and stores patient-visible history. An SSE layer serves authorized patient connections from that projection. An operator or developer inspects metrics, logs, outbox rows, queue state, retry history, and dead letters through local interfaces and documented procedures.

Keep one understandable local deployable application with logical worker, relay, projection, and notification roles unless the repository already has a concrete process boundary. Do not introduce microservices merely to make the diagram larger. A separate worker process is justified only if it clarifies failure isolation or lets a real broker test exercise the role; document the tradeoff.

### Patient and staff contracts

Preserve the Exercise 2 patient contracts and error behavior:

| Consumer | Contract | Production-grade expectation |
| --- | --- | --- |
| Patient | `POST /prescriptions` | Submission retry behavior remains deliberate and inventory/state invariants remain unchanged. |
| Patient | `GET /prescriptions/{id}` | Reads committed current state for the authorized owner and remains the correctness baseline. Its local p95 target is below 250 ms under the stated test workload. |
| Pharmacist | Queue read, approval, and rejection actions | Conditional transitions and outbox records commit together; conflicts remain explicit. |
| Packager and fulfillment worker | RabbitMQ work messages | Work is acknowledged only after durable business effects commit. |
| Patient notification client | `GET /prescriptions/{id}/events` | Returns an authorized SSE stream with durable event IDs, ordered history, replay from `Last-Event-ID`, and no cross-patient data. |

The SSE endpoint is an enhancement, not a replacement for the status `GET`. If the stream is unavailable, delayed, or disconnected, the patient can still retrieve the committed status. No full frontend is required; use a real HTTP/SSE test client and a small manual demonstration if useful.

### Prescription states and domain outcomes

Preserve the Exercise 2 state vocabulary: `SUBMITTED`, `AWAITING_APPROVAL`, `APPROVED`, `PACKAGING`, `READY_FOR_COLLECTION`, `FULFILLED`, and `REJECTED`, with only the documented legal transitions. Every committed transition produces a patient-visible history fact with a per-prescription monotonic sequence where the notification path needs it.

In addition to Foundation and Optimization outcomes, model reliability decisions explicitly: already applied event, retryable processing failure, permanent or malformed message, exhausted retry budget, ordering gap, unauthorized stream, stale replay boundary, and unavailable dependency. Use sealed Kotlin outcomes for expected business and processing alternatives. Keep infrastructure failures distinct so a listener can decide whether to retry, dead-letter, or acknowledge a known duplicate.

### Inventory responsibilities

Preserve the Exercise 2 inventory contract exactly: atomic reservation, no negative quantity, all-or-nothing multi-line behavior, stable lock ordering, one release on rejection, and no second decrement on fulfillment. A RabbitMQ duplicate must not cause a second inventory change or unsafe state transition. The inventory database invariant remains the authority; message identity and inbox records protect consumer-side effects around it.

Reservation expiry, manual release, and operational correction are separate decisions. If they are not implemented within the time box, document the stale-reservation risk and the recovery procedure rather than silently releasing stock. Do not add medication batches, warehouses, or insurance processing without a concrete requirement and tradeoff.

### Persistence responsibilities

Retain the optimized current-state tables, constraints, migrations, indexes, history, and reservation records. Add an outbox record for every integration event that must be published, with a stable event identifier, aggregate or prescription identifier, event type, routing intent, sequence information where relevant, availability time, publication marker, attempt metadata, and last failure information as justified by operations. The state change, status history, and outbox row must commit or roll back together.

Add an inbox or equivalent consumer record with uniqueness scoped to the logical consumer and stable event identifier. The inbox claim and the business effect must share one transaction. Keep inbox retention longer than the maximum redelivery, retry, replay, and operator inspection window. Add a status projection or event-history read model that stores patient ownership, prescription identity, status, and per-prescription sequence in an append-only form suitable for replay.

The current prescription state remains authoritative for synchronous correctness. The projection supports ordered facts and SSE replay; it must not silently become a conflicting source of truth. A projection lag is observable and the patient can fall back to `GET`.

### RabbitMQ topology and delivery semantics

Use a topology that separates work commands from facts:

| Concern | Local topology and role | Required semantics |
| --- | --- | --- |
| Workflow work | A durable direct work exchange such as `pharmacy.work`, with separate packaging and fulfillment queues | Competing consumers may run independently; work messages are persistent and manually acknowledged. |
| Patient/status facts | A durable topic exchange such as `pharmacy.events`, with a durable notification subscriber queue bound to prescription facts | Facts are delivered to a logical subscriber, projected in order, and are not consumed by individual SSE connections. |
| Transient retry | A durable retry exchange or dead-letter route with bounded delay queues for each work class as needed | Retryable failures wait outside the worker, use broker evidence such as `x-death`, and return to work only within a stated budget. |
| Permanent failure | A durable dead-letter exchange and quarantine queue for poison or exhausted messages | Payload, stable event identity, correlation information, and attempt history remain inspectable and replayable by an operator. |

Use durable exchanges and queues, persistent messages, explicit routing keys, and a pinned local RabbitMQ version. Publisher confirms prove broker acceptance only. Mandatory publishing or returned-message handling must make unroutable messages visible. A routed message is not a processed message. A consumer acknowledgement is not business completion unless the consumer has already committed its durable effect.

Work consumers use manual acknowledgements, deliberate prefetch, and a documented concurrency level. A channel or consumer failure reclaims unacknowledged messages, so redelivery is expected. Packaging and fulfillment may use competing consumers because independent work can be parallelized; the ordered status projection must use one effective consumer per ordered stream, such as a single-active-consumer queue, or a documented sharding strategy. Do not claim that RabbitMQ restores application order after retry or redelivery; sequence numbers and gap policy do that.

At-least-once delivery is the system-level contract. The outbox relay can publish a duplicate if it crashes after broker confirmation but before marking the row. A consumer can receive a duplicate after committing and before acknowledging. Retry and DLQ replay can create more deliveries. Stable event IDs, inbox uniqueness, conditional state transitions, and per-prescription sequence checks make those deliveries safe for the modeled effects. None of this is exactly-once delivery.

### SSE notification path

The status fact consumer reads from the RabbitMQ notification queue and writes committed projection rows. The SSE layer reads the projection and broadcasts only to authorized connections. It never consumes the business work queue and never makes each connection a RabbitMQ consumer.

Use `GET /prescriptions/{id}/events` as the patient stream. Each status event carries an event ID derived from a durable per-prescription sequence, not a new identifier generated only when a connection sends. On a fresh connection, provide the authorized history or snapshot in order. On reconnect, parse `Last-Event-ID` and replay only the missing tail, then perform a catch-up boundary check so events arriving during replay are neither skipped nor repeated. Enforce ownership before any replay and again in the data access path. Key live connections by patient identity and clean them up on completion, timeout, or error so one patient's event cannot be sent to another patient's connections.

The local identity stand-in may use a documented header, cookie, or short-lived test token, but the server must enforce ownership. If a browser `EventSource` cannot send the chosen custom header, document the local test-client choice and the production identity transport tradeoff. Do not weaken authorization to make a demo easier.

### Failure boundaries and observability expectations

For each boundary, document the durable result and recovery action: transaction rollback before commit, pending outbox after a process crash, duplicate relay publication after uncertain marking, unroutable return, worker crash before acknowledgement, transient retry, permanent dead letter, duplicate inbox claim, ordering gap, projection lag, SSE disconnect, server restart, unauthorized replay, and cross-patient connection attempts.

Use structured logs with a correlation identifier, prescription identifier, patient identifier where safe, event identifier, consumer or worker role, transition, attempt/retry information, outcome, and error classification. Do not log unnecessary medication or patient-sensitive details. Track outbox age and backlog, confirm/nack/return counts, queue depth and unacknowledged messages, redeliveries, retry and DLQ depth, inbox duplicate claims, projection lag and gaps, SSE connections/replays/authorization failures, database deadlocks and pool wait, and status `GET` latency percentiles. Define recovery procedures for stuck outbox rows, dead-letter inspection/replay, projection gaps, consumer restart, and stale reservations.

### Kotlin guidance for a Java engineer

Use immutable event envelopes, command values, response values, and read-only collections. Preserve stable event identity and sequence as explicit domain data rather than mutable listener state. Use nullable values only for optional or absent data, sealed outcomes for retryable/permanent/already-applied/gap decisions, and exceptions for unexpected infrastructure or programming failures. Keep state transitions behind named operations and align the in-memory model with the persisted status without pretending the type system coordinates multiple processes.

Keep the transactional outbox write, inbox claim, projection update, and business effect inside clear Spring-managed transaction boundaries. Review Spring proxy behavior: self-invocation does not pass through the proxy, listener callbacks may run on different threads, and an acknowledgement must happen on the delivery's channel after the transaction has committed. Do not publish from the transaction merely because the code is shorter.

RabbitMQ listener threads and PostgreSQL clients are commonly blocking. Prefer the repository's established blocking model with explicit listener concurrency and a measured pool. If coroutines are used, keep blocking database or broker calls on an appropriate dispatcher, preserve transaction context, and define cancellation behavior around acknowledgement. Do not suspend a listener and then acknowledge on an invalid or closed channel. Use small normal classes for services and workers, data classes for values, exhaustive `when` for closed outcomes, and behavior-focused Kotlin tests. Avoid mechanically translating Java beans, mutable event objects, broad catch-and-ack handlers, and clever scope-function chains that hide whether a transaction committed.

## Milestone Plan

Complete the milestones in order. The first six close or expose message failure windows; SSE starts only after the reliability and status baseline can be trusted.

| Order | Milestone | Depends on | Must-have work | Optional stretch |
| --- | --- | --- | --- | --- |
| 1 | Implement a transactional outbox | Exercise 2 behavior and transaction evidence | Commit state, history, and event intent together | Event schema/version metadata and retention policy |
| 2 | Implement a relay with publisher confirms and crash-window handling | Milestone 1 and durable topology | Claim pending rows, publish with confirms, surface returns, mark after confirmation, tolerate duplicate publication | Multiple relay workers and stuck-row recovery |
| 3 | Configure durable queues, manual acknowledgements, prefetch, and consumer concurrency | Milestone 2 topology | Durable work/fact queues, persistent messages, manual ack after commit, measured in-flight work | Single-active-consumer failover or carefully bounded worker scaling |
| 4 | Add bounded retries and dead-letter handling | Milestone 3 acknowledgement behavior | Failure classification, delayed retry, retry budget, poison quarantine, inspect/replay procedure | Multiple delay tiers or broker-version comparison |
| 5 | Add idempotent consumers and ordering rules | Milestones 3-4 | Inbox uniqueness, same-transaction effect, duplicate ack, per-prescription sequence/gap policy | Sharded ordered projections and retention analysis |
| 6 | Add structured logs, metrics, correlation IDs, and recovery procedures | All reliability paths so far | Operational signals, failure classification, local runbooks, no sensitive payload logging | Management API probes and recovery drills |
| 7 | Add SSE as a separate patient notification path | Ordered status projection from Milestone 5 | Projection-backed stream, connection lifecycle, GET fallback, no RabbitMQ connection consumer | Heartbeats and proxy buffering notes |
| 8 | Add event IDs, replay, ordering, authorization, and isolation | Milestone 7 stream | Durable sequence IDs, `Last-Event-ID` catch-up, ownership checks, patient-keyed fan-out | Server-restart composition evidence and token transport discussion |
| 9 | Add failure, reconnect, and operational tests | All previous milestones | Real PostgreSQL/RabbitMQ/SSE tests for crash windows, retries, DLQ, replay, order, auth, isolation, and p95 GET | Full broker restart or network interruption drill |
| 10 | Produce the final architecture and tradeoff record | Complete evidence | Architecture diagram, guarantee ledger, crash matrix, local measurements, omissions, and walkthrough | A short operational review and interviewer challenge session |

The two-hour stopping point is after Milestone 5 with one real broker failure test and a written SSE handoff. The five-hour version should implement Milestones 1-9 at narrow scope and finish the architecture record; it should cut dashboards and frontend work first. During the broader preparation period, use the cited posts to expand one failure window at a time, run tests with pinned local dependencies, rehearse recovery, and practice stating the difference between delivery, routing, processing, and business completion.

### Milestone 1: Implement a transactional outbox

**Objective:** Remove the Foundation/Optimization database-to-broker dual-write gap without changing the patient product behavior.

**What to implement:** Inspect every Exercise 2 transition that produces work or status facts. Decide which event is the first reliability target, what stable event identity and sequence it carries, and which state/history facts belong to the same transaction. Implement outbox persistence and transaction participation. Test commit and rollback outcomes, measure outbox insertion and publish-lag timestamps, and document that no RabbitMQ call occurs inside the business transaction.

**Decisions:** Decide whether events carry a full fact or a durable reference; decide how event type, routing intent, schema version, and aggregate identity are represented; decide how long unpublished and published records remain available; decide how a failed outbox insert affects the state transition.

**Directional hints:** The database should become the durable handoff point. Generate the event identity once and preserve it through relay, message, inbox, retry, and replay. An outbox removes the state-to-event loss gap but does not make publication or delivery exactly once.

**Relevant blog post or concepts:** `posts/series-2-postgres/01-schema-design.md`, `posts/series-2-postgres/02-transactions-isolation.md`, `posts/series-2-postgres/06-showcase-concurrent-persistence.md`, and `posts/series-3-rabbitmq/02-publisher-confirms-outbox.md`.

**Verification evidence:** Real PostgreSQL tests show that a committed state transition has its history and outbox record, while an induced failure rolls all of them back. A review record shows the event identity and sequence remain stable.

**Exit criteria:** No supported workflow transition depends on a post-commit direct publish to create its only durable handoff.

### Milestone 2: Implement a relay with publisher confirms and crash-window handling

**Objective:** Move committed outbox rows to RabbitMQ with observable broker acceptance and a no-loss preference under relay uncertainty.

**What to implement:** Inspect outbox row lifecycle and local broker topology. Decide how pending rows are claimed by one or more relay workers, how confirm correlation maps to event identity, how unroutable returns are handled, and when publication is marked. Implement a polling relay with a short, explainable claim batch and confirm-aware marking. Test broker unavailable, negative or timed-out confirmation, unroutable publication, concurrent relay claims, and a simulated crash after confirmation but before row marking. Measure outbox age, attempts, confirm latency, and duplicate publications. Document the unavoidable duplicate window.

**Decisions:** Decide whether to mark published or retain a separate attempt/receipt record; decide what happens to a row after a return or negative confirmation; decide how a relay restart finds an uncertain row; decide how retention avoids deleting evidence too early.

**Directional hints:** Mark only after confirmation and keep the row available until the mark is durable. If the relay crashes after broker acceptance but before marking, republishing the same stable event ID is the safe direction; the consumer must tolerate the duplicate. A publisher confirm proves broker acceptance, not routing, processing, or business completion.

**Relevant blog post or concepts:** `posts/series-3-rabbitmq/02-publisher-confirms-outbox.md`, `posts/series-3-rabbitmq/01-amqp-topology.md`, and `posts/series-3-rabbitmq/06-operational-testing.md`.

**Verification evidence:** Real RabbitMQ evidence for a confirmed routed message, a visible unroutable outcome, concurrent relay claim behavior, and two copies of one stable event identity after the simulated crash window. The duplicate must not be described as a publish failure that was magically prevented.

**Exit criteria:** Every committed event remains recoverable from PostgreSQL until the relay records broker acceptance, and uncertainty produces an observable duplicate-safe retry rather than silent loss.

### Milestone 3: Configure durable queues, manual acknowledgements, prefetch, and consumer concurrency

**Objective:** Ensure a worker crash does not silently discard work and make the amount of in-flight uncertainty measurable.

**What to implement:** Inspect the Foundation topology and each message's work or fact classification. Decide durable exchange/queue/message settings, work queue concurrency, prefetch, projection consumer concurrency, and acknowledgement timing. Implement manual acknowledgement for work consumers, durable local declarations, persistent messages, and post-commit acknowledgement. Test consumer failure before commit, after commit but before acknowledgement, and clean consumer restart. Measure unacknowledged count, redelivery count, processing time, prefetch capacity, and effective in-flight work. Document why work and facts use different consumer shapes.

**Decisions:** Decide which queues compete and which queue must have one effective ordered consumer; decide a prefetch/concurrency pair from the workload; decide how the worker distinguishes business completion from delivery; decide how an already-applied duplicate is acknowledged.

**Directional hints:** Commit the durable effect first and acknowledge second. Auto-ack and an exception-swallowing auto mode can lose work. Prefetch is messages per consumer; concurrency is consumers per queue; the product of the two bounds uncertain work. A redelivery flag is not a retry count.

**Relevant blog post or concepts:** `posts/series-3-rabbitmq/03-ack-prefetch-concurrency.md`, `posts/series-3-rabbitmq/01-amqp-topology.md`, and `posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md`.

**Verification evidence:** A real broker test pauses or fails a worker after the database effect and before acknowledgement, observes redelivery, and shows the eventual database result. Topology evidence shows durable queues and a bounded in-flight configuration.

**Exit criteria:** A worker failure favors no lost business work, and the remaining duplicate effect is explicitly assigned to the inbox/idempotency milestone.

### Milestone 4: Add bounded retries and dead-letter handling

**Objective:** Replace unbounded immediate requeue with a bounded, observable policy for transient failures and poison messages.

**What to implement:** Inspect possible worker failures and classify them as retryable or permanent. Decide retry delay, maximum attempts, retry queue ownership, terminal dead-letter route, payload/header retention, and replay permission. Implement a durable retry parking path with no eager consumer, broker-supported attempt evidence such as `x-death`, and a quarantine queue for permanent or exhausted messages. Test transient failure through retry and recovery, permanent malformed input, retry exhaustion, poison isolation, and dead-letter inspection/replay. Measure delivery attempts, delay, retry depth, DLQ depth, and time-to-quarantine. Document the operator procedure.

**Decisions:** Decide which database lock/dependency failures are retryable, which malformed or missing-prescription cases are permanent, whether retry delay is fixed or graduated, and how replay preserves event identity and attempt history.

**Directional hints:** Requeueing immediately is not a retry policy. Put delay outside the worker so a sleeping consumer does not hold prefetch capacity. The retry budget must survive a worker restart. A dead-letter queue is observable quarantine, not proof of durability or success.

**Relevant blog post or concepts:** `posts/series-3-rabbitmq/04-retries-dead-letters.md`, `posts/series-3-rabbitmq/06-operational-testing.md`, and the retry section of `posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md`.

**Verification evidence:** Real broker tests show a retryable message returning within a bounded budget, an exhausted message reaching the correct DLQ with payload and attempt history, a permanent message skipping retry, and unrelated work continuing. Replay evidence preserves the stable event identity and remains idempotency-dependent.

**Exit criteria:** No poison message can loop forever in the work queue, every failure has a classified fate, and an operator can inspect and deliberately replay a quarantined message.

### Milestone 5: Add idempotent consumers and ordering rules

**Objective:** Make duplicate delivery safe for modeled effects and prevent a delayed event from moving a patient's status backward or across a gap.

**What to implement:** Inspect every consumer side effect and stable event identity. Decide the logical consumer name, inbox uniqueness scope, transaction boundary, duplicate outcome, event retention, per-prescription sequence allocation, gap response, and projection consumer topology. Implement an inbox claim and business effect in one transaction, acknowledge already-applied events as successful, and apply ordered facts only when they are the expected next sequence. Test concurrent duplicate deliveries, commit-then-ack redelivery, old-event replay, expected sequence, missing sequence, and out-of-order delivery. Measure duplicate claims, gap count, projection lag, and ordered apply latency. Document the precise at-least-once and at-most-once-effect language.

**Decisions:** Decide whether the inbox is shared with a composite consumer key or separated physically; decide how long records are retained; decide whether a gap is retried, quarantined, or operator-recovered; decide why packaging can use competing consumers while the status projection cannot.

**Directional hints:** A check-then-act inbox lookup is racy. Let the uniqueness constraint arbitrate the claim inside the same transaction as the effect. A duplicate that already committed is acknowledged, not retried. RabbitMQ's happy-path queue order is not enough after redelivery or retry; per-prescription sequence is the application rule.

**Relevant blog post or concepts:** `posts/series-3-rabbitmq/05-idempotency-ordering.md`, `posts/series-3-rabbitmq/03-ack-prefetch-concurrency.md`, `posts/series-2-postgres/01-schema-design.md`, and `posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md`.

**Verification evidence:** Real PostgreSQL and RabbitMQ tests show duplicate event delivery creates one modeled effect, a failed effect rolls back its inbox claim, old events do not move the projection backward, and a sequence gap is visible and not applied out of order.

**Exit criteria:** Duplicate delivery is an expected at-least-once event, not an unsafe surprise, and each ordered prescription stream has an explicit gap and failover policy.

### Milestone 6: Add structured logs, metrics, correlation IDs, and recovery procedures

**Objective:** Make failure and recovery diagnosable by a reviewer or operator using local evidence.

**What to implement:** Inspect current logs, queue state, database records, and test failures. Decide the correlation identifier lifecycle from HTTP request through outbox, message, consumer, projection, and SSE; decide metric names and labels without sensitive payloads; decide alert thresholds or local warning conditions; and write recovery procedures. Implement structured logs, counters/timers/gauges, correlation propagation, and operator-facing inspection/replay actions. Test that a failure leaves enough evidence to find the prescription, event, queue, retry attempt, and recovery state. Measure log completeness, metric increments, outbox age, DLQ depth, projection lag, and status p95. Document normal and abnormal examples.

**Decisions:** Decide whether a correlation ID is client-provided or generated at the boundary; decide which identifiers are safe to log; decide which metrics distinguish broker acceptance, routing, processing, and business completion; decide how an operator avoids replaying a successful event with a new identity.

**Directional hints:** Do not rely on one application log line for broker state. Keep the durable row, broker evidence, and business state queryable separately. Alerting on work-queue depth alone can miss poison messages accumulating in a DLQ. Recovery procedures should be bounded and reversible where possible.

**Relevant blog post or concepts:** `posts/series-3-rabbitmq/06-operational-testing.md`, `posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md`, `posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md`, and `posts/series-4-product-sse/03-testing-realtime.md`.

**Verification evidence:** A structured log sample with correlation fields, metric evidence for a successful and failed path, queue/outbox/inbox inspection evidence, and a recovery record for a dead letter or stuck outbox row.

**Exit criteria:** A failure can be followed from request to durable outcome without reading source code or exposing patient-sensitive payloads.

### Milestone 7: Add SSE as a separate patient notification path

**Objective:** Add realtime delivery without turning patient connections into RabbitMQ consumers or creating a second source of truth.

**What to implement:** Inspect the ordered status projection and existing patient status `GET`. Decide projection ownership, connection lifecycle, stream endpoint contract, initial snapshot behavior, live broadcast trigger, fallback behavior, and local identity transport. Implement the projection-backed SSE path and connection cleanup. Test a committed status fact reaching an authorized stream and a stream failure leaving `GET` correct. Measure projection-to-stream delay, open connections, cleanup, and `GET` fallback latency. Document that SSE is a delivery optimization.

**Decisions:** Decide whether the status projection is the same history table or a separate read model; decide how live connections are keyed by patient; decide how a connection is removed on completion, timeout, or error; decide how a browser-compatible identity is represented without a full auth service.

**Directional hints:** The notification consumer is the only RabbitMQ subscriber for the projection; the SSE layer reads committed projection rows. Broadcast after the projection transaction commits. A connection registry keyed by patient supports structural isolation, but it does not replace authorization on the requested prescription.

**Relevant blog post or concepts:** `posts/series-4-product-sse/01-patient-first-api.md`, `posts/series-4-product-sse/02-sse-correctness.md`, `posts/series-3-rabbitmq/01-amqp-topology.md`, and `posts/series-4-product-sse/05-showcase-patient-notification.md`.

**Verification evidence:** A real HTTP/SSE client receives a committed status update in order, the status `GET` returns the same or fresher committed truth, and disconnecting the stream does not remove or lose business work from RabbitMQ.

**Exit criteria:** SSE is clearly a patient read path over durable state, not a work queue consumer or an alternate workflow engine.

### Milestone 8: Add event IDs, replay, ordering, authorization, and isolation

**Objective:** Make the realtime stream correct across reconnects, bursts, authorization boundaries, and multiple patients.

**What to implement:** Inspect projection sequences, ownership data, and stream lifecycle races. Decide the event ID and per-prescription sequence contract, `Last-Event-ID` parsing and invalid-value behavior, fresh snapshot behavior, replay query, catch-up boundary, authorization checks, and cross-patient fan-out rule. Implement ordered event IDs, replay of the missing tail, live catch-up, owner checks before replay, and patient-keyed delivery. Test fresh connection, reconnect after a gap, reconnect during a burst, stale or duplicate event, unauthorized initial connection, unauthorized replay, and two concurrent patients with interleaved events. Measure replay latency, event lag, sequence gaps, authorization failures, and cross-patient negative assertions. Document the browser identity transport assumption.

**Decisions:** Decide whether sequence numbers are per prescription rather than global; decide whether a late event is ignored or quarantined; decide how to handle an event ID beyond the current history; decide where ownership is checked both before and during replay; decide how the system behaves after a server restart.

**Directional hints:** Generate event IDs from durable projection sequence, never per send. Replay from `sequence > Last-Event-ID` after authorization. Record the highest sequence sent during catch-up and suppress live rows at or below it. Never use a latest-status cache to reconstruct an ordered stream.

**Relevant blog post or concepts:** `posts/series-4-product-sse/02-sse-correctness.md`, `posts/series-4-product-sse/03-testing-realtime.md`, `posts/series-4-product-sse/05-showcase-patient-notification.md`, and `posts/series-3-rabbitmq/05-idempotency-ordering.md`.

**Verification evidence:** The SSE client observes an exact increasing sequence, reconnects with `Last-Event-ID` and receives the missing tail without a gap, receives no unauthorized bytes, and two live patients receive only their own events while events are interleaved.

**Exit criteria:** Event ordering, replay, authorization, and cross-patient isolation are enforced and tested rather than described only in an architecture diagram.

### Milestone 9: Add failure, reconnect, and operational tests

**Objective:** Turn the production-grade claims into evidence against real local PostgreSQL, RabbitMQ, and HTTP/SSE behavior.

**What to implement:** Inspect the proof ledger and identify one authoritative test for each high-risk claim. Decide test-container version parity, fixture isolation, retry-delay overrides, queue cleanup, asynchronous wait strategy, stream termination, and explicit timeouts. Implement real integration tests for outbox atomicity, relay uncertainty, unroutable publication, manual-ack redelivery, bounded retry and DLQ, duplicate event identity, inbox rollback, ordering gaps, SSE reconnect, authorization, isolation, and the Exercise 2 workflow. Test broker and consumer failure at safe seams. Measure test stability, runtime, p95 status reads, SSE lag, and failure recovery time. Document every claim the suite does not prove.

**Decisions:** Decide which failures are simulated with latches and which use a real broker restart; decide how to assert committed state from a fresh database connection; decide how to poll asynchronous queues without fixed sleeps; decide how to terminate an SSE test without hanging CI.

**Directional hints:** Mocks can test decision logic and outbound arguments, but they cannot prove broker redelivery, TTL, `x-death`, publisher confirmation, or routing. Use a pinned broker image matching local Compose, override retry timing for tests, drain queues between scenarios, and use bounded polling. For SSE, a real HTTP client can control `Last-Event-ID` while a browser demo cannot.

**Relevant blog post or concepts:** `posts/series-3-rabbitmq/06-operational-testing.md`, `posts/series-4-product-sse/03-testing-realtime.md`, `posts/series-2-postgres/05-testing-postgresql.md`, and `posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md`.

**Verification evidence:** A test matrix maps each test to the claim it proves, dependency used, failure injected, observed durable result, and residual risk. The suite includes real broker and real HTTP/SSE evidence rather than only mocks or manual screenshots.

**Exit criteria:** The system's main reliability, realtime, and performance claims can be defended with reproducible tests and precise limits.

### Milestone 10: Produce the final architecture and tradeoff record

**Objective:** Leave a coherent, interview-ready record of patient value, system boundaries, guarantees, measurements, and deliberate omissions.

**What to implement:** Inspect the final code, migrations, topology, metrics, tests, and runbooks. Decide the final architecture diagram, event lifecycle, crash-window table, guarantee vocabulary, target measurement summary, scope omissions, and next improvements. Implement the documentation and walkthrough record. Test the documented start-to-finish demonstration from a clean local environment. Measure final status p95, local workflow rate, projection lag, retry/DLQ behavior, and test runtime. Document assumptions and all unproven production concerns.

**Decisions:** Decide which one or two design alternatives were rejected and why; decide which local measurements are decision evidence rather than capacity promises; decide which operational action requires human review; decide what would be built next if the product gained real authentication, multiple pharmacies, or higher volume.

**Directional hints:** Lead with the patient and the status `GET`, then explain persistence invariants, outbox/relay, worker delivery, projection, SSE, evidence, and limitations. Use at-least-once language consistently. Do not call a publisher confirm a processed message or an ordered SSE connection a globally ordered system.

**Relevant blog post or concepts:** `posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md`, `posts/series-4-product-sse/05-showcase-patient-notification.md`, `posts/series-4-product-sse/04-time-box-scoping.md`, and `artifacts/coach-assessment.md`.

**Verification evidence:** A clean-run walkthrough, architecture and failure diagrams, guarantee ledger, local measurement report, recovery procedure, test matrix, and a list of intentional gaps.

**Exit criteria:** The final record explains what the system guarantees, how each guarantee is tested, how a patient experiences failure, and what remains outside the local exercise.

## Step-by-Step Exercise Guide

### Step 1: Implement a transactional outbox

**Objective:** Turn each selected workflow transition and its intended publication into one durable PostgreSQL decision.

**What to implement:** Inspect Exercise 2 transitions, history writes, direct publisher calls, and event consumers. Decide the first event path, stable event ID, per-prescription sequence, event payload/reference boundary, retention, and transaction owner. Implement the outbox migration and application transaction. Test committed state/history/outbox together and rollback together. Measure insertion and pending age. Document the removal of RabbitMQ calls from the business transaction.

**Decisions:** Decide whether the outbox carries a fact, a command, or both through separate event types; decide how routing intent is stored; decide how a failed event insert maps to the command; decide when old published rows can be retained or removed.

**Directional hints:** The outbox is a durable handoff, not a broker transaction. Generate identity once and never regenerate it at the relay or replay boundary. Keep current state and history authoritative in PostgreSQL.

**Relevant blog post or concepts:** `posts/series-2-postgres/01-schema-design.md`, `posts/series-2-postgres/02-transactions-isolation.md`, `posts/series-3-rabbitmq/02-publisher-confirms-outbox.md`, and `posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md`.

**Verification evidence:** Real PostgreSQL tests show one commit contains state, history, and event intent, while an induced failure leaves none of them committed. The existing patient behavior still passes.

**Exit criteria:** A committed workflow transition cannot exist without a durable outbox record for each event that the design says must be published.

### Step 2: Implement a relay with publisher confirms and crash-window handling

**Objective:** Publish durable outbox intent to RabbitMQ with observable broker acceptance and safe recovery from relay uncertainty.

**What to implement:** Inspect the local exchange, bindings, outbox indexes, and relay claim requirements. Decide pending-row claim behavior, batch size, confirm correlation, returned-message handling, negative-confirm handling, marker timing, and restart behavior. Implement the relay and its durable publication state. Test successful confirm, unroutable publication, broker unavailable, concurrent claimers, and a crash after confirmation before marking. Measure outbox age, attempts, confirm latency, returns, and duplicate event deliveries. Document the crash-window result.

**Decisions:** Decide whether claims use row locking or a separate in-flight marker; decide which errors leave a row pending; decide how an operator finds a row that has exceeded an age threshold; decide how duplicate publication is proven safe by downstream identity.

**Directional hints:** A confirm is a broker receipt, not a routed or processed receipt. Mark after confirmation, not before. Retrying an uncertain publish with the same stable identity favors no loss and creates a duplicate that the consumer must neutralize.

**Relevant blog post or concepts:** `posts/series-3-rabbitmq/02-publisher-confirms-outbox.md`, `posts/series-3-rabbitmq/01-amqp-topology.md`, and `posts/series-3-rabbitmq/06-operational-testing.md`.

**Verification evidence:** Real broker assertions for confirmed routing, returned unroutable messages, disjoint relay claims, pending-row restart, and two deliveries of one stable event identity after the simulated crash window.

**Exit criteria:** Every committed event has a durable retry path until broker acceptance is recorded, and the relay's only unavoidable uncertainty produces an observable duplicate rather than silent loss.

### Step 3: Configure durable queues, manual acknowledgements, prefetch, and consumer concurrency

**Objective:** Make work recovery and in-flight capacity explicit at the consumer boundary.

**What to implement:** Inspect each message as work or fact and each queue's ordering requirement. Decide durable exchange/queue/message properties, work consumer count, prefetch, projection consumer count, acknowledgement mode, and transaction timing. Implement manual acknowledgement after the durable effect, persistent messages, durable queues, and configured listener concurrency. Test failure before commit, failure after commit before acknowledgement, clean restart, and bounded in-flight work. Measure unacknowledged messages, redelivery, processing time, worker concurrency, and prefetch capacity. Document which queue can compete and which cannot.

**Decisions:** Decide whether the status projection uses single active consumer or documented shards; decide the initial prefetch/concurrency pair; decide how duplicate/already-applied results are acknowledged; decide how channel ownership is kept valid for acknowledgement.

**Directional hints:** Commit first and acknowledge second. Do not let a listener swallow an exception and return normally if that means the broker will acknowledge unfinished work. Prefetch and concurrency govern uncertain work, not business correctness by themselves.

**Relevant blog post or concepts:** `posts/series-3-rabbitmq/03-ack-prefetch-concurrency.md`, `posts/series-3-rabbitmq/01-amqp-topology.md`, and `posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md`.

**Verification evidence:** A real broker failure test observes redelivery after an unacknowledged worker channel closes, and a durable-state check shows the selected transaction/ack ordering. Queue declarations and in-flight measurements match the written topology.

**Exit criteria:** Work is not silently lost on consumer failure, and the remaining post-commit duplicate is explicitly handed to idempotency.

### Step 4: Add bounded retries and dead-letter handling

**Objective:** Give transient failures a finite recovery path and give poison messages a visible, operator-controlled home.

**What to implement:** Inspect worker exceptions, database conflict categories, malformed payload behavior, and current requeue behavior. Decide failure classification, retry delay, attempt ceiling, retry queue topology, terminal DLQ route, `x-death` interpretation, payload retention, and replay procedure. Implement retry parking without an eager consumer, bounded return to work, and dead-letter quarantine. Test transient recovery, permanent failure with no retry, exhausted budget, poison isolation, DLQ inspection, and replay. Measure attempts, retry delay, queue depth, DLQ depth, and quarantine time. Document operator action and the terminal crash window.

**Decisions:** Decide whether the delay is fixed or graduated; decide which dependency and lock errors are retryable; decide how a malformed or missing prescription is quarantined; decide whether replay preserves attempt history and stable identity.

**Directional hints:** Immediate requeue is unbounded and can starve healthy work. Put waiting in a queue or an explicitly bounded Spring retry policy. The broker records attempt history, but the application still decides the policy. A DLQ is not a success state; it is visible quarantine.

**Relevant blog post or concepts:** `posts/series-3-rabbitmq/04-retries-dead-letters.md`, `posts/series-3-rabbitmq/06-operational-testing.md`, and `posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md`.

**Verification evidence:** A real broker test proves a bounded retry sequence, terminal dead letter, preserved payload/history, permanent-failure bypass, unrelated work progress, and an explicitly authorized replay.

**Exit criteria:** No worker exception creates an infinite hot loop, and an operator can see, classify, and recover or reject a poison message.

### Step 5: Add idempotent consumers and ordering rules

**Objective:** Neutralize duplicate deliveries at the business-effect boundary and protect patient-visible ordering.

**What to implement:** Inspect every consumer side effect, event identity, transition predicate, and status projection update. Decide inbox key scope, logical consumer names, same-transaction claim/effect boundary, duplicate outcome, retention, sequence allocation, gap policy, and ordered-consumer failover. Implement idempotent claims, duplicate acknowledgement, ordered projection application, stale suppression, and gap handling. Test concurrent duplicate deliveries, commit-then-ack redelivery, failed effect rollback, old-event replay, expected sequence, missing sequence, and out-of-order event. Measure duplicate claims, gap count, projection lag, and ordered processing latency. Document at-least-once delivery and at-most-once modeled effect without claiming exactly-once delivery.

**Decisions:** Decide whether one inbox table uses a composite consumer/event key; decide how long rows remain; decide whether gaps retry or dead-letter; decide why work queues can have competing consumers while facts require one effective ordered consumer or per-prescription sharding.

**Directional hints:** Do not perform an existence check and then apply the effect in separate transactions. Let the database uniqueness constraint win the concurrent claim. Treat an already-applied event as successful. A broker's queue order is not application order after retry or redelivery.

**Relevant blog post or concepts:** `posts/series-3-rabbitmq/05-idempotency-ordering.md`, `posts/series-3-rabbitmq/03-ack-prefetch-concurrency.md`, `posts/series-2-postgres/01-schema-design.md`, and `posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md`.

**Verification evidence:** Real PostgreSQL/RabbitMQ tests prove one modeled effect for duplicate stable IDs, rollback of a failed inbox/effect transaction, stale-event suppression, and visible non-application of sequence gaps.

**Exit criteria:** Duplicate deliveries are safe for the declared effects, and every ordered prescription stream has a durable sequence and recovery rule.

### Step 6: Add structured logs, metrics, correlation IDs, and recovery procedures

**Objective:** Make the system operable and its failure behavior explainable without inspecting source code.

**What to implement:** Inspect logs, database rows, broker headers, queue metrics, and existing test reports. Decide correlation propagation, safe identifiers, metric labels, warning/alert conditions, and runbook steps. Implement structured logs, measurements, correlation through HTTP/outbox/message/consumer/projection/SSE, and operator actions for stuck outbox, DLQ, projection gaps, consumer restart, and stale reservations. Test success, retry, dead-letter, duplicate, gap, unauthorized, and reconnect logs/metrics. Measure completeness, outbox age, confirms, returns, redeliveries, retries, DLQ depth, duplicates, projection lag, SSE replay, auth failures, pool wait, and status p95. Document recovery prerequisites and manual approval points.

**Decisions:** Decide what is safe to log about patient and medication data; decide whether correlation IDs are generated or accepted; decide which metric distinguishes broker acceptance from business completion; decide how replay avoids changing stable identity.

**Directional hints:** One log line cannot prove broker state. Use PostgreSQL, RabbitMQ, and application evidence together. Alert on DLQ depth and age, not only work-queue depth. Make runbooks idempotent or require explicit operator confirmation where repetition could change a patient record.

**Relevant blog post or concepts:** `posts/series-3-rabbitmq/06-operational-testing.md`, `posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md`, `posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md`, and `posts/series-4-product-sse/03-testing-realtime.md`.

**Verification evidence:** Structured log samples, metric assertions for normal and failed paths, queue/outbox/inbox inspection records, and one completed recovery drill with a documented operator outcome.

**Exit criteria:** A reviewer can correlate one prescription across boundaries and tell whether it is pending, routed, processed, projected, streamed, retried, or quarantined.

### Step 7: Add SSE as a separate patient notification path

**Objective:** Add realtime patient value without coupling connection lifecycle to business work delivery.

**What to implement:** Inspect the ordered status projection and the Exercise 2 status `GET`. Decide projection ownership, stream endpoint, initial snapshot, live broadcast trigger, cleanup callbacks, fallback behavior, and identity transport. Implement a projection-backed SSE stream and connection registry. Test committed projection changes reaching an authorized client, disconnected clients not affecting work queues, and `GET` remaining correct if the stream is absent. Measure projection-to-stream delay, connections, cleanup, replay readiness, and GET fallback latency. Document SSE as a delivery optimization.

**Decisions:** Decide whether the existing history table can serve as the projection or whether a separate read model is clearer; decide how connections are keyed by patient; decide how completion, timeout, and error remove connections; decide how local authentication is represented for a real stream client.

**Directional hints:** The notification consumer is the RabbitMQ subscriber; the SSE connection is only an HTTP client of committed projection data. Broadcast after commit. A patient-keyed connection registry helps prevent misrouting, but every requested prescription still needs an ownership check.

**Relevant blog post or concepts:** `posts/series-4-product-sse/01-patient-first-api.md`, `posts/series-4-product-sse/02-sse-correctness.md`, `posts/series-3-rabbitmq/01-amqp-topology.md`, and `posts/series-4-product-sse/05-showcase-patient-notification.md`.

**Verification evidence:** A real HTTP/SSE client receives a committed event, the status `GET` reports the same or newer committed truth, and disconnecting the client leaves the RabbitMQ work path unaffected.

**Exit criteria:** SSE has no role in claiming or completing business work and can be disabled without removing the status `GET`.

### Step 8: Add event IDs, replay, ordering, authorization, and isolation

**Objective:** Make reconnecting patient streams complete, ordered, authorized, and isolated.

**What to implement:** Inspect projection sequence allocation, ownership relationships, live broadcast timing, and connection join races. Decide durable event ID, per-prescription sequence, fresh snapshot, `Last-Event-ID` parsing, replay query, catch-up boundary, authorization points, and patient fan-out structure. Implement the replay and catch-up path, patient-keyed delivery, ownership enforcement, stale/duplicate suppression, and invalid replay behavior. Test fresh stream, reconnect after missing events, reconnect during a burst, exact ordered IDs, stale event, duplicate event, unauthorized connect, unauthorized replay, and two-patient interleaving. Measure replay latency, sequence gap, event lag, auth failure, and isolation results. Document browser header/token constraints and server-restart behavior.

**Decisions:** Decide why sequence is per prescription rather than global; decide whether a gap is quarantined or retried; decide how an event ID beyond current history is handled; decide how catch-up prevents duplicate live sends; decide which authorization check runs before any byte is written.

**Directional hints:** Event IDs must survive connection loss. Query events after the authorized last ID from durable storage, then suppress live rows at or below the applied boundary. Do not build a latest-status cache that loses the timeline or can send a stale status after a newer one.

**Relevant blog post or concepts:** `posts/series-4-product-sse/02-sse-correctness.md`, `posts/series-4-product-sse/03-testing-realtime.md`, `posts/series-4-product-sse/05-showcase-patient-notification.md`, and `posts/series-3-rabbitmq/05-idempotency-ordering.md`.

**Verification evidence:** Exact sequence assertions, reconnect tail assertions, no-event authorization assertions, and an interleaved two-patient test that fails if any event crosses the patient boundary.

**Exit criteria:** SSE ordering, replay, authorization, and isolation are proved by protocol-level tests rather than a browser demo.

### Step 9: Add failure, reconnect, and operational tests

**Objective:** Make every important production-grade claim executable against the real local dependencies.

**What to implement:** Inspect the final proof ledger and select the highest-risk claim at each boundary. Decide pinned PostgreSQL/RabbitMQ image parity, fixture cleanup, retry-delay overrides, queue draining, asynchronous polling, latch-based crashes, explicit stream timeouts, and test data isolation. Implement real PostgreSQL, RabbitMQ, and HTTP/SSE integration tests for outbox atomicity, relay uncertainty, routing, acknowledgement/redelivery, retries, DLQ, inbox, ordering, reconnect, authorization, isolation, and preserved workflow. Test the failure seams. Measure suite runtime, flakiness, p95 status reads, SSE lag, and recovery time. Document unproven claims.

**Decisions:** Decide which failures require a real broker restart and which can use a controlled listener crash; decide how committed state is read from a fresh connection; decide how an SSE test terminates; decide what evidence is sufficient for server-restart composition without reloading a full application context.

**Directional hints:** A mocked broker can test application decisions, not broker responses. Use a real pinned broker for confirm, routing, redelivery, TTL, and dead-letter claims. Use a real HTTP client for `Last-Event-ID`, concurrent streams, and denial-before-data checks. Poll with explicit bounds rather than sleeping fixed durations.

**Relevant blog post or concepts:** `posts/series-3-rabbitmq/06-operational-testing.md`, `posts/series-4-product-sse/03-testing-realtime.md`, `posts/series-2-postgres/05-testing-postgresql.md`, and `posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md`.

**Verification evidence:** A claim-to-test matrix includes dependency, injected failure, expected durable result, observed metrics/logs, and residual risk. The suite contains one full broker-to-SSE path plus focused tests for each failure property.

**Exit criteria:** The local system's reliability, realtime, isolation, and performance claims have reproducible evidence and explicit limits.

### Step 10: Produce the final architecture and tradeoff record

**Objective:** Convert the implementation and evidence into a concise product-engineering defense.

**What to implement:** Inspect final behavior, schema, topology, metrics, recovery procedures, and test output. Decide the final architecture diagram, state/event lifecycle, crash matrix, guarantee ledger, local performance summary, omissions, and next steps. Implement the architecture and tradeoff record. Test the documented clean-start walkthrough. Measure final p95 status reads, workflow submission rate, projection lag, retry/DLQ outcome, and test runtime. Document all assumptions and non-production limits.

**Decisions:** Decide which alternatives were rejected, why one local deployable service remains sufficient, which operations require human review, and which future product change would justify a new boundary.

**Directional hints:** Tell the story in causal order: patient value, status baseline, PostgreSQL invariants, outbox, relay, work/fact topology, consumer semantics, projection, SSE, evidence, and omissions. Use precise at-least-once terminology and never let a polished stream imply a stronger delivery guarantee than the database and broker provide.

**Relevant blog post or concepts:** `posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md`, `posts/series-4-product-sse/05-showcase-patient-notification.md`, `posts/series-4-product-sse/04-time-box-scoping.md`, and `artifacts/coach-assessment.md`.

**Verification evidence:** A clean local walkthrough, architecture diagram, crash-window table, guarantee matrix, local target report, recovery record, test matrix, and intentional-gap list.

**Exit criteria:** The final record explains what a patient experiences, what each component guarantees, how failure recovers, and what is still outside the exercise.

## Required Decisions

- How Exercise 2 behavior, API contracts, state vocabulary, inventory invariants, query evidence, and approximately 10 submissions-per-second local target are preserved.
- Which state changes write history and outbox events together, which event identity and per-prescription sequence are stable, and how outbox retention works.
- How relay rows are claimed, how publisher confirms are correlated, how unroutable messages are surfaced, and why the relay marks only after confirmation.
- The difference between database commit, broker acceptance, routing, consumer processing, and business completion.
- Which exchanges, queues, bindings, routing keys, retry queues, and dead-letter queues exist, and why work commands and facts are separate.
- Which queues are durable, which messages are persistent, what local broker version is pinned, and what durability still does not guarantee.
- Why work consumers use manual acknowledgements, when acknowledgement happens, how prefetch and concurrency bound in-flight work, and what redelivery means.
- How retryable and permanent failures are classified, where the bounded retry budget lives, how poison messages are quarantined, and how replay is authorized and observed.
- How inbox uniqueness is scoped per logical consumer, why the claim and effect share one transaction, and why duplicate events are acknowledged as already applied.
- What ordering is required per prescription, how sequence gaps are handled, why packaging can be parallel while status projection cannot, and how failover works.
- Which structured logs, metrics, correlation IDs, queue signals, outbox signals, projection signals, and recovery procedures support diagnosis without exposing sensitive data.
- Why SSE is a separate projection-backed patient path and never a competing RabbitMQ consumer.
- Where event IDs come from, how `Last-Event-ID` replay and catch-up work, where authorization is checked, and how cross-patient isolation is structurally and negatively tested.
- How the status `GET` remains the correctness baseline and why SSE may lag it but must never become a conflicting source of truth.
- How the p95 status `GET` below 250 ms is measured locally, what the sample and workload are, and what the result does not prove about production.
- Which Kotlin decisions cover nullability, immutable versus mutable values, sealed outcomes, state modeling, transaction proxies, blocking versus coroutine/thread usage, and testing idioms without mechanically translating Java.
- Why the design does not claim exactly-once delivery, managed infrastructure, cloud capacity, global ordering, or a complete authentication system.

## Tests and Evidence

Preserve the full Exercise 2 suite, then add the following production-grade evidence:

- Real PostgreSQL transaction tests showing state/history/outbox atomicity and rollback on failure.
- Real relay tests for pending-row claiming, correlated publisher confirmation, negative/uncertain confirmation, unroutable return, and the crash window after broker acceptance but before marking.
- Real RabbitMQ topology tests showing durable exchanges/queues, correct work/fact bindings, persistent messages, and no SSE connection attached as a work consumer.
- Manual-ack redelivery evidence for failure before commit and failure after commit before acknowledgement, with inbox protection for the latter.
- Prefetch and concurrency evidence showing bounded unacknowledged work and a documented effective in-flight limit.
- Retry and dead-letter tests showing classification, bounded attempts, retry delay, poison isolation, payload/header preservation, DLQ observability, and deliberate replay.
- Duplicate event tests showing stable event identity and one modeled effect per logical consumer, plus rollback when the effect fails after an inbox claim.
- Ordering tests showing expected per-prescription sequences, stale/duplicate suppression, gap handling, and ordered projection failover behavior.
- Structured log and metric assertions for correlation ID, prescription ID, event ID, consumer role, retry count, outbox age, DLQ depth, projection lag, SSE replay, authorization failure, and status latency.
- Real SSE protocol tests for fresh snapshot, ordered IDs, reconnect with `Last-Event-ID`, events created while disconnected, reconnect during a burst, stale/duplicate suppression, and explicit stream termination.
- Authorization tests for initial connection and replay, both returning denial with zero patient events.
- A two-patient concurrent isolation test with interleaved events proving each patient receives only their own sequence.
- One end-to-end test through REST submission/approval, PostgreSQL outbox, relay, RabbitMQ projection consumer, projection store, and SSE delivery.
- A local status `GET` performance run with a warm-up, stated sample count, authenticated owner reads, representative data, concurrent workflow activity, p50/p95/p99, error rate, machine, versions, and pool observations. The target is p95 below 250 ms; state clearly whether it passed.
- A preserved Exercise 2 workflow/load report showing the reliability work did not regress inventory, transition, duplicate-effect, or approximately 10 submissions-per-second local behavior.
- A final proof matrix marking each claim as proven by unit, real PostgreSQL, real RabbitMQ, real HTTP/SSE, manual observation, or design reasoning.

The suite must state its limits. It does not prove production scale, multi-node broker durability, browser implementation behavior, global event ordering, real identity-provider integration, or exactly-once delivery. It proves local behavior under the declared conditions and makes residual risks visible.

## Bottleneck & Reflection Questions

- What does a publisher confirm prove, and why does it not prove routing, consumer processing, or business completion?
- Walk through a relay crash after broker acceptance but before the outbox marker. Why is a duplicate preferable to silent loss, and how is it recognized?
- What happens if the exchange accepts a message but no binding routes it, and where is that failure observed?
- Why must work consumers acknowledge after the durable business transaction commits?
- What does prefetch bound, what does consumer concurrency change, and how much unacknowledged work can a crash reclaim?
- Why is the redelivery flag not a retry budget, and where does bounded retry evidence live?
- Which failures are retryable, which are permanent, and why should a poison message be quarantined rather than immediately requeued?
- Why must a retry queue be a parking place rather than an eager second worker queue?
- How does an inbox uniqueness constraint close the check-then-act race, and why must it share a transaction with the business effect?
- Why is a duplicate event acknowledged as success rather than sent back through retry?
- What ordering does RabbitMQ provide on the happy path, and what changes after retry, redelivery, multiple consumers, or multiple queues?
- Why can packaging use competing consumers while a status projection needs one effective ordered consumer or per-prescription sharding?
- What does a sequence gap mean, and why should the projection avoid applying a later status out of order?
- Why must SSE connections never be RabbitMQ consumers, and what patient isolation failure would a shared connection queue create?
- Where do SSE event IDs come from, what does `Last-Event-ID` request, and how does catch-up avoid duplicates during a reconnect burst?
- Why must authorization happen before replay and inside the replay data access path?
- How do you prove two patients connected at once cannot observe each other's events?
- What happens to SSE after a server restart, and why is the loss of in-memory connections acceptable?
- Why is the REST status `GET` still the correctness baseline after SSE exists?
- How does the p95 status target relate to indexes, projection choice, pool hold time, and local data size, and what does it not say about production?
- Which metrics distinguish an outbox backlog from a queue backlog, a retry storm from a consumer slowdown, and projection lag from an SSE connection failure?
- What recovery action is safe for a dead letter, and why must replay preserve stable identity and attempt history?
- Which Kotlin boundary would be dangerous if a Java engineer mechanically added mutable state, caught and swallowed listener exceptions, or moved blocking I/O into an unbounded coroutine context?

## Success Criteria

- Exercise 2's patient and staff behavior, PostgreSQL invariants, local target evidence, and status `GET` contract remain intact.
- State changes, history facts, and outbox intent commit atomically in PostgreSQL.
- The relay uses stable event identity, publisher confirms, visible routing failures, durable messages, and a no-loss preference under uncertainty while tolerating duplicate publication.
- Work and fact AMQP topology are separate, durable, inspectable, and locally version-pinned.
- Work consumers use manual acknowledgements after committed effects, documented prefetch, and deliberate concurrency; redelivery is expected and observable.
- Retryable failures have a bounded path, permanent and exhausted failures reach a dead-letter quarantine, poison messages do not loop forever, and replay is documented.
- Idempotent consumers use inbox uniqueness and the same transaction as the business effect; duplicate delivery does not repeat the modeled effect.
- Per-prescription ordering is enforced with durable sequence information and a documented gap/failover policy.
- Structured logs, metrics, correlation IDs, and recovery procedures make failure and recovery visible without logging unnecessary patient data.
- SSE is a separate projection-backed patient notification path, not a competing RabbitMQ consumer.
- SSE events are ordered, event IDs are durable and monotonic per prescription, reconnecting clients can replay or catch up with `Last-Event-ID`, and live connections are cleaned up.
- Patient authorization is enforced before connect and replay, and a two-patient concurrent test proves cross-patient isolation.
- The REST status `GET` remains the authoritative correctness baseline and stays available when SSE is delayed or unavailable.
- The local p95 patient status `GET` measurement is below 250 ms under its stated workload, with no production-capacity claim.
- Failure, recovery, broker, database, SSE, and performance claims are backed by the appropriate real integration evidence or explicitly marked as reasoning.
- The final architecture record states at-least-once delivery, avoids exactly-once delivery claims, and names assumptions, intentional gaps, and next steps.

## Interview Defense Checklist

- Start with the patient journey and explain why the status `GET` remains the truth after SSE is added.
- Draw the PostgreSQL state/history/outbox commit, relay, work exchange, fact exchange, projection, and SSE path without making SSE a broker consumer.
- Walk every crash window: before database commit, after commit before relay, after confirm before marking, before worker commit, after commit before acknowledgement, during retry/DLQ handoff, during projection, and during SSE reconnect.
- Distinguish publisher confirmation, message routing, consumer processing, business completion, and SSE delivery.
- Explain at-least-once delivery, idempotent effects, inbox uniqueness, and why exactly-once delivery is not claimed.
- Explain why manual acknowledgement follows the transaction, how prefetch and concurrency affect recovery, and why immediate requeue is unsafe for poison messages.
- Describe retry classification, attempt budget, dead-letter inspection, and stable-identity replay.
- Explain per-prescription sequence numbers, gap policy, ordered projection concurrency, and why work queues can scale differently.
- Prove authorization and cross-patient isolation with the two-patient interleaving test rather than a verbal promise.
- Explain fresh SSE connection, `Last-Event-ID` replay, reconnect during a burst, and server restart recovery.
- Defend the local p95 status target and approximately 10 submissions-per-second preservation as measurements with explicit limits.
- Explain the Kotlin choices around nullability, immutable values, sealed outcomes, state modeling, Spring proxy boundaries, blocking I/O, coroutines, and tests.
- Name what remains outside the exercise: real identity infrastructure, multi-node production operations, cloud deployment, global ordering, a full frontend, and exactly-once delivery.
