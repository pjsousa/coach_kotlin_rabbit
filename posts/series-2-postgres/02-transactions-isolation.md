# Transactions, Isolation, and Atomic State Changes

The schema from the previous post can represent a prescription workflow, but a schema alone does not make the workflow safe. Two pharmacist requests can arrive at the same time. Two patients can request the last available medication. A process can fail after changing one table and before changing another.

The important PostgreSQL skill is not memorizing isolation-level names. It is choosing a transaction boundary and writing a database operation whose predicate expresses the invariant that must hold when the operation commits.

For the pharmacy challenge, the core questions are:

- Can only one request approve a prescription that is awaiting approval?
- Can rejection release a reservation without leaving the prescription in a misleading state?
- Can two submissions reserve more units than inventory contains?
- Do status history and outbox records commit with the state change they describe?
- Can the service tell the difference between a transition that won and one that lost a race?

The examples use the tables introduced in the schema article: `prescriptions`, `prescription_status_history`, `inventory`, `inventory_reservations`, and `outbox_events`.

## A Transaction Protects A Commit, Not A Thought

A common service implementation looks safe when read from top to bottom:

```text
read prescription
if status is AWAITING_APPROVAL
    set status to APPROVED
save prescription
```

The problem is that the read and the write are separate database operations. Another request can change the row after the read and before the save. Both requests may observe `AWAITING_APPROVAL`, and an unconditional save does not record which request was entitled to win.

A transaction is necessary for related changes to commit or roll back together, but merely placing this read-then-write sequence inside a transaction does not automatically solve the race. The write still needs either:

- a predicate that includes the expected state;
- a row lock acquired before the decision; or
- a stronger isolation strategy with retry handling.

For a small workflow, a conditional update is usually the clearest first choice.

## PostgreSQL `READ COMMITTED`

PostgreSQL defaults to `READ COMMITTED`. Each statement sees data committed before that statement began, along with changes made earlier in its own transaction. Two `SELECT` statements in one transaction can therefore see different committed data if another transaction commits between them.

This is different from saying that every operation is unsafe. PostgreSQL also coordinates concurrent updates with row-level locks. Consider two approval requests:

```text
Transaction A                         Transaction B
---------------                       ---------------
BEGIN;                                BEGIN;
UPDATE ... WHERE status =             UPDATE ... WHERE status =
  'AWAITING_APPROVAL';                  'AWAITING_APPROVAL';
                                      waits for A's row lock
COMMIT;
                                      rechecks the WHERE predicate
                                      finds status is now APPROVED
                                      updates zero rows
                                      COMMIT;
```

For an `UPDATE` under `READ COMMITTED`, PostgreSQL may wait for a concurrent update. After the first transaction commits, PostgreSQL re-evaluates the `WHERE` condition against the newer row version. If the status no longer matches, the second update does not apply.

That behavior makes this operation safe:

```sql
UPDATE prescriptions
SET status = 'APPROVED',
    status_version = status_version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE id = :prescription_id
  AND status = 'AWAITING_APPROVAL';
```

The state predicate is part of the write. It is not merely a validation performed earlier in Kotlin.

`READ COMMITTED` does not provide repeatable reads or full serializability. A transaction that performs several broad queries may still observe different results across statements. The useful point is narrower: a single conditional update can atomically test and change one row, and PostgreSQL coordinates concurrent writers to that row.

## Approval As A Conditional State Change

Approval normally has more effects than changing one column. In this challenge it may need to:

1. Move the prescription from `AWAITING_APPROVAL` to `APPROVED`.
2. Append an `APPROVED` status-history row.
3. Insert an outbox event for packaging or the next workflow step.

Those facts describe one business command and should commit together. The transaction boundary belongs around the whole command, not around each repository call separately.

The transition itself can be expressed with `RETURNING`:

```sql
UPDATE prescriptions
SET status = 'APPROVED',
    status_version = status_version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE id = :prescription_id
  AND status = 'AWAITING_APPROVAL'
RETURNING id, patient_id, status, status_version;
```

If a row is returned, this transaction won the transition. The returned values can drive the history and outbox inserts without a second read of the prescription:

```sql
INSERT INTO prescription_status_history (
    prescription_id,
    sequence_number,
    status,
    reason,
    actor_type
)
VALUES (
    :prescription_id,
    :new_status_version,
    'APPROVED',
    NULL,
    'PHARMACIST'
);

INSERT INTO outbox_events (
    event_id,
    aggregate_type,
    aggregate_id,
    event_type,
    payload
)
VALUES (
    :event_id,
    'PRESCRIPTION',
    :prescription_id,
    'PrescriptionApproved',
    :payload
);
```

The application should execute these statements in one database transaction:

```text
BEGIN
  conditional prescription UPDATE ... RETURNING
  if no row returned: classify the command as not found or invalid state
  insert status history
  insert outbox event
COMMIT
```

If the history or outbox insert fails, the approval update rolls back too. The database never commits `APPROVED` while losing the durable records needed to explain or continue that transition.

### What Does Zero Rows Mean?

An affected-row count of zero means that this command did not apply. It may mean:

- the prescription ID does not exist; or
- the prescription exists but is no longer awaiting approval.

The conditional update intentionally does not decide which user-facing error to return. The service can perform a separate read after the failed update if the API needs to distinguish `404 Not Found` from a conflict such as `409 Conflict`. That read is for error classification, not for deciding whether the transition is safe.

If the command only needs success versus no-op, zero rows is enough. Avoid adding a preliminary read just to make the code look more explicit.

## Rejection And Reservation Release

Rejection has a similar transition, but a reservation may need to be released. If inventory was reserved when the prescription was submitted, rejection should not produce this partial result:

```text
prescription = REJECTED
inventory reservation = still RESERVED forever
```

The state change, inventory restoration, reservation update, history row, and outbox event belong in one transaction. A conceptual sequence is:

```sql
BEGIN;

UPDATE prescriptions
SET status = 'REJECTED',
    status_version = status_version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE id = :prescription_id
  AND status = 'AWAITING_APPROVAL'
RETURNING id, patient_id, status, status_version;

-- Continue only when the UPDATE returned one row.

UPDATE inventory AS i
SET available_quantity = i.available_quantity + r.quantity,
    updated_at = CURRENT_TIMESTAMP
FROM inventory_reservations AS r
WHERE r.prescription_id = :prescription_id
  AND r.status = 'RESERVED'
  AND i.medication_id = r.medication_id;

UPDATE inventory_reservations
SET status = 'RELEASED',
    updated_at = CURRENT_TIMESTAMP
WHERE prescription_id = :prescription_id
  AND status = 'RESERVED';

INSERT INTO prescription_status_history (
    prescription_id, sequence_number, status, reason, actor_type
)
VALUES (
    :prescription_id, :new_status_version, 'REJECTED', :reason, 'PHARMACIST'
);

INSERT INTO outbox_events (
    event_id, aggregate_type, aggregate_id, event_type, payload
)
VALUES (
    :event_id, 'PRESCRIPTION', :prescription_id,
    'PrescriptionRejected', :payload
);

COMMIT;
```

The real repository code should verify that the first update changed exactly one row before applying the remaining statements. If any later statement fails, the transaction rolls back the status change and the inventory release together.

The service also needs a defined lock order. For example, every command could lock the prescription first, then touch inventory rows in ascending `medication_id` order. A reservation command that locks inventory first and the prescription second can deadlock with a rejection command that uses the opposite order. Consistent ordering is a simple way to reduce that risk.

## Inventory Reservation Is A Database Invariant

Checking inventory and decrementing it later is not a reservation:

```text
read available_quantity = 1
if quantity <= available_quantity
    later decrement by 1
```

Two requests can both read `1`. The application has made a decision using a value that may already be stale.

For one medication row, the check and decrement can be one conditional update:

```sql
UPDATE inventory
SET available_quantity = available_quantity - :quantity,
    updated_at = CURRENT_TIMESTAMP
WHERE medication_id = :medication_id
  AND available_quantity >= :quantity
RETURNING medication_id, available_quantity;
```

The expression uses the current database value, not a value previously copied into the application. If the request needs five units and only four are available, no row is updated. The `CHECK (available_quantity >= 0)` constraint remains valuable as a second line of defense, but the conditional update prevents the normal overselling path.

If the workflow records a reservation, the decrement and reservation row must be in the same transaction:

```sql
BEGIN;

UPDATE inventory
SET available_quantity = available_quantity - :quantity,
    updated_at = CURRENT_TIMESTAMP
WHERE medication_id = :medication_id
  AND available_quantity >= :quantity
RETURNING medication_id, available_quantity;

-- If no row was returned, ROLLBACK and report insufficient inventory.

INSERT INTO inventory_reservations (
    prescription_id, medication_id, quantity, status
)
VALUES (
    :prescription_id, :medication_id, :quantity, 'RESERVED'
);

COMMIT;
```

If the reservation insert fails, the decrement is rolled back. If the process crashes after commit, both records are durable and a later workflow step can find the reservation.

For a prescription with multiple medication lines, reserve all lines in one transaction if the product rule is “all lines or none.” Process the medication IDs in a stable order and roll back the entire transaction when any conditional update returns no row. Otherwise a prescription can reserve some lines and fail on another, leaving recovery work that the small challenge did not intend to model.

Reservation timing is a product decision:

- A submission-time reservation protects stock while the prescription waits for approval.
- A check-only submission is simpler but cannot promise that stock remains available later.
- An approval-time reservation may be correct if approval should happen before stock is claimed, but approval then owns the inventory failure path.

Do not describe a check as a reservation in an interview. State exactly when the quantity changes and what happens if a later workflow step rejects the prescription.

## When A Conditional Update Is Enough

A conditional update is often enough when all of these are true:

- the invariant concerns one row or one atomic SQL operation;
- the new value can be calculated from the current row value;
- no related records must commit with it; and
- the caller can safely interpret zero affected rows as failure or conflict.

Examples include:

```sql
UPDATE prescriptions
SET status = 'PACKAGING', status_version = status_version + 1
WHERE id = :id AND status = 'APPROVED';
```

```sql
UPDATE inventory
SET available_quantity = available_quantity - :quantity
WHERE medication_id = :medication_id
  AND available_quantity >= :quantity;
```

These statements are atomic at the database boundary. They do not require the application to hold a stale object and calculate a replacement value.

A conditional update is not enough when the command must also:

- append status history;
- create an outbox event;
- create or release a reservation;
- update several related rows under an all-or-nothing rule; or
- make a decision based on multiple rows that must remain coordinated.

In those cases, put the statements in one transaction and retain the conditional predicate on the state-changing statement. A broader transaction does not replace a correct predicate.

## When `SELECT FOR UPDATE` Is The Better Tool

Sometimes the service must inspect a row and related data before choosing an action. A row lock makes that decision against a stable row version for the duration of the transaction:

```sql
BEGIN;

SELECT id, status, status_version
FROM prescriptions
WHERE id = :prescription_id
FOR UPDATE;

-- Inspect the locked row and related facts.
-- Apply the legal transition and related writes.

COMMIT;
```

The lock blocks another transaction that wants to update the same prescription until the first transaction commits or rolls back. This is useful when the business decision involves several dependent reads and cannot be expressed cleanly as one update.

It is not automatically better than a conditional update. It can hold locks for longer, increase contention, and make deadlocks easier to create when several rows are involved. Prefer the smallest operation that expresses the invariant:

- use a conditional `UPDATE` for a simple single-row transition;
- use `SELECT FOR UPDATE` when a multi-step decision needs a locked current row;
- use a consistent row order when locking multiple rows;
- keep the transaction short and never perform a broker or HTTP call while holding database locks.

The state predicate is still useful with `SELECT FOR UPDATE`. It documents the legal transition and protects against a future code path that changes the operation.

## Use `RETURNING` As The Write Result

PostgreSQL's `RETURNING` clause returns values from rows actually inserted, updated, or deleted. It is useful for concurrency code because the result of the write is also the result of the decision.

Without `RETURNING`, approval often becomes:

```text
UPDATE the row
SELECT the row again
construct an event from the second read
```

With `RETURNING`, the repository can return the new status, version, patient ID, and any database-generated values from the statement that won the race:

```sql
UPDATE prescriptions
SET status = 'APPROVED',
    status_version = status_version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE id = :id
  AND status = 'AWAITING_APPROVAL'
RETURNING id, patient_id, status, status_version, updated_at;
```

This avoids an unnecessary read and prevents the event-building code from accidentally using a later state. The returned row should be treated as the authoritative result of this command.

In a JDBC-based Kotlin repository, expose the operation as a meaningful result rather than leaking an arbitrary integer through the service:

```kotlin
data class AppliedTransition(
    val id: UUID,
    val patientId: UUID,
    val status: PrescriptionStatus,
    val version: Long
)
```

The repository can return `AppliedTransition?`. `null` means the conditional update found no applicable row. The service then maps that result to the domain outcome it needs. The exact JDBC or Spring data-access API is less important than preserving the distinction between “the database applied the transition” and “the command was not applicable.”

## Spring Transaction Boundaries

For a Kotlin/Spring service, the application-level command is a natural transaction boundary:

```kotlin
@Service
class PrescriptionService(
    private val prescriptions: PrescriptionRepository,
    private val events: OutboxRepository
) {
    @Transactional
    fun approve(id: UUID): ApprovalOutcome {
        val transition = prescriptions.approveIfAwaiting(id)
            ?: return ApprovalOutcome.InvalidState

        prescriptions.appendHistory(transition)
        events.insertApproved(transition)
        return ApprovalOutcome.Approved(transition.id)
    }
}
```

The repository methods must participate in the same transaction and connection. The service should not call an external RabbitMQ publisher before commit. The outbox row is the database-side handoff; a relay publishes it after the transaction succeeds.

Spring's usual `@Transactional` support is proxy-based. The call must enter the transactional method through the Spring-managed bean. A method calling another transactional method on `this` does not pass through the proxy, so it does not create the transaction boundary a reader may assume. Keep the boundary on a public application-service operation or structure the services so the call crosses the managed boundary.

