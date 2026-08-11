# Foundation Pharmacy Prescription Fulfillment System - Exercise

## Objective

Build a straightforward, readable local Pharmacy Prescription Fulfillment System with Kotlin, Spring Boot, PostgreSQL, RabbitMQ, REST, and Docker Compose. The exercise must prove one complete patient journey: a patient submits a prescription, inventory is checked and handled, a pharmacist approves or rejects it, packaging progresses, the prescription becomes ready for collection, and fulfillment is recorded. The patient must be able to retrieve the current status through an authenticated-by-stand-in status `GET` endpoint.

This is the first level, not a production design. Prefer explicit code and a small number of understandable components over optimization. The deliberate learning boundary is that database writes and RabbitMQ publication are connected directly, basic inventory handling is not race-safe, and failure recovery is documented rather than silently solved.

For a two-hour challenge mode, complete the patient API, state machine, schema and seed data, one RabbitMQ workflow step, and the happy-path evidence. Use the remaining time up to five hours for stronger API/error tests, the failure-window demonstration, and interview documentation. These are local preparation targets, not production capacity claims.

## Starting Point

Start by inspecting the repository's Kotlin, Spring Boot, PostgreSQL, RabbitMQ, REST, Docker Compose, and testing conventions. Use the original challenge at `Product Engineer_ Tech Challenge.md`, the editorial scope in `artifacts/blog-plan.md`, and the candidate priorities in `artifacts/coach-assessment.md`. Read the Kotlin and patient-first material before choosing names or boundaries, especially `posts/series-1-kotlin/01-kotlin-for-java-developers.md`, `posts/series-1-kotlin/02-nullability-results-domain-errors.md`, `posts/series-1-kotlin/03-state-machines-with-sealed-types.md`, and `posts/series-4-product-sse/01-patient-first-api.md`.

Treat any existing application conventions as the starting point. Do not introduce a second framework, a full frontend, cloud services, managed infrastructure, or a microservice fleet. Use local PostgreSQL and RabbitMQ through the repository's Docker Compose context, and use curl-like clients, a test client, or small scripts for patients, pharmacists, packagers, and fulfillment staff.

Before implementation, write down the assumptions that the original challenge leaves open: when inventory is claimed, what rejection does to a reservation, what `READY_FOR_COLLECTION` means, how a patient is identified, and which staff actions are simulated. The next exercise must be able to read these decisions without reverse-engineering them from the service.

## Background & Motivation

The original challenge is judged on patient experience, simplicity, system design, and failure handling within approximately two to five hours. A patient waiting in a pharmacy needs a truthful status, not an internal dashboard. A simulated pharmacist and packager can use an inconvenient interface, which keeps the exercise focused on the backend product slice.

The Foundation level intentionally uses a direct database-write and publish path. For example, an approval may commit its PostgreSQL state and then publish a packaging message. That path is easy to understand and easy to demo, but a process failure between the two operations can leave an approved prescription with no packaging work. A consumer can also commit a business effect and fail before the broker learns that it is finished. These windows must appear in the written evidence.

Do not add a transactional outbox, publisher confirms, bounded retries, dead-letter handling, or an idempotent consumer here. Do not add SSE. Those omissions are the handoff to the Optimization and Production exercises. The Foundation result is valuable only if its boundaries are named precisely.

## System Specification

### Actors and patient experience

The patient submits medication lines and receives a prescription identifier and initial status. The patient can retrieve only their own prescription through the status `GET`, using a local identity stand-in such as a documented patient header or test principal. The response should say what the patient needs to know: current status, medication summary, submitted time, and a short ordered status timeline. It must not expose internal queue names, database identifiers that are not part of the contract, stack traces, or broker delivery details.

The pharmacist receives a small queue view and can approve or reject a prescription awaiting approval. The packager can complete packaging through a deliberately inconvenient staff action or script. The fulfillment or collection worker can mark a ready prescription as fulfilled. No staff frontend is required.

### Patient and staff API contracts

| Consumer | Contract to provide | Expected behavior |
| --- | --- | --- |
| Patient | `POST /prescriptions` with patient identity, a non-empty set of medication lines, positive quantities, and a client submission key | Valid input creates one prescription and returns its identifier and initial view. A repeated key must have a documented outcome rather than silently creating an ambiguous duplicate. |
| Patient | `GET /prescriptions/{id}` with patient identity | Returns the authoritative current status and patient-safe timeline for the owner. A missing identifier is not the same as an unauthorized identifier. |
| Pharmacist | `GET /staff/pharmacist/queue` | Returns a bounded, reviewable set of prescriptions awaiting approval. The first level may use a simple limit rather than advanced pagination. |
| Pharmacist | An approve action and a reject action for a prescription | Each action expresses intent, validates the current state, and returns a deliberate success or conflict outcome. Rejection records a reason. |
| Packager | A packaging completion action or simulated packager command | The action can move only a prescription in the packaging state to ready for collection. |
| Fulfillment staff | A collection or fulfillment action | The action can move only a ready prescription to fulfilled. |

