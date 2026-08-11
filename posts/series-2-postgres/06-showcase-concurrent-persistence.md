# A Correct Pharmacy Persistence Model Under Concurrent Demand

A Saturday morning in the pharmacy waiting room: two patients submit prescriptions for the same medication, one unit of which remains in stock. The pharmacist is approving another prescription at the same moment a patient polls for status. None of these requests knows the others exist. If the persistence model is correct, every one of them gets an answer that is true at the moment it commits — no patient is promised stock that was already claimed, no prescription is approved twice, no status change happens without its history, and no event is published before its state change is durable.

The previous five posts each proved one piece of this model: the schema, the transaction boundaries, the reservation race, the indexes, and the tests. This post unifies them into the single design the challenge would submit. The goal is not a new technique. It is the one coherent story — the table of what the model is, which mechanism protects each invariant, and why a design this small survives real concurrent demand.

## Four Invariants, One Model

Everything in the persistence layer exists to keep four statements true, no matter how many requests arrive at once:

1. **No oversell.** Total reserved quantity never exceeds what was available, and `available_quantity` never goes negative.
2. **One winner per transition.** A prescription awaiting approval is approved exactly once; a reservation is released or consumed exactly once.
3. **Atomic workflow facts.** The state change, its history row, and its outbox event commit together or not at all. The database is never ahead of or behind the story it tells.
4. **Safe retries.** A duplicate request or a redelivered message produces the same durable state as the original, because uniqueness constraints make repetition harmless.

Everything else in the model — every column, constraint, index, and transaction boundary — is in service of those four. When an interviewer asks "why does this table exist?", the honest answer is one of those invariants, not "it seemed like good practice."

## The Submission Transaction

The first moment of concurrent demand is prescription submission. In one database transaction the service must create the prescription and its lines, claim inventory for every line, record the reservation, write the first history row, and insert the submission's outbox event:

```kotlin
@Transactional
fun submit(command: SubmitPrescription): UUID {
    for (line in command.lines.sortedBy { it.medicationId }) {
        inventory.tryReserve(line.medicationId, line.quantity)
            ?: throw InsufficientStockException(line.medicationId)
        reservations.insertReserved(command.prescriptionId, line.medicationId, line.quantity)
    }
    prescriptions.insertSubmitted(command)
    history.append(command.prescriptionId, "SUBMITTED", actorType = "PATIENT")
    outbox.insertSubmissionEvent(command)
    return command.prescriptionId
}
```

The reservation is the atomic decrement from the reservation post: computed from the current row value, predicate inside the write, zero affected rows meaning "no claim happened":

```sql
UPDATE inventory
SET available_quantity = available_quantity - :quantity,
    updated_at = CURRENT_TIMESTAMP
WHERE medication_id = :medication_id
  AND available_quantity >= :quantity
RETURNING medication_id, available_quantity;
```

Every mechanism from the series is already present in this one method:

- **The transaction boundary.** Lines, reservations, prescription, history, and outbox event roll back together. If line three of four lacks stock, line one's decrement never commits — a partial reservation would be recovery work the challenge never agreed to model.
- **Affected rows as the business outcome.** Zero rows is insufficient stock, never a retry. The decision was already made by the write.
- **Stable line order.** `sortedBy { it.medicationId }` converts the deadlock cycle between two multi-line submissions into orderly waiting.
- **The `CHECK (available_quantity >= 0)` constraint** stays in the schema as the second line of defense, converting a future buggy writer into a failed transaction instead of silently persisted negative stock.
- **The client-supplied prescription UUID.** If the process crashes after commit and the patient retries, the primary key on `prescriptions` rejects the duplicate with SQLSTATE `23505`, which the API maps to "already submitted." The uniqueness constraint *is* the idempotency mechanism — there is no double reservation and no double decrement.

At this point the design already covers the challenge's core claim: two patients racing for the last unit get exactly one reservation and one honest rejection. That claim lives in the statement, not in the service code, and that is precisely what makes it survive concurrent demand.

## Approval And Rejection: Transitions With One Winner

