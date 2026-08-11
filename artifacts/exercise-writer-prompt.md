# Exercise Writer Prompt: Pharmacy Fulfillment Progression

## Context

You are acting as a technical exercise designer for a Product Engineer interview preparation track. The candidate is a Lead Backend Engineer with strong Java/backend experience and no prior Kotlin experience. The target stack is Kotlin, Spring Boot, RabbitMQ, PostgreSQL, REST, Docker, and Server-Sent Events for the advanced phase. The domain is a pharmacy prescription fulfillment product.

Read these sources before writing:

- `artifacts/coach-assessment.md` for the candidate's diagnostic gaps and strengths.
- `artifacts/blog-plan.md` for the full curriculum and knowledge blocks.
- `posts/series-1-kotlin/` for the written Kotlin foundation material.
- `Product Engineer_ Tech Challenge.md` for the original requirements and evaluation criteria.

The candidate has 30 days at two hours per day, but the submitted challenge itself is expected to take approximately two to five hours. The exercise progression must teach deeply while preserving a credible time-boxed submission path.

## Product

Build a **Pharmacy Prescription Fulfillment System** covering:

1. A patient submits a prescription.
2. The system verifies or reserves medication inventory.
3. A pharmacist approves or rejects the prescription.
4. An approved prescription is routed to packaging.
5. A packager completes fulfillment.
6. The patient can track status until their number is called.

The patient is the primary user. Pharmacist and packager interfaces may be simulated clients or simple API consumers. The implementation must use Kotlin and RabbitMQ and should use PostgreSQL for persistence. Local Docker-based dependencies are preferred.

## North-Star Metrics

The progression has cumulative targets:

- **Exercise 1:** Complete and demonstrate the end-to-end workflow correctly for a single order, with a patient status `GET` endpoint.
- **Exercise 2:** Sustain approximately 10 prescription workflow submissions per second in a local environment without negative inventory or unsafe duplicate side effects.
- **Exercise 3:** Preserve the Exercise 2 behavior while targeting p95 patient status reads below 250 ms and proving SSE ordering, reconnect replay, and patient isolation.

These are engineering targets for local tests, not production capacity claims.

## Progression Rules

The three exercises are cumulative. Each exercise starts from the previous exercise's code and improves it. Do not create three unrelated applications.

### Exercise 1: Foundation

Build a straightforward, readable implementation:

- Kotlin domain model and explicit prescription states.
- Spring Boot REST API for submission, status lookup, and simulated pharmacist/packager actions.
- PostgreSQL tables for prescriptions, medication lines, medication inventory, and current status.
- RabbitMQ used for the workflow queues with a simple topology.
- Patient status available through `GET /prescriptions/{id}`.
- A simple happy-path end-to-end demonstration with curl or a Postman collection.
- Unit tests for state transitions and at least one end-to-end workflow test.

Deliberate limitations:

- A direct database-write plus publish path may expose the dual-write failure window.
- No complete outbox, retry, DLQ, or inbox implementation yet.
- SSE is not required in this phase.
- No real staff UI; simulated clients are acceptable.

The exercise must ask the candidate to observe and document where this implementation breaks under failure or concurrency. Do not hide the limitation or provide its solution.

### Exercise 2: Reliability

Start from Exercise 1 and preserve its API and product behavior where practical. Improve reliability and concurrency without changing the product into a collection of unrelated services:

- Conditional PostgreSQL state transitions.
- Atomic inventory reservation that cannot create negative stock.
- Transactional outbox for workflow events.
- Relay behavior and publisher confirms.
- Durable RabbitMQ topology with explicit routing keys.
- Manual consumer acknowledgements and acknowledgement after durable business effects.
- Bounded retry handling and a dead-letter path.
- Stable event IDs and an inbox or equivalent uniqueness constraint for idempotent consumers.
- Integration tests against real PostgreSQL and RabbitMQ containers.
- Failure tests for relay publication, consumer crashes, redelivery, and duplicate processing.

Do not add SSE yet unless it is needed to verify that the existing status endpoint remains correct. Keep the architectural shape recognizable so the candidate can explain what changed and why.

### Exercise 3: Production Showcase

Start from the reliable Exercise 2 system and add production-facing capabilities:

- SSE patient status updates as a fan-out notification path, not as competing RabbitMQ work consumers.
- Stable SSE event IDs and monotonic per-prescription sequence numbers.
- Reconnect support using `Last-Event-ID`.
- Replay or catch-up behavior so a reconnecting patient does not silently miss status changes.
- Authorization and filtering so a patient cannot see another patient's events.
- A separate operational or god-mode view that does not steal worker messages.
- Tests for ordering, reconnect, missed events, cross-patient leakage, and concurrent patients.
- Structured logs, useful metrics, correlation IDs, and a documented failure/recovery procedure.
- A concise architecture decision record covering simplicity, delivery semantics, ordering, persistence, and deferred work.

The candidate should be able to defend why `GET /prescriptions/{id}` remains the authoritative correctness baseline even after SSE is added.

## Required Exercise File Format

Create exactly these files under `showcase_projects/pharmacy-fulfillment/`:

- `exercise_01_foundation.md`
- `exercise_02_reliability.md`
- `exercise_03_production.md`

Each file must contain:

```text
# [Level] Pharmacy Prescription Fulfillment System - Exercise

## Objective
## Starting Point
## Background and Motivation
## System Specification
## Time-box Guidance
## Step-by-Step Exercise Guide
## Required Decisions
## Tests and Evidence
## Bottleneck and Reflection Questions
## Success Criteria
## Interview Defense Checklist
```

For every implementation step, include:

- what to implement;
- what decision the candidate must make;
- a directional hint, not a solution;
- which blog post or concept to study;
- how the candidate can verify the behavior.

## Exercise-Specific Requirements

### Foundation file

Include the minimum API contract, state transition table, PostgreSQL schema expectations, RabbitMQ basic topology, sample commands, test expectations, and a deliberate failure/concurrency investigation. Make the exercise achievable as a clean two-hour submission if the candidate stops after the core slice.

### Reliability file

Start explicitly from the Foundation implementation. Require the candidate to identify the direct-publish failure window before implementing the outbox. Require an explanation of publisher confirms, manual acknowledgements, redelivery, retry classification, DLQ behavior, and idempotency. Include a small load or concurrency experiment tied to the 10 submissions/second target.

### Production file

Start explicitly from the Reliability implementation. Require SSE event identity, reconnect handling, replay/catch-up, ordering, authorization, and an isolated god-mode view. Require tests that prove no lost or cross-patient events. Require concise observability and an operational README section. Keep the exercise challenging but achievable as a focused advanced progression, not a demand for a complete enterprise platform.

## Constraints

- Do not provide solution code.
- Do not write the application itself.
- Do not invent cloud dependencies; use Docker/local services.
- Do not require a full frontend. A minimal generated or hand-written UI is optional and must come after backend correctness.
- Do not claim exactly-once delivery.
- Do not use SSE as a RabbitMQ competing-consumer shortcut.
- Do not introduce microservices unless the exercise explicitly asks the candidate to justify a boundary.
- Keep Kotlin teaching explicit for an experienced Java engineer, including what is idiomatic and what is merely Java translated into Kotlin.
- Keep PostgreSQL and RabbitMQ semantics concrete enough to test locally.
- Tie every exercise to the original challenge's patient experience, simplicity, system design, and failure handling criteria.

## Output Rule

Write only the three exercise Markdown files. Do not write implementation code or additional exercises. After writing them, report the files created and any assumptions that remain open.