Use a consistent error contract. Invalid input maps to a validation response, a missing prescription maps to not found, an illegal transition or insufficient stock maps to a conflict, and a database or broker outage remains a technical failure rather than a false business success. Document how the local identity stand-in would be replaced by a real authorization layer; do not build that layer for this exercise.

### Prescription states and domain outcomes

Use this state vocabulary unless a different vocabulary is justified in the exercise record:

| State | Meaning | Legal next actions |
| --- | --- | --- |
| `SUBMITTED` | The request has passed basic input validation and is being recorded | Complete the submission decision into `AWAITING_APPROVAL`, or fail the command without claiming success |
| `AWAITING_APPROVAL` | Inventory handling succeeded and a pharmacist decision is required | Approve or reject |
| `APPROVED` | A pharmacist accepted the prescription and packaging work should begin | Start packaging through the basic RabbitMQ workflow |
| `PACKAGING` | A packager has taken the work and packaging is in progress | Complete packaging |
| `READY_FOR_COLLECTION` | Packaging is complete and the patient may be called or notified by the staff process | Fulfill or record collection |
| `FULFILLED` | The prescription-to-patient cycle is complete | No further workflow command |
| `REJECTED` | A pharmacist declined the prescription with a reason | No further workflow command |

The domain must distinguish an accepted transition, a missing prescription, an invalid current state, invalid input, insufficient inventory, an ownership failure, and an infrastructure failure. Use Kotlin nullable values for ordinary repository absence, sealed domain outcomes for expected command alternatives, and exceptions for failures that the current operation cannot safely complete. Do not turn every failure into `null` or a generic success/failure boolean.

### Inventory responsibilities

Seed a small medication catalog containing at least Amoxicillin, Ibuprofen, Lisinopril, Metformin, and Atorvastatin, with deterministic inventory quantities. Choose submission-time reservation for this exercise so that a prescription waiting for approval has a clear stock decision. On rejection, restore a reservation according to the documented model; on fulfillment, do not decrement the same units a second time.

The Foundation implementation may use a straightforward read, check, and write sequence inside an understandable application transaction. It must work for the happy path, but it must explicitly state that the approach is not a concurrency proof. A database check constraint for non-negative quantity is useful as a safety net, but a failed constraint is not the same as a clean insufficient-inventory domain outcome. Exercise 2 replaces this path with an atomic reservation decision.

### Persistence responsibilities

Use small, versioned migrations for medication data, inventory, prescriptions, prescription lines, reservations if used, and status history. Add primary keys, foreign keys, positive-quantity checks, legal-status constraints, and the uniqueness rule chosen for repeated patient submission. Store current status for fast status reads and history for the patient timeline and later diagnostics. Seed fixed, readable sample data through the repository's local setup conventions.

The current prescription row is the correctness source for the status `GET`. A status history row explains the journey but must not become a competing source of current state. The Foundation schema may be simple, but the migration and seed process must be reproducible from an empty local database.

### Messaging behavior and deliberate boundaries

Define a small direct work topology with a named exchange, a packaging queue, a routing key, and a documented consumer. Approval should visibly cause at least one message to travel through RabbitMQ; packaging work should not be disguised as a REST-only transition. Keep all logical workers in the same local service or a small simulated process unless the repository already has a clear boundary.

The direct publish path is intentional: the application writes the state and then publishes without a transactional outbox. Use basic acknowledgement behavior sufficient for the happy path, but do not claim that a consumer crash is recovered. There are no bounded retries, no retry queue, no dead-letter queue, no inbox uniqueness, and no idempotent consumer in this level. Duplicate delivery may repeat a side effect or may be rejected by a later state check; either result must be documented rather than described as safe.

### Failure boundaries and observability

Record the outcome of a database failure before commit, a database commit followed by a publish failure, an unroutable message, a consumer failure before acknowledgement, and two concurrent inventory attempts. Explain which cases are visible to the patient through the status `GET` and which can leave work stalled. Log the prescription identifier, actor or worker role, transition, and failure category for the demonstrated path. A small manual failure matrix is more valuable than a large unproven monitoring design.

### Kotlin guidance for a Java engineer

