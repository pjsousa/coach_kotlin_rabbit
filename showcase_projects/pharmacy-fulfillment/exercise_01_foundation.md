# Foundation Pharmacy Prescription Fulfillment System - Exercise

## Objective

Build a small, readable Kotlin/Spring Boot service that takes a prescription through the complete patient journey:

1. The patient submits a prescription.
2. The system validates medication and available inventory.
3. A simulated pharmacist approves or rejects it.
4. A simulated packager completes packaging.
5. The prescription becomes ready for collection and can be fulfilled.
6. The patient reads the current status through an API.

The goal is a correct, demonstrable foundation, not a production-ready messaging platform.

## Starting Point

Start from an empty project. Use Kotlin, Spring Boot, PostgreSQL, RabbitMQ, and Docker-based local dependencies. Use the Kotlin foundation posts in `posts/series-1-kotlin/` before beginning.

Do not build a real pharmacist or packager UI. Use curl, a Postman collection, or small simulated clients. A patient-facing status `GET` endpoint is required. SSE is intentionally deferred to Exercise 3.

## Background and Motivation

This is the two-hour submission path from the original challenge. It should demonstrate product thinking and a complete workflow before adding sophisticated reliability infrastructure.

The implementation deliberately starts with a simple publish path. You must observe and document what can go wrong if a database write and a RabbitMQ publish are not one atomic operation. Do not silently solve that problem in this exercise; Exercise 2 is built around it.

## System Specification

### Functional requirements

- Create a prescription for a patient with one or more medication lines.
- Reject unknown medications and non-positive quantities.
- Verify or reserve available inventory.
- Expose the current prescription status to the owning patient.
- Allow a pharmacist action to approve or reject a prescription awaiting approval.
- Route an approved prescription to packaging through RabbitMQ.
- Allow a packager action to complete packaging.
- Allow a fulfillment action to make the prescription ready or fulfilled according to your documented state model.
- Return clear outcomes for missing prescriptions and invalid state transitions.

### Minimum API surface

Choose the exact request and response shapes, but provide equivalents of:

- `POST /prescriptions`
- `GET /prescriptions/{id}`
- pharmacist approval and rejection actions;
- packager completion action;
- fulfillment or ready-for-collection action.

Document the API with curl examples or a Postman collection.

### Suggested states

You may use a different vocabulary if it is internally consistent:

```text
SUBMITTED -> AWAITING_APPROVAL -> APPROVED -> PACKAGING -> READY -> FULFILLED
                              \-> REJECTED
```

### Persistence expectations

Persist at least:

- medication master data;
- prescription identity, patient identity/number, and current status;
- prescription medication lines and quantities;
- inventory quantity.

Use PostgreSQL migrations or a documented schema setup. Add primary keys, foreign keys, and constraints that protect obvious invalid data.

### RabbitMQ expectations

RabbitMQ must participate in the workflow. Define and document:

- at least one exchange;
- a pharmacist or approval-related queue if your flow uses an asynchronous approval worker;
- a packaging queue;
- routing keys and message intent;
- what a message means and which component consumes it.

Use a simple topology in this exercise. Do not introduce a microservice fleet.

### Non-functional requirements

- Run locally with documented commands.
- Keep the service understandable by a reviewer unfamiliar with the code.
- Make the happy path observable through logs and API responses.
- Do not claim exactly-once delivery or production-grade failure recovery.

## Time-box Guidance

### First 30 minutes

- Write the README assumptions and state diagram.
- Create the project and local PostgreSQL/RabbitMQ dependencies.
- Decide the API and persistence model.

### Next 60 minutes

- Implement prescription creation, status lookup, and state transitions.
- Add the basic RabbitMQ workflow.
- Seed the required medication inventory.

### Final 30 minutes

- Run the end-to-end flow.
- Add focused state-transition tests and one integration or API flow test.
- Document known limitations and what Exercise 2 will improve.

If the infrastructure setup consumes too much time, preserve the patient workflow and explain the missing parts rather than adding an incomplete UI.

## Step-by-Step Exercise Guide

### 1. Write assumptions and the state machine

Define:

- whether inventory is reserved at submission or approval;
- whether rejected prescriptions release reserved inventory;
- the difference between `READY` and `FULFILLED`;
- who is allowed to invoke pharmacist, packager, and fulfillment actions;
- how a patient identifies their prescription without exposing another patient's data.

Key decision: choose the smallest state model that covers the challenge. Do not add audit, payment, notification, or insurance workflows yet.

Study: `posts/series-1-kotlin/03-state-machines-with-sealed-types.md`.