Submission created the demand. Approval is where the workflow facts must commit atomically, and where the second race of the day happens: two pharmacist clicks on the same prescription.

```sql
UPDATE prescriptions
SET status = 'APPROVED', status_version = status_version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE id = :prescription_id
  AND status = 'AWAITING_APPROVAL'
RETURNING id, status_version;
```

One row returned means this request won; zero rows means someone else already transitioned the prescription. Under `READ COMMITTED`, PostgreSQL waits out the concurrent writer and then re-evaluates the predicate against the newest committed row version — the loser does not apply a stale decision, it matches zero rows. The application maps that result to a deliberate outcome: the transition did not happen.

The rest of the approval transaction writes the facts that belong to the win:

1. One `prescription_status_history` row with the next per-prescription sequence number.
2. One `outbox_events` row with a stable UUID `event_id` describing `APPROVED`.
3. A single commit that makes the current status, the history, and the handoff to RabbitMQ durable together.

The outbox is the answer to the dual-write failure mode the schema post opened with. "Update status, commit, then publish" can crash between the two and leave a prescription approved in the database with no packaging event. "Publish, then update" can send a message describing a state change that rolled back. Both orders are wrong; the outbox insert inside the same transaction as the transition makes the event a database fact. The relay may publish it late, and a relay crash may publish it twice — but the database never claims a transition whose event does not exist, and the inbox table makes the duplicate harmless. This is where "at-least-once plus idempotency" is spelled out in schema, not just in conversation.

Rejection is the same discipline on the reservation side:

```sql
UPDATE inventory_reservations
SET status = 'RELEASED', updated_at = CURRENT_TIMESTAMP
WHERE prescription_id = :prescription_id
  AND medication_id = :medication_id
  AND status = 'RESERVED'
RETURNING quantity;
```

with the inventory restore in the same transaction:

```sql
UPDATE inventory
SET available_quantity = available_quantity + :quantity,
    updated_at = CURRENT_TIMESTAMP
WHERE medication_id = :medication_id;
```

The `AND status = 'RESERVED'` predicate is what makes a double release impossible: the second release attempt affects zero rows and cannot restore the stock twice. Exactly-once effects, built from affected-row discipline rather than from exactly-once delivery. And when fulfillment completes, the reservation becomes `CONSUMED` and no further decrement happens — the units left `available_quantity` at reservation time. A design that decrements again at fulfillment has double-counted.

## What Concurrent Demand Actually Looks Like

The model's individual pieces are only meaningful in combination. Here is one afternoon, several requests, no coordination between them:

```text
Patient A submits                Patient B submits                Pharmacist clicks approve
-------------------------------- -------------------------------- ---------------------------
BEGIN;
UPDATE inventory ... >= 1        BEGIN;
  row lock acquired on M1          UPDATE inventory ... >= 1
  quantity 1 -> 0                   waits for A's row lock
insert reservation, prescription,   ...
history, outbox event             COMMIT;                         UPDATE prescriptions
COMMIT;                              lock released                  SET status='APPROVED'
                                      re-evaluates WHERE             WHERE status='AWAITING_APPROVAL'
  UPDATE inventory ... >= 1          available_quantity = 0          --
  zero rows -> conflict              0 >= 1 is false                 wins: RETURNING one row
  transaction aborted, nothing       zero rows -> conflict           history + outbox event inserted
  committed                          nothing committed               COMMIT;
```

Three properties of that timeline carry the whole design, and each was established in an earlier post:

- **The database coordinates the race.** Writers to the same row serialize on the row lock; the losing transaction re-evaluates its predicate after the winner commits. Neither patient needed a distributed lock, a leader election, or a retry loop to get a correct answer.
- **Readers are never involved.** The patient polling their status while the reservation update runs reads MVCC snapshots and never waits behind the writer. PostgreSQL's readers do not block on row locks, which is a real difference from SQL Server's default `READ COMMITTED` — and it is why the synchronous status `GET` remains the authoritative correctness baseline even after the asynchronous workflow is added.
- **Lock ordering is a global rule.** Submission sorts lines, and every other command that touches inventory rows must follow the same order. Violations surface as SQLSTATE `40P01` deadlocks, which the application retries a bounded number of times and the metrics track as a bug signal, not a fact of life.