Use `val`, immutable data classes or value objects for request and domain values, and read-only collection types at boundaries. Use `var` only where a persistence adapter or framework lifecycle genuinely requires mutation. Do not expose a mutable prescription object with a public status setter; give the application named operations such as approval, rejection, packaging completion, and fulfillment.

An enum persisted as a status column is enough at this level if transition behavior is explicit. A sealed hierarchy can model richer states or outcomes, but do not create a complicated persistence mapping merely to display Kotlin features. Keep nullable types for absence, use sealed outcomes for expected business alternatives, and let technical failures cross the infrastructure boundary as exceptions or an explicitly documented technical result.

Place the database transaction around one application command that owns the related writes. Remember that Spring transaction annotations are proxy-based: the call must enter through a Spring-managed bean, and a self-call does not create a new proxy boundary. Keep RabbitMQ publication outside the database transaction in this intentionally limited design, and name the resulting gap.

The local PostgreSQL and RabbitMQ clients are blocking unless the repository deliberately provides non-blocking clients. Prefer ordinary Spring worker threads and a small, explainable concurrency model for this level. Do not add coroutines just to appear modern; if coroutines are used, make the blocking dispatcher boundary and cancellation behavior explicit. Test domain transitions with focused Kotlin/JUnit tests, use small fixture builders, and reserve real PostgreSQL/RabbitMQ tests for claims that mocks cannot prove. Avoid mechanically translating Java beans, `Optional` everywhere, mutable anemic models, and nested scope-function chains that hide transaction ownership.

## Milestone Plan

The milestones must be completed in this order. A later milestone may clarify an earlier decision, but it must not silently erase an intentional limitation.

| Order | Milestone | Depends on | Must-have work | Optional stretch |
| --- | --- | --- | --- | --- |
| 1 | Define the prescription state machine and domain outcomes | Repository and challenge inspection | Legal transitions, actors, failure categories, patient-facing meanings | A small transition table covering every command and state |
| 2 | Define patient and staff API contracts | Milestone 1 | Patient submission, owner-scoped status `GET`, pharmacist, packager, and fulfillment actions | Problem Details-style examples and a queue cursor decision |
| 3 | Create the PostgreSQL schema and seed data | Milestones 1-2 | Versioned migrations, constraints, deterministic medications and inventory | Status history and reservation audit detail |
| 4 | Implement the straightforward workflow | Milestones 1-3 | Complete happy path, basic inventory handling, staff actions, owner-safe status reads | A submission idempotency key and a thin application-service boundary |
| 5 | Add a basic RabbitMQ topology | Milestone 4 | One exchange, packaging queue, routing key, consumer, visible message movement | A separate fulfillment work queue if it keeps the topology clearer |
| 6 | Add unit, API, and happy-path evidence | Milestones 1-5 | Domain tests, invalid input tests, API checks, one full workflow test | Real local broker/database test assertions and a scripted demonstration |
| 7 | Measure a baseline and document known failure windows | All previous milestones | Baseline timings, direct publish gap, inventory race note, limitation record | A controlled failure demonstration and an interview walkthrough |

The two-hour stopping point is after Milestone 6 with a complete happy path and a short limitation record. The five-hour version should complete all seven milestones and improve evidence rather than add a frontend. During the broader 30-day, 60-hour preparation period, extend each milestone by reading its cited post, rerunning the evidence against clean containers, writing an ADR or diagram, and rehearsing what is proven versus deferred. Do not use Foundation time to implement the Optimization or Production architecture early.

### Milestone 1: Define the prescription state machine and domain outcomes

**Objective:** Establish one workflow vocabulary that the API, persistence, worker messages, tests, and patient view can all share.

**What to implement:** Name every state and legal command; define who may issue it; define the patient-visible meaning; classify missing, invalid, conflicting, insufficient-stock, unauthorized, and technical outcomes. Record the chosen inventory timing and reservation lifecycle.

**Decisions:** Decide whether `SUBMITTED` is persisted briefly or represented by the submission history; decide whether rejection is legal only from `AWAITING_APPROVAL`; decide whether `READY_FOR_COLLECTION` and `FULFILLED` are separate; decide whether the domain uses an enum plus behavior or a sealed state model.

**Directional hints:** Keep the graph flat and intent-based. Do not expose a generic status setter. A local Kotlin transition check improves readability, but it cannot decide a race between two requests; leave the database handoff visible for Exercise 2.

**Relevant blog post or concepts:** `posts/series-1-kotlin/03-state-machines-with-sealed-types.md`, `posts/series-1-kotlin/02-nullability-results-domain-errors.md`, and the state-machine section of `posts/series-4-product-sse/01-patient-first-api.md`.

