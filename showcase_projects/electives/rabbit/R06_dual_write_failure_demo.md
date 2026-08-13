# R06 Dual-Write Failure Demo — Code-Along Elective

## Objective

Reproduce the classic publish-after-commit loss window on purpose: a transaction writes the prescription state, a `kill -9` in the gap between commit and publish, and documented evidence that the message — and the packaging run it carried — is gone forever. Primary objective: make the dual-write failure a *memory*, with screenshots, so the outbox in R07 is chosen from experience, not from a blog.

## Time box

~2 hours. Core — R07 exists to fix what this kata proves.

## Prerequisites

- `R02_fire_and_forget_publisher.md` — the publish path you are about to misplace.
- `R03_manual_ack_consumer.md` — you need a working consumer on `packaging.requests` to *observe* the loss (a queue with no consumer would hide it).
- PostgreSQL + RabbitMQ + app from `../glue/X01_docker_compose_trio.md` (or `../postgres/P01_schema_and_migrations.md` for the schema discipline).
- Showcase position: before `../../pharmacy-fulfillment/exercise_03_production.md` Milestone 1 (which removes exactly this gap), and before `R07_outbox_relay_mini.md` (which is this kata's after-picture).

## Blog & curriculum links

- Primary: `../../../posts/series-3-rabbitmq/02-publisher-confirms-outbox.md` — "Why Publish-After-Commit Is Wrong" and the Four Moments table.
- Secondary: `../../../posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md` — "Step 1: The Approval Transaction" and the crash-window table (window 1 vs the outbox's window 2).
- Coach-assessment gap: the dual-write gap was named in the diagnostic; this kata turns it into a reproduced failure with evidence.

## Background & motivation

Every pharmacy take-home has an "approve" flow that commits a state change and then publishes. The demo works, the walkthrough works, and the interview collapses it with one question: *what happens if the process dies between the commit and the publish?* The prescription is APPROVED in PostgreSQL, and nobody is ever told to package it — no error, because the code that would have logged the failure never ran. This kata makes that collapse physical: you build the naive path deliberately, widen the gap so you can kill in it, and document what the patient experiences. It deliberately ignores the fix (that is R07), Postgres transaction subtleties (P03 covers approval races), and idempotency — the mirror-image failure (ghost work message) is a stretch.

## Learning objectives

- Trace the three dual-write orderings (publish-first, publish-after-commit, outbox) and name each one's loss window.
- Widen and kill inside the commit→publish gap deterministically.
- Produce the evidence matrix: DB state, queue depth, consumer logs at each kill point.
- Distinguish "state committed" from "event exists" from "work happened" as three separable facts.
- Write the paragraph that motivates the outbox without yet building it.

## Warm-up (3 min)

Read the "Why Publish-After-Commit Is Wrong" section of `../../../posts/series-3-rabbitmq/02-publisher-confirms-outbox.md`. Then look at your R02 publisher and R03 consumer running, and state out loud where the gap is: between the `COMMIT` and the `rabbitTemplate.convertAndSend`.

## System specification

- **Scope in:** a deliberately naive `ApproveService` — `@Transactional` UPDATE of prescription status, then a publish to `pharmacy.work` / `packaging.request`; a configuration value `demo.gap-ms` that sleeps *between the transaction commit and the publish*; the R03 consumer; an evidence ledger (screenshots + notes).
- **Scope out:** any fix — no outbox, no relay, no confirms-dance; no retry topology; no idempotency.
- **Functional requirements:** you can reproduce, at will: (a) kill after commit before publish → state committed, zero messages, consumer silent; (b) kill during publish → state committed, zero *or* one message, indistinguishable from the app side; (c) crash before commit → clean rollback (the control case).
- **Constraints:** local Docker stack; single module; the gap must be configurable, not a magic sleep in production code — comment it as a demo-only probe.

## Step-by-step code-along

1. **Do:** Build the naive path. `ApproveService.approve(rxId)` is `@Transactional`: conditional UPDATE to `APPROVED` (see `../postgres/P03_approve_once_race.md` for the row-count habit), then — *inside the same method, after the transaction* — `rabbitTemplate.convertAndSend("pharmacy.work", "packaging.request", packagingRequest)`. Add `demo.gap-ms` (default 0) that sleeps after the method returns/commits and before the publish; log a line just before the publish: `"about to publish packaging.request for {}"`.
   **Run:** run the stack; approve one prescription. **Observe:** state = APPROVED, one message in `packaging.requests`, consumer log shows the packaging effect. Happy path works — write down that it works, because you are about to break it.
   **Decision:** where exactly is your "gap"? Nudge: the gap is *between commit and publish* — if your sleep sits inside the `@Transactional` method before commit, you are widening the wrong window.

2. **Do:** Reproduce the loss. Set `demo.gap-ms=10000`, approve a prescription, and `kill -9` the app during the gap (the log line "about to publish" tells you the window is open). Restart.
   **Run:** check all three evidence sources. **Observe:** PostgreSQL shows the prescription APPROVED; `packaging.requests` depth is 0; the consumer logged nothing; no error anywhere. The packaging run does not exist and never will. Screenshot all three (DB row, empty queue, consumer log) and caption them: *this is a silent stall*.
   **Decision:** is this loss or delay from the patient's point of view? Nudge: without a sweeper, it is loss; the workflow has no code path that will ever publish that event.

3. **Do:** Reproduce the *uncertain* case: same gap, but kill during the publish call itself (set the gap so the sleep ends and the publish begins, then kill in the same second — or place the log line and kill on sight of it plus a fraction of a second).
   **Run:** repeat 5 times. **Observe:** sometimes the queue has the message, sometimes not — and from the app's perspective both outcomes are identical (no exception logged). Write the sentence: *the exception alone cannot tell the relay whether the broker accepted the message.* That is the void publisher confirms exist to fill (R02/R07).

4. **Do:** Run the control case: crash *before* the commit (kill inside the transaction, before it returns). Restart and inspect.
   **Run:** same procedure, kill earlier. **Observe:** the prescription is still SUBMITTED, no message, and — the point — the system *knows* it: a client retry can simply re-approve. Rollback is a recoverable state; commit-then-lost-publish is not. Add this row to your evidence ledger.

5. **Do:** Write the loss ledger: a markdown table in the module README (or your notes) with rows for each kill point — before commit / after commit before publish / during publish / after publish — columns: DB state, broker state, consumer state, recoverable?, and the recovery action (retry client call? restart sweeper? nothing). This document is the interview artifact; R07 will add the outbox row to it.
   **Run:** nothing to run — this is the documentation step. **Observe:** your ledger should have exactly one row with no recovery action: the one you just reproduced.

## Try this

Scale it: run the gap experiment 10 times in a loop (a script or repeated curl), killing at a randomized offset inside the gap. Tally the outcomes in your ledger. You are not looking for a particular split — you are proving that the split exists and is *unobservable from inside the process*. Then say the interview sentence out loud: *"No broker setting can fix this, because the message was never created — the failure is before the broker exists."*

## Trade-off fork

**Option A — publish-after-commit + a startup sweeper.** On boot, scan for APPROVED prescriptions with no packaging event and republish them. The "recovery action" column gets a value.
**Option B — the transactional outbox (R07).** State change and event-intent commit together; a relay reconciles the broker from the database.

Pick one and write 3–5 lines — before building either: what does A cost (a "did we publish" marker that is itself a second truth to keep, a scan that republishes anything ambiguous — duplicates — and a delay window the patient feels)? What does B cost (a table, a poller, its own duplicate window)? Then note which one the curriculum mandates and why the sweeper's "marker" is secretly the beginning of an outbox anyway.

## Hints

- **Hint 1:** `kill -9` (not `SIGTERM`) is essential — Spring's graceful shutdown can flush or close in ways that hide the window. If you are on macOS/Linux, `kill -9 $(jps | grep <app> | cut -d' ' -f1)` from another terminal works; avoid stopping via the IDE's stop button.
- **Hint 2:** Make the gap generous (10s) for the first reproduction, then shrink it. If the consumer *is* the same app process you are killing, run the consumer logic on a separate instance (`--spring.rabbitmq.listener.simple.acknowledge-mode=manual` profile flag or a second Gradle run) so the observer survives the kill — otherwise your evidence is circular.

## Checkpoint / success criteria

You may leave when:

- You reproduced the loss window with three aligned pieces of evidence: committed DB row, empty queue, silent consumer (screenshot set).
- You reproduced the uncertain-publish case at least once and documented both outcomes as indistinguishable from inside the app.
- Your loss ledger has the control case (pre-commit crash → recoverable) and exactly one unrecoverable row.
- You can state, without notes, why the patient experiences this as a silent stall and why no broker configuration prevents it.

## Bottleneck & reflection questions

1. Which of the four moments (commit → broker acceptance → routing → processing) failed in your reproduced loss, and which moment *could not fail*, because the event never existed?
2. Your ops team adds a "republish all unapproved-then-approved prescriptions" cron job. What duplicate does it create, and what does R05 require of it?
3. How long is your team allowed to not know about this loss before a patient notices? What is the cheapest *detection* signal you could add today without fixing the gap?
4. The interview asks "what happens if the broker is down when you approve?" — does your naive design lose the approval, delay it, or error cleanly? Where does your answer come from in this kata's evidence?
5. Why is "crash before commit" the only dual-write outcome this design handles well, and what property of transactions makes it safe?

## Handoff

- Next: `R07_outbox_relay_mini.md` — the fix, with the publisher confirms from R02 doing real work. This kata's loss ledger becomes the "before" column of R07's crash table.
- Related showcase: `../../pharmacy-fulfillment/exercise_03_production.md` Milestones 1–2 — the outbox transaction and the relay are the closing of this gap.
- Interview line to say aloud: *"I have reproduced the dual-write loss window — commit the state, die before the publish, and the event simply never exists; the process cannot even tell the difference between 'the broker never saw it' and 'it is still in flight.' That is why my approval transaction writes an outbox row instead of making a RabbitMQ call, and why my relay marks published only after a broker confirm."*

## Optional stretch

Build the mirror-image failure: a publish-first variant that publishes `packaging.request` *before* the transaction commits, then kill after the publish and before the commit. Observe the ghost message: the broker delivered packaging work for a prescription the database still considers SUBMITTED. Add it as the ledger's last row, and in one paragraph explain which failure is worse for a pharmacy — work that never happens (your demo) or work that happens before it legally should (the ghost) — and why the outbox removes both orderings at once.
