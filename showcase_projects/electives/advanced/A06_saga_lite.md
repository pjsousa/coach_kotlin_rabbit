# A06 Saga Lite — Code-Along Elective

## Objective

You already built state machines with sealed types in K03, made single transitions race-safe in P03, and made message delivery reliable in R03. This elective composes those three: a **saga-lite** — a multi-step workflow (reserve → approve → package) where each step is a durable state machine transition and each failure triggers a compensating action. One primary objective: coordinate a local, durable saga in one Spring Boot app where a mid-workflow failure produces a consistent, patient-visible outcome — not a half-reserved, half-cancelled prescription.

## Time box

- Core: 3 hours
- Optional: 0.5h for a second saga path (reject → release inventory) with the same machinery

## Prerequisites

- K03 (`../kotlin/K03_workflow_state_machine.md`) — sealed states, exhaustive `when`, illegal transitions as domain outcomes. You saw the state machine; now drive it across a workflow.
- P03 (`../postgres/P03_approve_once_race.md`) — conditional updates and affected-row counts; every saga step is one.
- R03 (`../rabbit/R03_manual_ack_consumer.md`) — work commands over Rabbit, ack-after-effect.
- Showcase position: **during Exercise 3** — the approval→packaging handoff is the saga core of `../../pharmacy-fulfillment/exercise_03_production.md`.

## Blog & curriculum links

- Primary: `posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md` (the journey of one approval)
- Secondary: `posts/series-1-kotlin/03-state-machines-with-sealed-types.md` and `posts/series-2-postgres/02-transactions-isolation.md`
- Coach-assessment gap: workflow coordination "conceptual" → durable, compensatable state.

## Background & motivation

Ex3's core workflow is a saga wearing a simpler name: reserve inventory, pharmacist approves, packaging worker packages, status facts fan out. The tutorial instinct is to chain these in one try/catch with "if step 3 fails, try to undo step 1 by memory." That breaks under the program's own rules: the outbox (A03) makes each step's effects durable *before* the next message exists, so a failure after step 2 commits cannot be unwound by an in-memory catch block. This kata exists to make compensation a **first-class durable action**: each step is a state transition; each step has a registered compensating transition; and the saga state machine — a sealed type, as in K03 — decides which compensation runs, once, durably. It deliberately ignores distributed saga *orchestration services* (no second app, no saga engine) and multi-entity atomicity beyond one aggregate per step — the point is the composition, not the fleet.

## Learning objectives

- Model a saga as a sealed state machine (K03) whose states include *in-progress* variants carrying progress, not just terminal states.
- Execute steps as conditional transitions (P03) driven by Rabbit work commands (R03), acking only after the step's durable effect commits.
- Implement compensating transitions (release inventory on reject, cancel approval on packaging failure) as normal conditional updates with their own affected-row checks.
- Show that a failure at step 3 leaves the saga in an explicit, recoverable state — never a half-applied zombie.
- Record the saga's journey (progress + compensations) in status history so the patient's view is consistent.

## Warm-up

Re-read the "Journey of One Approval" walkthrough in `posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md` and the "Sealed Types For States With Different Data" section of `posts/series-1-kotlin/03-state-machines-with-sealed-types.md`. Then, on paper, draw the three-step saga for one prescription with two failure arrows: *reserve succeeds, approve fails* and *reserve+approve succeed, package fails*. For each arrow, write the compensating transition and its affected-row condition. That drawing is the design you're about to implement.

## System specification

**Scope in:** one prescription aggregate; steps RESERVE (inventory decrement), APPROVE (pharmacist command over Rabbit), PACKAGE (packaging worker over Rabbit); a saga table `saga_state(prescription_id, step, status, attempts)` written with each step; compensations: RELEASE (inventory increment) for failed reserve/approve, and CANCEL_APPROVAL (state back to AWAITING_APPROVAL + release) for failed packaging; status-history entries for each transition and compensation.