**Verification evidence:** Produce a transition table or diagram showing at least one legal and one illegal attempt for every command. Unit-level examples must distinguish not found, invalid state, invalid input, and technical failure.

**Exit criteria:** The team can explain every state, every legal transition, and every expected domain outcome without referring to an implementation detail.

### Milestone 2: Define patient and staff API contracts

**Objective:** Give patients a small, truthful interface and give staff only the commands required to move the workflow.

**What to implement:** Specify request fields, response fields, ownership behavior, HTTP status mappings, and endpoint names for submission, status lookup, pharmacist queue, approval, rejection, packaging completion, and fulfillment. Make the status `GET` return the current state and an ordered timeline.

**Decisions:** Decide where patient and staff identity comes from locally; decide whether a repeated submission key returns the existing prescription or a conflict; decide how a patient-safe response differs from a worker view; decide which staff commands are HTTP actions and which are RabbitMQ work.

**Directional hints:** Start with the two patient actions, then add only the staff commands needed for the walkthrough. Treat the queue as a query and the transition endpoints as commands. Use conflict responses for illegal transitions instead of silently returning the unchanged object as success.

**Relevant blog post or concepts:** `posts/series-4-product-sse/01-patient-first-api.md`, `posts/series-4-product-sse/04-time-box-scoping.md`, and `posts/series-1-kotlin/01-kotlin-for-java-developers.md`.

**Verification evidence:** Review the contract with a patient scenario and a pharmacist scenario. Demonstrate that an owner can read a prescription, a different patient cannot, and a staff action cannot be invoked through a generic status update.

**Exit criteria:** A reviewer can drive the entire product slice from the documented API and knows which actions are intentionally inconvenient simulations.

### Milestone 3: Create the PostgreSQL schema and seed data

**Objective:** Persist the minimum facts needed for the workflow, patient status, inventory decision, and explanation of history.

**What to implement:** Create versioned migrations for medications, inventory, prescriptions, lines, optional reservations, and status history. Add constraints for ownership references, positive quantities, legal statuses, and non-negative inventory. Seed the five named medications and deterministic quantities.

**Decisions:** Decide whether identifiers are local or externally owned; decide whether duplicate medication lines are merged or rejected; decide whether the first exercise uses a reservation row; decide which indexes are justified by the first status and queue reads.

**Directional hints:** Keep the schema boring and relational. Current status should be a direct read; history should be append-only evidence. A constraint can catch an invalid row, but it does not turn a read-check-write reservation into an atomic concurrency design.

**Relevant blog post or concepts:** `posts/series-2-postgres/01-schema-design.md`, `posts/series-2-postgres/05-testing-postgresql.md`, and the migration discussion in `artifacts/blog-plan.md`.

**Verification evidence:** Apply migrations to an empty local PostgreSQL database, seed the known medications, and verify foreign-key, quantity, status, and uniqueness failures. Record the exact seed assumptions without including copyable SQL in this exercise document.

**Exit criteria:** A fresh local database can be created and seeded reproducibly, and each schema element has a stated product or correctness purpose.

### Milestone 4: Implement the straightforward workflow

**Objective:** Make the patient journey work end to end before improving concurrency or reliability.

**What to implement:** Implement submission validation, basic inventory handling, prescription persistence, status history, owner-scoped status lookup, pharmacist approval/rejection, packaging progression, ready-for-collection, and fulfillment. Keep the application-service transaction boundary visible and keep external publishing outside it for the deliberate direct path.

**Decisions:** Decide how a reservation is released on rejection and consumed on fulfillment; decide how a lost HTTP response affects a retried submission; decide how the service maps repository absence and invalid state; decide whether staff actions are synchronous commands or simulated scripts.

**Directional hints:** Make each operation named and narrow. Do not let controllers or message listeners assign arbitrary statuses. Prefer a readable sequence of application statements over a chain of Kotlin scope functions that hides which writes are part of the same transaction.

**Relevant blog post or concepts:** `posts/series-1-kotlin/05-showcase-kotlin-prescription-domain.md`, `posts/series-2-postgres/02-transactions-isolation.md`, and `posts/series-4-product-sse/05-showcase-patient-notification.md`.

**Verification evidence:** Submit one seeded prescription, retrieve its status, approve it, observe the packaging transition, complete packaging, fulfill it, and retrieve the final status as the owning patient. Also show rejection and an invalid transition.

**Exit criteria:** The full workflow is demonstrable without manual database edits, and every patient-visible status is backed by a committed current row.

### Milestone 5: Add a basic RabbitMQ topology

**Objective:** Make asynchronous workflow coordination visible while keeping the topology intentionally small.