The annotation also does not turn every exception into a business outcome. A known invalid transition can be represented as a result after the repository returns zero rows. A database outage should normally escape as an infrastructure failure so the transaction rolls back and the caller can retry or report an error. If the code catches a database exception, it must not continue issuing SQL in a transaction that PostgreSQL has already marked as failed.

For a small challenge, one clear `@Transactional` service method is easier to explain than a chain of hidden transactional repository methods. The important evidence is that the state update, history, reservation effect, and outbox insert share one commit.

## Transaction Failure Cases

A reviewer should be able to reason through these cases:

### The process dies before commit

PostgreSQL rolls back the open transaction. The prescription, history, inventory, and outbox changes from that transaction are not committed. The client may retry the command, so command idempotency and conditional state transitions still matter.

### The process dies after commit but before the HTTP response

The state change is durable even though the client did not receive success. A retry should observe the new state and return a safe result such as “already approved” or a conflict, according to the API contract. It must not create a second transition event merely because the first response was lost.

### The outbox insert fails

The transaction rolls back the state update. There is no committed approval claiming that a packaging event is ready when the outbox record was rejected.

### RabbitMQ is unavailable after the database commit

The prescription remains approved and the outbox row remains unpublished. The relay retries later. Holding the PostgreSQL transaction open while waiting for RabbitMQ would increase lock duration and still would not create an atomic database-to-broker commit.

### The second approval arrives after the first commits

Its conditional update affects zero rows because the status is no longer `AWAITING_APPROVAL`. The service must not append another history row or outbox event for that failed transition.

These cases show why affected-row handling is part of correctness, not just repository plumbing.

## Isolation Is Not A Substitute For Modeling

It is tempting to solve every concurrency concern by choosing `SERIALIZABLE`. PostgreSQL's serializable mode can be appropriate for a workflow that truly requires serial execution, but it can also abort transactions with serialization failures that the application must retry. It is not a replacement for writing a precise update predicate.

For this challenge, `READ COMMITTED` plus explicit conditional updates is usually a good baseline:

- simple state transitions use a state predicate;
- inventory uses a conditional arithmetic update;
- related facts use one transaction;
- row locks are used when a multi-step decision requires them;
- serialization or retry complexity is added only when the invariant needs it.

The choice should be stated as a workload decision, not as a claim that `READ COMMITTED` is universally safest.

## Prove The Behavior With PostgreSQL

Mocks can verify that a repository method was called. They cannot prove that two real transactions compete correctly. High-value integration tests for this article include:

1. Start two transactions that approve the same awaiting prescription. Assert that exactly one conditional update returns a row and exactly one approval history and outbox record exists.
2. Seed one available medication unit and submit two concurrent reservations. Assert that one succeeds, one reports insufficient inventory, and `available_quantity` is never negative.
3. Force the outbox insert to fail during approval. Assert that the prescription remains awaiting approval and no history row was committed.
4. Reject a prescription with a reserved line. Assert that the status is rejected, the reservation is released, and inventory is restored together.
5. Retry an approval after the first transaction committed but before its response was observed. Assert that the API behavior is deliberate and no duplicate transition event is created.
6. Lock rows in competing multi-medication operations in different requested orders. Verify the application uses a stable order and define how deadlock or retry errors are handled.

These tests should run against the PostgreSQL version used by the application, with the real migrations. An in-memory fake can make the service test fast, but it cannot reproduce PostgreSQL's row locking, `READ COMMITTED` recheck behavior, constraints, or `RETURNING` semantics.

## Interview Review Checklist

Be ready to answer:

- What exact predicate prevents two approvals from succeeding?
- What does an affected-row count of zero mean, and how do you classify it?
- Why is a read followed by an unconditional save unsafe?
- What does PostgreSQL `READ COMMITTED` guarantee for two concurrent updates?
- When is a conditional update enough without a broader transaction?
- Which approval, rejection, and reservation effects must commit together?
- Why does the outbox insert belong in the database transaction but the RabbitMQ publish does not?
- When would `SELECT FOR UPDATE` be clearer than a conditional update?
- What lock order do multi-medication operations use?
- What happens when a transaction commits but the HTTP response is lost?
- Which of these claims have been tested against real PostgreSQL?

## Interview Takeaway

Use PostgreSQL to make the winning operation explicit. Put the expected state in the `UPDATE` predicate, use affected rows or `RETURNING` to identify the winner, and keep every fact that describes one business command inside the same database transaction. `READ COMMITTED` is a practical default for this workflow when paired with precise writes; it is not permission to rely on stale application reads. For the pharmacy challenge, this is enough to defend approval, rejection, and inventory reservation without overbuilding the persistence layer.
