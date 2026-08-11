# Scoping a Two-Hour Versus Five-Hour Challenge

The challenge gives you a range, not a deadline: "approximately two to five hours," and then the sentence that should anchor every scoping decision: *"we would rather see clean, working code than extra features. If you run out of time, submit what you have and note what you would have done next."* The most important product decision in this exercise is not which queue topology to use or how to frame SSE events. It is deciding, before you write a line of code, what the two-hour version must be — and what the five-hour version buys you on top of it.

This post is that decision, made explicit: the two-hour slice is a correct, complete patient journey with no realtime and no reliability machinery; the five-hour version layers on retries, dead-lettering, and SSE proof. Everything else is deferred and documented, and the generated UI waits for backend proof — because in this challenge, a UI is the most expensive way to demonstrate the attribute least being scored.

## Two Submissions, Not Two Endpoints of One

The trap is treating 2 hours and 5 hours as different budgets for the same plan. They are different plans. The 2-hour submission that omits half of a 5-hour plan reads as unfinished; the 2-hour submission that is a complete, defensible slice reads as product judgment. The 5-hour submission that is a polished version of the 2-hour slice with SSE and reliability *proven* reads as exactly what a Product Engineer role is asking for.

| Capability | 2-hour slice | 5-hour version |
| --- | --- | --- |
| Prescription submission with idempotency | Yes | Yes |
| Status GET as the source of truth | Yes | Yes |
| Explicit state machine, atomic transitions | Yes | Yes |
| RabbitMQ topology with the workflow queues | Minimal, honest | Full: confirms, manual acks, retries, DLQ |
| Outbox relay + publisher confirms | No — documented gap | Yes |
| Idempotent consumers (inbox) | No | Yes |
| Retries, dead letters, poison isolation | No | Yes |
| SSE with IDs, replay, isolation | No | Yes |
| Tests | End-to-end happy path + state transitions | + failure, redelivery, reconnect, isolation |
| README with decisions and limitations | Yes, including the gaps | Yes, including ADRs |

Note the first column says "No — documented gap," not "No." The difference is the entire point of this post.

## The Two-Hour Slice: Correct Before Fancy

The coach assessment's build progression is explicit: foundation first (REST, PostgreSQL, explicit transitions, simulated workers, a correct status GET), then reliability, then SSE as an advanced showcase. The two-hour slice is the foundation, with RabbitMQ present in its simplest honest form because the challenge mandates it.

The slice, in order:

1. **`POST /prescriptions`** with a client idempotency key and a unique constraint, so retried submissions cannot create duplicates.
2. **`GET /prescriptions/{id}`** reading persistent state directly — the correctness baseline from series 4's first post. If this endpoint is right, the product is right.
3. **The flat state machine** (`SUBMITTED → APPROVED → PACKAGING → READY → FULFILLED`, plus `REJECTED`), enforced by conditional updates so illegal transitions return `409`.
4. **A minimal RabbitMQ topology** moving work from approval to packaging, with a single queue and manual acknowledgements at most.
5. **Simulated staff** as scripts that consume the pharmacist queue and issue decisions — not a UI, not a dashboard.
6. **One end-to-end test** that submits, approves, packages, fulfills, and asserts the patient's status at each step.
7. **A README** that states the slice, the invariants, and exactly what was not built.

What is deliberately absent: the outbox, publisher confirms, retry and dead-letter topology, the inbox, SSE, and any UI. The two-hour submission's honest statement is: *"Messages can be lost between the database commit and broker publish; retries are unbounded requeues; the patient polls."* That statement, written in the README, is worth more than three hours of half-built reliability machinery — because the challenge explicitly asks for working code over features.

## What the Extra Three Hours Buy

The five-hour version spends its budget in risk order, not in feature order. Every item below closes a failure window or proves an advanced claim; nothing below is cosmetic:

1. **Outbox relay + publisher confirms** (series 3, post 2): closes the dual-write window the two-hour slice documented. The database commit and the broker publish become one transactionally reliable flow.
2. **Manual acknowledgements, prefetch, idempotent consumers** (series 3, posts 3 and 5): ack after durable business effect, dedupe redeliveries with an inbox table, bound prefetch so one poison message does not stall a queue.
3. **Retries and dead letters** (series 3, post 4): transient failures retry with TTL, permanent failures dead-letter, poison messages are inspectable and replayable.
4. **SSE with event IDs, replay, ordering, and patient isolation** (series 4, post 2): the realtime layer as a delivery optimization over the GET baseline, never a second source of truth.
5. **Tests that prove the claims** (series 3, post 6; series 4, post 3): broker integration tests for redelivery and DLQ, SSE tests for reconnect via `Last-Event-ID`, ordering, and no cross-patient leakage.
6. **Documentation of the version**: what is now proven, what is still not (exactly-once is still impossible; ordering is per-prescription, not global).

A rough hour budget for that ordering:

| Item | Budget |
| --- | --- |
| Foundation slice + e2e test + README | 2h |
| Outbox, relay, publisher confirms | 45m |
| Manual ack, prefetch, inbox idempotency | 45m |
| Retry/DLQ topology and its tests | 45m |
| SSE endpoint, replay, isolation + tests | 1h |
| ADRs, diagrams, final walkthrough notes | 15m |

If the SSE hour does not fit, the submission is still the four-hour version: reliable workflow, no realtime, and a README that says exactly what the next hour would have built. The evaluator's instruction *"submit what you have and note what you would have done next"* turns "out of time" into a scoping decision instead of a failure.