**What to implement:** Declare a named exchange, a packaging queue, a routing key, a message meaning, and one consumer or simulated worker. Publish the packaging work after the approval state write and let the consumer advance the documented packaging state.

**Decisions:** Decide whether the exchange is direct or topic-based for the one work path; decide who owns the packaging queue; decide what basic acknowledgement behavior the local client uses; decide how an unroutable message or unavailable broker is surfaced.

**Directional hints:** Separate a work command from a status fact even in this small topology. Use durable local declarations if the repository already does so, but do not imply that durability closes the database-to-broker gap. Do not add an SSE consumer or a notification queue.

**Relevant blog post or concepts:** `posts/series-3-rabbitmq/01-amqp-topology.md`, `posts/series-3-rabbitmq/03-ack-prefetch-concurrency.md`, and `posts/series-4-product-sse/04-time-box-scoping.md`.

**Verification evidence:** Inspect the local broker and show the approval message arriving at the intended queue and being consumed by the intended worker. Capture a log line or test assertion linking the prescription identifier to the message intent.

**Exit criteria:** RabbitMQ visibly coordinates one workflow step, the topology is documented, and the absence of confirms, retries, dead letters, inbox records, and SSE is explicit.

### Milestone 6: Add unit, API, and happy-path evidence

**Objective:** Prove the product slice at the cheapest credible test levels.

**What to implement:** Add Kotlin unit tests for state rules and domain outcomes, API tests for validation/not found/conflict/ownership behavior, and one end-to-end happy-path test against the local stack. Add deterministic fixtures and a short manual demonstration plan.

**Decisions:** Decide which claims are proven without Spring, which need real PostgreSQL, which need real RabbitMQ, and which are only manual observations. Decide how asynchronous tests wait without arbitrary sleeps.

**Directional hints:** Test outcomes and invariants, not every collaborator call. A mock can verify that a publisher method was invoked, but only a real broker can prove routing. Keep the suite small enough that a reviewer will run it.

**Relevant blog post or concepts:** `posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md`, `posts/series-2-postgres/05-testing-postgresql.md`, and `posts/series-3-rabbitmq/06-operational-testing.md`.

**Verification evidence:** The test report contains state-transition cases, invalid input and ownership cases, one complete patient journey, and at least one real local dependency assertion. The manual path can be followed by a new reviewer without reading application internals first.

**Exit criteria:** The Foundation behavior is reproducible and the tests make clear what they do not prove about concurrency or delivery.

### Milestone 7: Measure a baseline and document known failure windows

**Objective:** Turn deliberate limitations into interview-quality evidence and establish a baseline for Exercise 2.

**What to implement:** Measure a small local baseline for submission and status reads, record the environment and sample size, and write a failure matrix covering database rollback, commit-before-publish failure, unroutable publication, consumer crash, duplicate delivery, and inventory races. State what the status `GET` proves and what it cannot repair.

**Decisions:** Decide which failure windows can be safely simulated locally; decide what counts as a failed patient outcome; decide which limitation is the first Exercise 2 handoff; decide how to report a partial or unavailable measurement honestly.

**Directional hints:** A baseline is a reference point, not a capacity claim. Prefer a controlled pause, broker inspection, or test seam over destructive commands. Distinguish a publish attempt from broker acceptance, routing, consumer processing, and business completion even though Foundation does not yet prove all four.

**Relevant blog post or concepts:** `posts/series-3-rabbitmq/02-publisher-confirms-outbox.md`, `posts/series-2-postgres/06-showcase-concurrent-persistence.md`, and `posts/series-4-product-sse/04-time-box-scoping.md`.

**Verification evidence:** Produce a short baseline table, a failure matrix with expected recovery or operator action, and a handoff list naming direct publish, basic inventory, unbounded retry behavior, missing dead letters, missing consumer idempotency, and missing SSE.

**Exit criteria:** A reviewer can say exactly what Foundation proves, reproduce the happy path, and predict where Exercise 2 or Exercise 3 must change the design.

## Step-by-Step Exercise Guide

### Step 1: Define the prescription state machine and domain outcomes

**Objective:** Make the workflow and its expected failures explicit before persistence or messaging choices obscure them.

**What to implement:** Inspect the challenge and existing repository conventions. Decide the state graph, inventory timing, reservation lifecycle, actors, and ownership model. Implement the domain representation and named transition decisions. Test legal and illegal transitions without Spring. Measure nothing yet, but document the state vocabulary and its patient meaning.

**Decisions:** Choose enum versus sealed state representation; choose nullable lookup versus sealed command outcome; decide which technical failures remain exceptions; decide whether `SUBMITTED` is an observable state or only a history event.