Verify: draw at least one legal and one illegal transition for every action.

### 2. Model the Kotlin domain

Create clear types for prescription identity, medication lines, status, and command outcomes. Prefer immutable values and explicit outcomes for expected business failures.

Key decision: decide where nullable values end and domain outcomes begin. Avoid a generic mutable object that allows any caller to assign any status.

Study: `posts/series-1-kotlin/01-kotlin-for-java-developers.md` and `02-nullability-results-domain-errors.md`.

Verify: unit-test valid approval, invalid approval, missing order, and invalid quantity cases without starting Spring.

### 3. Create PostgreSQL persistence

Create migrations and repository boundaries for medication, inventory, prescription, and prescription lines. Add constraints and indexes for the lookups you actually use.

Key decision: state how inventory is checked and updated. It is acceptable for this exercise to use a straightforward implementation, but document the concurrency limitation you have not yet solved.

Study: the PostgreSQL series in `artifacts/blog-plan.md`, especially schema and transaction posts.

Verify: run migrations from an empty database and load the pharmacy medication seed data.

### 4. Implement the patient API

Implement prescription submission and status lookup. Return a patient-safe representation rather than leaking internal database or message details.

Key decision: define how missing prescriptions, invalid input, and invalid state transitions map to HTTP responses.

Study: the Product Workflow series in `artifacts/blog-plan.md`.

Verify: demonstrate submission, status lookup, invalid input, and lookup of a missing ID with curl or Postman.

### 5. Implement simulated staff actions

Provide a simple way for a pharmacist and packager to act. These may be API commands or small command-line clients. Keep their interfaces intentionally inconvenient compared with the patient flow.

Key decision: choose whether approval is synchronous at first and which subsequent action uses RabbitMQ. The system must visibly use RabbitMQ for workflow coordination.

Verify: drive an approved order into packaging and then fulfillment using the documented commands.

### 6. Add the basic RabbitMQ workflow

Define a small exchange and queue topology. Publish a message when the selected workflow transition requires downstream work. Consume it and apply the next state transition.

Key decision: document what happens if the database write succeeds but publishing fails, or if a consumer crashes before acknowledging. Do not implement the complete answer yet.

Study: `artifacts/blog-plan.md`, Series 3, AMQP topology.

Verify: inspect the broker topology and show a message moving from publication to consumption.

### 7. Add the minimum evidence

Include:

- unit tests for state transitions;
- a submission-to-fulfillment API or end-to-end test;
- a README with run commands and assumptions;
- a short limitations section naming the direct-publish failure window and concurrency risks.

Study: `posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md`.

Verify: a new reviewer can start dependencies, run tests, and observe the patient journey without reading implementation details first.

## Required Decisions

Document these decisions in the README or an ADR:

- Why inventory is reserved at your chosen point.
- Why the selected RabbitMQ exchange and queues are sufficient for this phase.
- Why the patient status `GET` is the correctness baseline.
- Why there is no real pharmacist UI.
- Which failure modes are known but deferred.
- Which parts of the implementation are intentionally simple because of the time box.

## Tests and Evidence

Minimum evidence:

- state transition tests;
- invalid input and missing order tests;
- one full happy-path test;
- a reproducible seed for medications and inventory;
- a short manual demonstration using curl or Postman;
- an explanation of how you would reproduce the direct-publish failure window.

Do not spend the exercise mocking every framework call. Prove the user journey first.

## Bottleneck and Reflection Questions

- What happens if PostgreSQL commits but RabbitMQ publication fails?
- What happens if the consumer crashes after updating the database but before acknowledgement?
- Can two simultaneous submissions consume the last unit of a medication?
- Is the patient status endpoint reading an authoritative state?
- Which endpoint or queue would you remove if the two-hour limit became one hour?
- What would you add first in Exercise 2, and why?

## Success Criteria

- A fresh checkout can start local dependencies and the service using the README.
- A patient can submit a prescription and retrieve its status.
- The full workflow can be demonstrated with simulated staff actions.
- RabbitMQ visibly coordinates at least one workflow step.
- Invalid transitions and missing records produce deliberate outcomes.
- Tests cover the state machine and the happy path.
- The README states assumptions, limitations, and next steps honestly.

## Interview Defense Checklist

Be ready to explain:

- why the design is intentionally small;
- why Kotlin types are used where they are;
- what PostgreSQL currently guarantees and does not guarantee;
- what RabbitMQ currently guarantees and does not guarantee;
- why SSE was deferred;
- how Exercise 2 will close the direct-publish, duplicate, retry, and concurrency gaps.