## What to Defer — and Defend the Deferral

The deferral list from the coach assessment is a scope defense, not a laziness list. For this challenge: a full frontend design system, Kubernetes deployment, cloud-specific GCP deployment, broad microservice decomposition, exactly-once claims, advanced PostgreSQL tuning unrelated to the workload, and distributed tracing. Add to that from the challenge itself: authentication beyond documented headers, staff dashboards, and resource versioning.

The rule that justifies every entry: **defer anything whose absence does not break the patient journey and does not hide a correctness bug.** Auth headers with a documented replacement slot do not break the journey. An outbox gap does hide a correctness bug — so it moves to the top of the five-hour list, not into the deferral pile. Kubernetes does not break the journey, and `docker compose up` proves more in an interview than a Helm chart ever will. The evaluator scores patient experience, simplicity, system design, and failure handling. Every hour spent on a deferred item is an hour not spent on one of those four.

## Documenting Limitations: The README Is Scope

The challenge asks you to *"document your system design and technology decisions"* — and the coach assessment is blunt that failure handling and tradeoff explanation are assessed explicitly. The README is where scope becomes evidence. Three sections carry the weight:

**"What is included."** The slice and its invariants: the state machine, the correctness baseline, the idempotency contract, the test commands.

**"Known limitations and crash windows."** Written before the feature, not as an apology after. The two-hour version states: the commit-publish window can lose an event; redelivery can process an event twice without an inbox; the patient sees updates by polling; simulated auth headers stand in for identity. Each limitation is one sentence with the one-line fix that would close it — because in the walkthrough, the interviewer's next question is always "how would you fix that?", and the README has already rehearsed it.

**"What I would do next."** Ordered by risk: outbox first, retries and DLQ second, SSE third. This section is a deliverable the challenge explicitly requests. Writing it before the code is written is what makes the two-hour slice feel complete rather than truncated.

The discipline is to never claim what is not proven: no exactly-once, no "real-time" without an SSE implementation, no "fault-tolerant" without a test that proves redelivery. Precise, limited language beats impressive language with unproven claims — this is the exact tradeoff vocabulary series 5 rehearses.

## Why Generated UI Follows Backend Proof

The coach assessment flags the scope instinct directly: the plan to generate a UI early is the one product judgment to correct, because *"for interview signal, reliable backend behavior, tests, README, and tradeoff explanation should come first."* The reasoning is worth stating in the interview rather than just obeying it:

- **A UI proves nothing the evaluator scores.** Patient experience is scored, but a UI is the most expensive way to demonstrate the least-scored attribute. The patient journey is already observable: `curl` against the status GET, an SSE client asserting the event stream, and an end-to-end test that walks the workflow. That is the experience, proven.
- **A UI can hide an unbuilt backend.** A button that calls a missing endpoint reads as progress in a demo and as nothing in a code review. The reviewer opens the repo, not the browser tab.
- **A UI consumes the exact budget that proves SSE.** The three hours that built a plausible dashboard are the three hours that could have built reconnect tests, event replay, and patient isolation — the advanced capability the candidate's preparation is explicitly about.
- **Generated UI is cheap at the end.** Once the backend contract is fixed by tests, a minimal static page over the API is an afternoon, and it is a page that cannot drift from the contract.

When UI is warranted — and the five-hour version can justify a minimal one — it is generated against a proven API: status polling for the patient, and the simulated staff remain scripts, because the staff "can live with an inconvenient interface" and a script is the most inconvenient interface that still works.

## Interview Framing: The Scoping Story

In the walkthrough, the scoping decision is told as three sentences:

1. **The slice:** "At two hours, the submission is a complete product — submit, track, fulfill — with the status GET as the source of truth and an end-to-end test proving it."
2. **The gap, stated precisely:** "The deliberate omission is reliability: the commit-publish window can lose an event and redelivery is not deduplicated. The README documents both windows and the fix for each."
3. **The order of the next three hours:** "Given another three hours, I close the dual-write window with an outbox relay, add retries and a dead-letter queue, then prove SSE with reconnect and isolation tests — in that order, because each step fixes a documented failure before it adds a feature."

That structure demonstrates exactly what the challenge's failure-handling criterion measures: the ability to say what breaks, where it surfaces, and what would close it — under a real time budget.

## Interview Review Checklist

- What is the difference between a 2-hour plan and a 5-hour plan executed at 2 hours, and how does that show up in the submission?
- Which failure windows does the two-hour slice document, and what is the one-line fix for each?
- Why is the outbox the first hour of the five-hour budget, ahead of SSE?
- What belongs on the deferral list, and what is the rule that puts each item there?
- Where is the scope documented, and why does "what I would do next" need to be written before the code is finished?
- Why is the generated UI scheduled after backend proof, and what does it prove when it exists?

## Interview Takeaway

The two-hour and five-hour versions of this challenge are different products, and choosing between them is the first design decision you will defend. The two-hour slice is complete, honest, and boring: a correct patient journey with a documented list of failure windows. The five-hour version closes those windows in risk order and proves the realtime layer the role is advertising — and if the clock runs out mid-SSE, the submission still stands on its own. Defer aggressively, document every deferral as a decision, and let the UI wait until the backend has been proven by tests and the README has already explained what was built and what was not. Clean, working code with a precise account of its gaps beats a broad scaffold with no account at all — which is exactly what the challenge says it wants.