**Directional hints:** The smallest complete graph is easier to defend than a generic workflow engine. Make illegal operations return deliberate outcomes. Keep local type safety separate from shared database safety.

**Relevant blog post or concepts:** `posts/series-1-kotlin/03-state-machines-with-sealed-types.md`, `posts/series-1-kotlin/02-nullability-results-domain-errors.md`, and `posts/series-1-kotlin/05-showcase-kotlin-prescription-domain.md`.

**Verification evidence:** A state/command table, a domain test set, and a written explanation of why the Kotlin model does not by itself protect concurrent requests.

**Exit criteria:** No endpoint, worker, or fixture needs to invent a new state or silently bypass a transition rule.

### Step 2: Define patient and staff API contracts

**Objective:** Prioritize the patient journey and keep staff interfaces intentionally small.

**What to implement:** Inspect the state table and challenge actors. Decide and document the request and response fields for submission, status, pharmacist queue, approval, rejection, packaging completion, and fulfillment. Implement the transport validation and outcome-to-HTTP mapping. Test owner access, missing records, invalid quantities, and invalid transitions. Measure the number of endpoints and document why no frontend is needed.

**Decisions:** Choose the patient identity stand-in; choose the submission-key behavior; choose whether the status timeline is included in the status view; choose the minimum staff queue limit and response shape.

**Directional hints:** Keep patient reads fact-oriented and staff endpoints command-oriented. Do not let clients send arbitrary next states. Return a conflict for a state race rather than a misleading success.

**Relevant blog post or concepts:** `posts/series-4-product-sse/01-patient-first-api.md`, `posts/series-4-product-sse/04-time-box-scoping.md`, and `posts/series-1-kotlin/01-kotlin-for-java-developers.md`.

**Verification evidence:** A contract document or request collection, API tests for the main error mappings, and a demonstration that one patient cannot read another patient's prescription.

**Exit criteria:** The patient can submit and read status, staff can issue every needed command, and workers have a clearly separate future message contract.

### Step 3: Create the PostgreSQL schema and seed data

**Objective:** Give the workflow a reproducible, constrained local persistence model.

**What to implement:** Inspect the chosen API fields and state history needs. Decide table ownership, keys, foreign keys, constraints, timestamps, and seed identifiers. Implement versioned migrations and deterministic medication/inventory seeds. Test a clean migration, constraint failures, and seed repeatability. Measure migration and seed time only if useful, and document the schema assumptions.

**Decisions:** Choose whether to store a reservation audit row; choose the initial indexes based on actual status and queue reads; choose cleanup boundaries for local tests; choose whether patient identifiers are external references rather than a new patient service.

**Directional hints:** Keep current status separate from history. Do not introduce batches, warehouses, insurance, or a full medication catalog. Name the read-check-write inventory limitation next to the schema rather than hiding it in a later exercise.

**Relevant blog post or concepts:** `posts/series-2-postgres/01-schema-design.md`, `posts/series-2-postgres/05-testing-postgresql.md`, and `posts/series-2-postgres/06-showcase-concurrent-persistence.md`.

**Verification evidence:** A clean-database migration run, seeded medication list, representative row inspection, and assertions for positive quantities, legal statuses, foreign keys, and chosen uniqueness rules.

**Exit criteria:** The service can start against an empty local PostgreSQL database without manual schema edits.

### Step 4: Implement the straightforward workflow

**Objective:** Prove a complete, readable patient-to-fulfillment path before tuning.

**What to implement:** Inspect the API and persistence contracts. Decide the order of validation, inventory handling, prescription writes, history writes, and direct publication. Implement the application service and adapters. Test submission, status lookup, approval, rejection, packaging, ready-for-collection, fulfillment, and retry of a patient request. Measure a simple end-to-end elapsed time and document the direct write/publish boundary.

**Decisions:** Choose whether a rejected reservation is restored immediately; choose how a repeated submission key behaves; choose which command owns the transaction; choose how a broker outage is surfaced without claiming the state change completed asynchronously.

**Directional hints:** Keep network calls out of a database transaction when possible, but do not pretend the direct publish is atomic with the commit. Use explicit statements in Kotlin where transaction ownership matters. A `val` does not make a database entity immutable, and a read-only list does not replace a database constraint.

**Relevant blog post or concepts:** `posts/series-1-kotlin/05-showcase-kotlin-prescription-domain.md`, `posts/series-2-postgres/02-transactions-isolation.md`, and `posts/series-4-product-sse/05-showcase-patient-notification.md`.

**Verification evidence:** A clean end-to-end run shows each status through the owner-scoped `GET`, inventory changes once on the happy path, rejection returns a reason, and illegal actions are rejected.