**Scope out:** a separate saga orchestrator service, timeouts/expiry of whole sagas, retry budgets beyond one compensation attempt (A01's machinery is the natural extension), multi-aggregate sagas, exactly-once effect (A04 already owns that — but your steps must *use* it).

**Functional requirements:**
- Each step commits its durable effect before acking its command message.
- A failure at any step triggers exactly one compensating transition, durably recorded.
- The saga table and the status history agree at every moment (single-transaction per step).
- A crashed saga restarts from its durable step, not from the beginning.
- The patient's `GET /prescriptions/{id}` reflects the compensated outcome (consistent, not hypothetical).

**Constraints:** local Docker Compose, one Spring Boot app, Kotlin, pinned Postgres + Rabbit, manual ack.

## Step-by-step code-along

1. **Do:** Model the saga state as a sealed type (K03 style), stored as a small table plus a Kotlin sealed hierarchy for the *in-code* decision: e.g.
   `sealed interface SagaState { data object NotStarted; data class Reserved(seq: Int); data class Approved(seq: Int); data class Packaged(seq: Int); data class Compensating(seq: Int, reason: Reason); data object Cancelled; data object Completed }`.
   Map `step` + `status` columns to it; keep the sealed type as the decision layer, the rows as the truth.
   **Run:** a pure unit test constructing each state and asserting its legal transitions (exhaustive `when`). **Observe:** the compiler enforces "no transition you didn't model" — this is K03's payoff in a saga. **Decision:** store a `reason` enum in `Compensating` (nudge: `RESERVE_FAILED`, `APPROVE_FAILED`, `PACKAGE_FAILED` — operators will ask *why* it compensated).

2. **Do:** Step RESERVE as a conditional transition: `UPDATE inventory SET qty = qty - :n WHERE med_id = :id AND qty >= :n RETURNING qty` (P04's atomic decrement), then insert the saga row `(prescription_id, 'RESERVE', 'DONE')` and a status-history entry — all in one transaction.
   **Run:** submit one prescription with sufficient stock. **Observe:** inventory and saga row move together; the audit query `SELECT * FROM saga_state` shows `RESERVE/DONE`. **Decision:** reserve as the saga's step 1 vs the Ex3 product's existing submission transaction — the saga is additive here; your submission transaction may already reserve. Keep whichever is true; the saga table records it either way.

3. **Do:** Step APPROVE as a work command: publish `approve.request` (via the outbox from A03/R07, never direct-publish), consume it with MANUAL ack, apply the transition `AWAITING_APPROVAL → APPROVED` with the P03 conditional update (`WHERE status = 'AWAITING_APPROVAL'`), update the saga row to `APPROVE/DONE` in the same transaction, then ack.
   **Run:** approve via the staff command; watch the flow. **Observe:** the saga row shows `APPROVE/DONE` only after the state update committed — and a redelivered duplicate is absorbed by the conditional update's affected-row check (P03's lesson: rows = 0 means already applied; ack and move on). **Decision:** which table is authoritative for "approve already happened" — the state or the saga row? (They must agree by construction; pick the state — it's the product contract from Ex3.)

4. **Do:** Step PACKAGE the same way: `approve.request → package.request` routing, consume, `APPROVED → PACKAGING → READY_FOR_COLLECTION` transitions, saga row `PACKAGE/DONE`, ack after commit.
   **Run:** let packaging complete. **Observe:** saga `PACKAGE/DONE`, patient sees `READY_FOR_COLLECTION`, inventory never decremented twice (P04 evidence). **Decision:** whether packaging is one step or two (PACKAGING → READY) — the saga cares about *durable steps with compensations*, so fold the in-flight marker into the same step if no compensation exists between them.

5. **Do:** Compensations. Implement `releaseInventory(prescriptionId)` as `UPDATE inventory SET qty = qty + :n WHERE med_id = :id` (idempotent by the saga-row guard: only run when saga state is in a compensating-eligible step, guarded by a conditional update on the saga row itself) and `cancelApproval` as `AWAITING_APPROVAL/APPROVED → REJECTED` with the saga row set to `COMPENSATING(reason)` → `CANCELLED`. Wire: reserve failure → release (nothing to release if reserve failed — the compensation is the *saga row* being marked CANCELLED with reason; approve failure → release inventory + mark cancelled; package failure → cancel approval + release inventory.
   **Run:** force each failure (a `FAIL_NOW` flag in the command payload or a fault-injection endpoint). **Observe:** after each, `saga_state` ends in `CANCELLED(reason=…)`, inventory returns to its original count, status history shows the compensation, and `GET /prescriptions/{id}` returns `REJECTED`. Capture the audit trail. **Decision:** compensation ordering — release-then-mark vs mark-then-release (nudge: the saga-row guard makes either safe against duplicates; pick the one whose failure leaves the *inventory* in the better state, because inventory is the invariant P04 cares about).

6. **Do:** Crash restart. Kill the app between step commit and ack (the commit-then-ack window from R03) and restart. 
   **Run:** redelivery. **Observe:** the conditional transition returns rows = 0 (already applied), the saga row is already `DONE` for that step, so the consumer acks the duplicate without re-executing — the durable saga row *is* the idempotency guard for the step (A04's inbox is the generalized version; note the trade-off). **Decision:** saga-row-as-guard vs a real inbox (A04) — write the 2-line difference in your notes.

7. **Do:** Wire into Ex3 (`../../pharmacy-fulfillment/exercise_03_production.md` Milestone 1-5 shape): the approval transaction becomes step APPROVE, the packaging worker becomes step PACKAGE, and rejection releases inventory with the saga row marking `CANCELLED`.
   **Run:** Ex3's existing tests + your saga tests. **Observe:** no behavior regression; the patient status GET never shows an impossible state.

## Try this

**The two-winner race.** Approve and Reject the same prescription concurrently (two pharmacist commands, R03/P03 muscle). Your saga must produce exactly one outcome — either the approval transition wins and reject gets rows = 0, or vice versa — and the saga row must reflect the winner. Then repeat with the approve *also* racing a packaging-failure compensation. The patient must never see `APPROVED` and `REJECTED` simultaneously. Record the outcomes of 20 runs; they should all be identical modulo the winner's identity. That determinism under races is the saga's whole job.

## Trade-off fork

Pick one pair, implement it, justify in 3–5 lines:

- **Orchestration vs choreography:** orchestration (a coordinator that reads saga state and issues the next command — what this kata's saga table does in-app) is explicit and auditable but adds a coordinator component and a single point of reasoning. Choreography (each step publishes the next event, Rabbit binds them) removes the coordinator but scatters the workflow across queues and makes the "what happens if this event is lost" story per-hop. You built the orchestrated variant; write the lines for why you didn't ship choreography for a 3-step pharmacy workflow.
- **Compensating transaction vs pure retry:** compensation restores a consistent state (patient sees REJECTED, inventory restored) but doubles the transitions you must implement and test. Pure retry (A01's parking lot, no compensation) is simpler but leaves a failed-approve reservation in limbo — a real inventory leak in a pharmacy. Name what each costs; there is no single winner because the answer depends on whether the failure is retryable (transient lock) or permanent (cancelled prescription).

## Hints

- **Hint 1:** Every saga step is three writes that must move together: the state transition, the saga-row update, and the status-history entry — one `@Transactional` boundary, same as the outbox (A03). If you see a saga where "the state says APPROVED but the saga row says RESERVE," you have a transaction-boundary bug, not a modeling bug.
- **Hint 2:** Compensation guard: use a conditional update on the saga row itself (`UPDATE saga_state SET status='CANCELLED' WHERE prescription_id=:id AND status='COMPENSATING'`), check rows = 1. Two concurrent compensation attempts (redelivery + operator retry) then have exactly one winner, and the loser's release never runs twice — which is why inventory can't be double-returned.

## Checkpoint / success criteria

Done when:

- Reserve→approve→package happy path: saga row `PACKAGE/DONE`, patient `READY_FOR_COLLECTION`, inventory decremented once.
- Each of the three failure arrows produces a recorded compensation ending in `CANCELLED(reason=…)` with inventory restored and status `REJECTED`.
- Crash-restart at the commit-then-ack window re-executes zero business effects (duplicate absorbed, rows = 0 evidence).
- Concurrent approve/reject race produces exactly one outcome across 20 runs.
- Audit trail (saga table + status history) is consistent at every step — asserted by the tests.

## Bottleneck & reflection questions

1. Your saga state lives in Postgres and the decisions in a sealed type. Where does the *knowledge* of "what to do next" live — and how do you keep the sealed type from lying about a row an operator edited by hand?
2. Compensation release is idempotent by guard. What happens if the compensation *itself* fails (DB down at release time) — where does the saga sit, and who retries it? (A01's machinery is the honest answer.)
3. The patient sees REJECTED after a packaging failure. Is that the right product outcome, or should it be a *different* terminal state the patient can distinguish? This is a Product Engineer question wearing a saga costume.
4. At what point does a 3-step saga with in-app coordination stop being "saga-lite" and start needing a real orchestrator or an event log? Define your cut line for Ex3.
5. Ex3's status facts are published per step. How do your saga steps and the outbox (A03) keep the *fact* emission from becoming a fourth uncoordinated write?

## Handoff

- Next: A07 (`A07_idempotent_http_and_brokers.md`) — the same exactly-once-effect discipline at the HTTP boundary. Or A09 (`A09_postgres_under_contention.md`) if lock contention is your next worry.
- Related showcase work: `../../pharmacy-fulfillment/exercise_03_production.md` — this kata's saga table is the nucleus of Milestones 1-5, and its audit trail feeds Milestone 10's crash matrix.
- Interview line: *"The fulfillment workflow is a saga with durable steps: each step commits its effect before acking, each step has a registered compensation that runs under a conditional guard, and the saga state is a sealed type over a database row — so a crash mid-workflow restarts from the durable step and a packaging failure leaves the patient with a consistent REJECTED state and restored inventory, never a half-reserved zombie."*

## Optional stretch

Add a saga *timeout*: a scheduled scan that finds saga rows stuck in an in-progress step older than N minutes and runs the step's compensation (with a documented manual-override endpoint to skip). Test the timeout fires exactly once per stuck saga even if the scan runs concurrently with itself.