## The Model In One Table

When an interviewer asks "what is your persistence model?", the answer is this table: every workflow step, the mechanism that protects its invariant, and the test that proves it. If a mechanism is missing from the model, the corresponding invariant is unclaimed.

| Workflow step | Mechanism | Invariant protected |
| --- | --- | --- |
| Submit (reserve inventory) | Atomic `UPDATE ... WHERE available_quantity >= :q`, `CHECK (available_quantity >= 0)` | No oversell; no negative stock |
| Submit (idempotency) | Client UUID as primary key, SQLSTATE `23505` mapped to "already submitted" | Retry after lost response creates one prescription, one reservation |
| Multi-line submit | One transaction, lines locked in `medication_id` order, `40P01` bounded retry | All-or-nothing reservation; no deadlocks |
| Approve | Conditional update `WHERE status = 'AWAITING_APPROVAL'`, zero rows = lost race | Exactly one winner per transition |
| Approve (workflow facts) | History row + outbox event inserted in the same transaction, committed together | Status, history, and event never diverge |
| Reject | Reservation update `WHERE status = 'RESERVED'` + inventory restore, one transaction | Release exactly once; stock restored once |
| Fulfill | Reservation to `CONSUMED`, no second inventory change | No double-counted units |
| Patient status read | Current status column on `prescriptions`, patient-status indexes, MVCC reads | Fast, non-blocking, authoritative synchronous answer |
| Outbox relay | Partial index on unpublished rows, `FOR UPDATE SKIP LOCKED` claiming | Rows claimed exactly once, publishable in any order |
| Event consumer | `inbox_events` primary key `(consumer_name, event_id)` | Duplicate delivery produces one processed effect |

The table also shows the shape of a two-hour submission versus a five-hour one: the two-hour version stops at the first six rows and documents that relay or idempotency is pending; the five-hour version adds the last four. What never changes is that each row is either implemented with its mechanism or explicitly listed as a known limitation — never silently assumed.

## The Indexes The Model Needs — And No More

The index post's discipline was to index the query shapes that actually exist, then validate with `EXPLAIN ANALYZE`. In this model there are exactly four:

- **Patient status lookups.** The current-status query by `prescription_id` is served by the primary key; if the product ever lists "my prescriptions" for a patient, the status-history lookup index pattern applies.
- **Status history by prescription.** `(prescription_id, sequence_number DESC)` serves both the history display and the future SSE replay boundary. The monotonic per-prescription sequence is allocated inside the same transaction as the state change, so ordering never depends on timestamps.
- **Unpublished outbox rows.** The partial index `(available_at, occurred_at) WHERE published_at IS NULL` is what keeps the relay's `FOR UPDATE SKIP LOCKED` scan narrow as the table grows.
- **Outbox by aggregate.** `(aggregate_id, occurred_at)` makes "what happened to prescription X" a lookup instead of a scan — useful for support and for the future SSE replay.

Nothing else gets an index in the challenge. The patient-status query shape is a single-row read; the pharmacist and packaging queues read by status, which at challenge scale is served by the small scan the index post walked through. Adding indexes before the query shapes exist is exactly the premature tuning the series warns against — and `EXPLAIN ANALYZE` against the real engine is what keeps the few indexes honest.

## Proving The Model Against Real PostgreSQL

Every row of the model table has a corresponding integration test, and the testing post's rule governs all of them: the authoritative assertions run against real PostgreSQL via Testcontainers, with real migrations, real threads, and SQL assertions on committed state. The high-value suite is small on purpose — it is the tests that would catch the real failure modes:

1. **Race for the last unit.** Seed `available_quantity = 1`, fire two latched submission transactions; assert one reservation, one insufficient-stock outcome, final quantity zero.
2. **Double approval.** Two concurrent `approveIfAwaiting` calls; assert one `true`, one `false`, and `count(*)` on history equals 1.
3. **Atomic workflow facts.** Approve, then assert the history row and the unpublished outbox event committed together; abort an approval and assert nothing changed — including that the outbox holds no phantom event.
4. **Release exactly once.** Reject, then attempt the release twice concurrently; assert inventory restored exactly once and one `RELEASED` row.
5. **Retry after a lost response.** Submit, commit, discard the response, resubmit with the same UUID; assert one prescription, one reservation, one decrement, and a deliberate conflict outcome.
6. **Relay claim.** Concurrent relay polls with `SKIP LOCKED`; assert no outbox row is claimed twice.
7. **Inbox deduplication.** Deliver one `event_id` twice; assert one processed row.

The last three rows of the model table belong to the RabbitMQ series, but the persistence half of them — the inbox primary key and the `SKIP LOCKED` claim — is PostgreSQL behavior and is proven here. The assertion pattern matters as much as the scenario: read the committed state with a fresh connection outside the transaction, so the test observes what a concurrent reader would see, rather than trusting the repository under test to report its own success.

## What Is Intentionally Omitted

The design is deliberately small because the challenge is deliberately small. Naming the omissions — and the reason for each — is part of the answer:

- **Reservation expiry.** Reserved stock on a prescription that is never decided stays reserved. Production adds a sweeper that releases stale `RESERVED` rows past a deadline, coordinated so it never races a concurrent consume. The challenge documents the limitation instead and may expose a simple operational release endpoint. Honest documentation beats a half-built sweeper.
- **Optimistic version columns and advisory locks.** The `status_version` column exists as a hook, but the conditional status update already decides the race inside the write. Classic optimistic retry would generate the most round trips exactly when the row is busiest. PostgreSQL advisory locks solve cross-service coordination problems the challenge does not have.
- **`SERIALIZABLE`.** The invariants are expressible in single statements under `READ COMMITTED`. Serializable would buy nothing here and cost abort-and-retry machinery on the hottest rows.
- **Read replicas, partitioning, and scale tuning.** The four indexes and a warm cache serve the challenge's data volume. Scale claims are reasoned about, not benchmarked on a laptop.
- **Exactly-once.** No claim, anywhere: the model is at-least-once delivery plus idempotency through uniqueness constraints. Saying "duplicates are possible and here is the constraint that makes them harmless" is the correct sentence; "exactly-once" is the incorrect one.
- **Patient and medication catalog tables.** The challenge only needs identifiers; owning full identity and catalog systems would be over-modeling, and the schema post said so.

## Interview Review Checklist

- Why do the reservation decrement, the reservation row, the prescription, its history, and the outbox event belong in one transaction?
- Walk through the two-submission race for the last unit, statement by statement. What does each loser observe, and what does the winner's commit release?
- Where does `23505` appear in the model, and why is that constraint the idempotency mechanism?
- How does a double approval become impossible, and how does the application distinguish "won" from "lost the race"?
- Why does the outbox insert belong in the same transaction as the status change, and what exactly is still at risk after commit?
- How does rejection restore stock exactly once? What stops a double release?
- Why does fulfillment not decrement inventory again?
- Which indexes exist in the model, and which query shapes justify each? Which index does the model deliberately not have?
- How do the tests prove rows 1-7 of the model table, and why must they run against real PostgreSQL?
- What would a two-hour submission omit from the table, and how is that documented?
- Why is `SERIALIZABLE` not used, and what would a reservation-expiry sweeper need to coordinate against?

## Interview Takeaway

The persistence model is one story: four invariants, each protected by the weakest mechanism that expresses it. Submission is a single transaction whose statements decide the reservation; approval and rejection are conditional updates whose affected rows are the business outcome; history and outbox events commit with the transition they describe; uniqueness constraints make retries and redeliveries harmless; and a handful of justified indexes keep the few real query shapes fast. Under concurrent demand the database coordinates the races, readers never block, and every crash window has a defined recovery result. The design stays small because the challenge is small, and the strongest interview signal is the one-table summary: for every step, the mechanism, the invariant, the test, and the honest list of what was left out.