**Exit criteria:** A new reviewer can follow the patient journey without editing the database or invoking an undocumented internal method.

### Step 5: Add a basic RabbitMQ topology

**Objective:** Demonstrate asynchronous coordination without prematurely building the reliability architecture.

**What to implement:** Inspect the workflow transition that needs asynchronous work. Decide exchange type, queue name, routing key, message meaning, consumer role, and basic acknowledgement behavior. Implement the local topology and consumer. Test the route with a real broker, measure the delay only as a local observation, and document the direct publish and consumer crash windows.

**Decisions:** Decide whether packaging begins when the message is consumed or when packaging completion is recorded; decide whether fulfillment is a second work message or a staff command; decide how an unroutable message is noticed.

**Directional hints:** Work commands belong on a competing-consumer queue; patient facts should not be sent to a queue per SSE connection. Keep the topology inspectable and avoid a microservice decomposition that adds no product value.

**Relevant blog post or concepts:** `posts/series-3-rabbitmq/01-amqp-topology.md`, `posts/series-3-rabbitmq/03-ack-prefetch-concurrency.md`, and `posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md`.

**Verification evidence:** Broker topology inspection, a message-to-consumer trace, and a happy-path test that cannot pass if RabbitMQ is bypassed.

**Exit criteria:** The asynchronous step is real, documented, and intentionally not described as durable, retryable, or idempotent.

### Step 6: Add unit, API, and happy-path evidence

**Objective:** Make the Foundation result reviewable and runnable within the time box.

**What to implement:** Inspect the risk list and choose the smallest credible tests. Implement domain tests, API tests, a real PostgreSQL migration/seed check, a real RabbitMQ route check, and one complete workflow test. Measure test duration and manual setup friction. Document which assertions come from units, real dependencies, or observation.

**Decisions:** Decide whether to use Testcontainers or the repository's Docker Compose services for each test; decide how asynchronous tests wait; decide which mocks are acceptable for transport mapping and which claims require real infrastructure.

**Directional hints:** A test naming the reason for an assertion is more useful than a generic "workflow works" name. Do not spend the time box mocking every listener method. The happy path and the state machine are the product proof at this level.

**Relevant blog post or concepts:** `posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md`, `posts/series-2-postgres/05-testing-postgresql.md`, and `posts/series-3-rabbitmq/06-operational-testing.md`.

**Verification evidence:** Test output, a repeatable clean-stack demonstration, fixture notes, and a map from each claimed behavior to its evidence source.

**Exit criteria:** The evidence suite is small enough to run in a review and honest enough to show its concurrency and delivery gaps.

### Step 7: Measure a baseline and document known failure windows

**Objective:** End Foundation with an honest baseline and a clear handoff.

**What to implement:** Inspect the local runtime, seed size, and test path. Decide a repeatable sample for submission and status reads. Implement only safe observation or test seams for failure timing. Test or reason through the direct publish, consumer crash, inventory race, and duplicate message windows. Measure elapsed times, success/error counts, and any visible queue delay. Document limitations, evidence, and the first change required in Exercise 2.

**Decisions:** Decide whether a failure is demonstrated, simulated, or reasoned about; decide how to avoid confusing a database commit with message publication; decide which claims will be carried forward unchanged.

**Directional hints:** Never use a successful demo to imply recovery. State that the status `GET` can report the last committed truth even when downstream work is stalled, but it cannot make a missing message appear. Preserve the limitations so the next exercise has something concrete to reproduce.

**Relevant blog post or concepts:** `posts/series-3-rabbitmq/02-publisher-confirms-outbox.md`, `posts/series-2-postgres/03-inventory-reservation.md`, `posts/series-2-postgres/06-showcase-concurrent-persistence.md`, and `posts/series-4-product-sse/04-time-box-scoping.md`.

**Verification evidence:** A baseline table, failure matrix, limitation record, and a handoff note that distinguishes database race safety, message-delivery reliability, and realtime notifications.

**Exit criteria:** Foundation is complete as a deliberately limited product slice, not an unfinished production system disguised by optimistic wording.

## Required Decisions

- Which state transitions are legal, who may issue them, and what the patient sees at each state.
- Whether inventory is checked or reserved at submission, how rejection releases it, and why fulfillment does not decrement it again.
- Whether the submission key is required, how a repeated patient request is handled, and how ownership is represented locally.
- Which fields appear in the patient status `GET`, which errors are `404`, `409`, `400`, or technical failures, and why the endpoint is the correctness baseline.
- Which application service owns each database transaction and how Spring proxy-based transaction entry is kept visible.
- Why the direct database-write and publish path is acceptable only as a learning boundary, and exactly what happens if the process fails between commit and publish.
- What the basic RabbitMQ exchange, queue, routing key, and consumer mean, and why no message is described as exactly-once.
- Why Foundation has no publisher confirms, transactional outbox, bounded retries, dead-letter handling, idempotent consumers, or SSE.
- Which Kotlin values are immutable, which values may be nullable, which business alternatives use sealed outcomes, and where blocking I/O runs.
- Which tests require real PostgreSQL or RabbitMQ and which claims remain only measured, manually observed, or reasoned about.

## Tests and Evidence

Produce the following evidence without embedding application implementation in this exercise description:

- Focused Kotlin tests for legal transitions, illegal transitions, invalid quantities, missing records, ownership failure, and expected domain outcomes.
- API tests for submission, owner-scoped status lookup, missing prescription, invalid input, approval/rejection conflicts, packaging completion, and fulfillment.
- A clean local PostgreSQL migration and deterministic seed check.
- A real RabbitMQ topology and message-route check for the required asynchronous step.
- One complete submission-to-fulfillment happy-path test that checks the status `GET` after each meaningful transition.
- One rejection path showing the documented inventory release behavior.
- A manual demonstration plan suitable for a reviewer who has not read the application source.
- A baseline record with machine/runtime context, seed size, sample count, elapsed time, success/error counts, and known setup costs.
- A failure matrix covering database rollback, database commit followed by publish failure, unroutable publication, consumer crash before acknowledgement, duplicate message delivery, and concurrent inventory claims.

Label every result as one of: unit evidence, API evidence, real PostgreSQL evidence, real RabbitMQ evidence, manual observation, or design reasoning. Foundation does not prove safe concurrent inventory, reliable publication, bounded retry, dead-letter recovery, consumer idempotency, or SSE correctness.

## Bottleneck & Reflection Questions

- What exactly can happen between the PostgreSQL commit and the RabbitMQ publish, and how would a patient discover the stall?
- What does a basic publish call tell you about broker acceptance, routing, consumer processing, and business completion?
- If a consumer changes the database and crashes before acknowledgement, what happens to the message and to the business effect?
- Can two simultaneous submissions claim the same last inventory unit, and what evidence would distinguish a clean conflict from a constraint failure?
- Why is a simple database check constraint useful but insufficient as the inventory algorithm?
- Which status is authoritative for the patient, and why should the timeline not replace the current status row?
- Which parts of the API are patient facts and which are staff commands?
- Why is a simulated staff client better product judgment than a rushed internal UI in this time box?
- What does Kotlin's `val` guarantee, what does a nullable lookup mean, and why are sealed outcomes clearer than a generic boolean?
- Where would a Spring self-invocation fail to create the transaction you thought you had?
- Why are blocking PostgreSQL and RabbitMQ clients acceptable on ordinary worker threads here, and when would a coroutine dispatcher decision become important?
- Which Foundation limitation should Exercise 2 close first, and which should remain deferred until Exercise 3?

## Success Criteria

- The repository's documented local dependencies start without cloud or managed infrastructure.
- A patient can submit a prescription with valid medication lines and retrieve its own current status.
- A different patient cannot use the status endpoint to observe the prescription.
- A pharmacist can approve or reject an awaiting prescription with deliberate outcomes.
- RabbitMQ visibly coordinates at least one packaging workflow step.
- Packaging, collection readiness, and fulfillment can be completed through documented simulated staff actions.
- Inventory behaves correctly for the demonstrated happy path, and the concurrency limitation is explicit.
- Migrations, seed data, domain tests, API tests, and one full happy-path test are reproducible.
- The patient status `GET` is identified as the correctness baseline.
- The direct publish window, missing bounded retries, missing dead letters, missing idempotent consumers, and missing SSE are documented rather than silently assumed away.
- No claim of exactly-once delivery or production capacity is made.

## Interview Defense Checklist

- Explain the patient journey first and the smallest API that supports it.
- Draw the state machine and show why each illegal transition is rejected.
- Explain why the status `GET` is the authoritative baseline and why SSE is intentionally absent.
- Describe the chosen inventory timing and the exact basic concurrency limitation.
- Identify the database transaction boundary and the Spring proxy caveat.
- Explain the direct database-write and publish crash window without calling it atomic.
- Distinguish an AMQP work queue from a patient notification stream.
- State what RabbitMQ proves in the happy-path test and what it does not prove about redelivery.
- Explain the Kotlin choices: nullability, immutable values, sealed outcomes, state modeling, blocking I/O, and testing idioms.
- Name the first PostgreSQL improvement for Exercise 2 and the RabbitMQ/SSE improvements intentionally handed to Exercise 3.
