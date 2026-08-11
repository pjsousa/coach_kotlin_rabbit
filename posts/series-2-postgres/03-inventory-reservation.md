# Inventory Reservation Without Overselling

One unit of Amoxicillin remains in stock. Two prescriptions for it arrive at nearly the same time. What the system does in the next few milliseconds is the sharpest test of the pharmacy challenge's persistence design. If the decision is made in Kotlin from a value read earlier, both requests can be told the medication is available, and the pharmacy has promised a patient something it cannot deliver. In this domain that is not a cosmetic race condition; it is a broken patient promise that surfaces hours later at the fulfillment counter.

The schema post introduced the `inventory` and `inventory_reservations` tables. The transactions post introduced conditional updates, affected-row counts, and `READ COMMITTED`. This post applies both to the highest-risk invariant in the challenge and goes one layer deeper: what PostgreSQL actually does when two transactions compete for one row, how to choose between an atomic update and a row lock, how multi-medication prescriptions create deadlocks, and how the reservation survives crashes and retries.

## State The Invariant Before The SQL

"Don't oversell" is a slogan, not a specification. Before choosing any SQL, write down the rules the design must keep true:

- `available_quantity` never becomes negative.
- Across any number of concurrent requests for the same medication, the total quantity successfully reserved never exceeds the quantity that was available.
- A reservation that commits is durable and discoverable by the later workflow steps that approve, reject, or fulfill the prescription.
- Rejection releases the claim exactly once. Fulfillment consumes it exactly once. Neither happens twice, and neither silently fails to happen.

There is also a product distinction an interviewer will probe. Verification says "stock exists at this moment"; reservation says "this stock is claimed for this prescription." A prescription that waits in a pharmacist queue needs reservation: approval that can no longer be fulfilled is a worse patient experience than a clear rejection at submission. If the time-boxed version only verifies, say so plainly. Do not call a check a reservation.

## Implementations That Lose The Last Unit

It is worth being precise about why the common approaches fail, because interviewers often present them as a starting point.

### Read-Check-Write In The Application

```kotlin
val available = inventoryRepository.findQuantity(medicationId)
if (available >= line.quantity) {
    inventoryRepository.updateQuantity(medicationId, available - line.quantity)
}
```

Two transactions both read `1`, both pass the check, and both write `0`. The database now shows zero stock against two committed reservations. This is a classic lost update, and wrapping the code in `@Transactional` does not fix it: the decision was made from a stale value, and no database constraint was consulted at write time. The Kotlin `if` is validation for user feedback, never a guarantee.

### Unconditional Decrement Relying On The Constraint

```sql
UPDATE inventory
SET available_quantity = available_quantity - :quantity
WHERE medication_id = :medication_id;
```

This at least computes from the current database value. With one unit left and two concurrent requests, the second update waits for the first, then decrements the new row version to `-1`, and the `CHECK (available_quantity >= 0)` constraint aborts it with SQLSTATE `23514`. The invariant survives, but a routine business situation — insufficient stock — surfaces as a database exception. The loser gets a constraint violation instead of a clean "insufficient inventory" outcome, and the error-mapping code can no longer distinguish "not enough stock" from "a bug wrote bad SQL."

### Application-Level Locks

A `synchronized` block or a `ReentrantLock` serializes threads inside one JVM. It does nothing against a second instance of the service, a second deployment during a rollout, or a manual operational script. Any design whose correctness requires exactly one application instance is fragile, and saying so in an interview signals that you know where concurrency control actually lives.

### `SERIALIZABLE` As The Default Reflex

Serializable isolation would prevent the anomaly, but it does so by aborting one transaction with a serialization failure (SQLSTATE `40001`) and forcing the application to retry. For a single hot row, that is machinery without benefit: a conditional update under `READ COMMITTED` already provides the guarantee, deterministically, with no retry loop. Serializable isolation is the right tool for multi-row invariants that cannot be expressed in one statement. Reaching for it first suggests the predicate was never written down.

## The Atomic Decrement Under `READ COMMITTED`

The reservation itself is one statement:

```sql
UPDATE inventory
SET available_quantity = available_quantity - :quantity,
    updated_at = CURRENT_TIMESTAMP
WHERE medication_id = :medication_id
  AND available_quantity >= :quantity
RETURNING medication_id, available_quantity;
```

Everything the invariant needs is inside it: the new value is computed from the row's current database value, and the predicate is evaluated against the row version actually being updated — not against a value the application saw earlier.

Here is what PostgreSQL does when two such statements race for the last unit:

```text
Transaction A                          Transaction B
---------------                        ---------------
BEGIN;                                 BEGIN;
UPDATE inventory ... >= 1
  finds available_quantity = 1
  acquires the row lock
  writes available_quantity = 0
                                       UPDATE inventory ... >= 1
                                         finds the row, tries to lock it
                                         waits on A's row lock
COMMIT;
                                         lock released; A committed
                                         re-evaluates WHERE against the
                                         newest committed row version
                                         available_quantity = 0
                                         0 >= 1 is false
                                         updates zero rows
```

Several properties of this exchange deserve attention:

- **Writers to the same row serialize on that row's lock.** PostgreSQL coordinates the race; the application does not.
- **The loser's predicate is re-evaluated after the wait.** Under `READ COMMITTED`, when an `UPDATE` unblocks after a concurrent commit, PostgreSQL re-checks the `WHERE` clause against the latest committed version of the row (the mechanism known internally as EvalPlanQual). The loser does not apply a stale decision; it simply matches zero rows.
- **Plain readers are not involved.** PostgreSQL's MVCC means a patient polling their prescription status never waits behind a reservation update. Coming from SQL Server, this is a real difference: under SQL Server's default `READ COMMITTED` without row-versioning, readers can block behind writers. PostgreSQL readers do not block on row locks, and PostgreSQL does not escalate row locks to table locks under contention.
- **There is no fairness promise.** If five transactions queue on the row, the design should rely only on "exactly enough of them win," never on which one wins first.

The `CHECK` constraint stays in the schema as a second line of defense: it converts a future buggy writer into a failed transaction instead of silently persisted negative stock. It is a safety net, not the mechanism.

## Affected Rows Are The Business Outcome

The statement's result is the decision:

- **One row returned** — this request claimed the units. The workflow continues in the same transaction.
- **Zero rows** — no units were claimed and nothing was changed. The medication is unknown or the stock is insufficient.

Do not pre-read the quantity "to produce a better error." A pre-read is stale by construction and adds a round trip. If the API must distinguish `404 Not Found` (unknown medication) from `409 Conflict` (insufficient stock), perform that classification read after the failed update and treat it strictly as error mapping. The decision was already made by the update.

In the Kotlin repository, keep that distinction intact rather than leaking a bare integer:

```kotlin
fun tryReserve(medicationId: UUID, quantity: Int): ReservationResult? =
    jdbcClient.sql(
        """
        UPDATE inventory
        SET available_quantity = available_quantity - :quantity,
            updated_at = CURRENT_TIMESTAMP
        WHERE medication_id = :medication_id
          AND available_quantity >= :quantity
        RETURNING medication_id, available_quantity
        """
    )
        .param("medication_id", medicationId)
        .param("quantity", quantity)
        .query(ReservationResult::class.java)
        .optional()
        .orElse(null)
```

`null` means the claim did not happen. The service maps it to a domain outcome; it does not retry it, because zero rows is an answer, not an accident.

## One Transaction For Decrement, Reservation, And Workflow Facts

The decrement alone is not the whole command. A submission-time reservation for the challenge workflow needs, in one database transaction:

1. For each medication line, processed in a stable order such as ascending `medication_id`: the conditional decrement. If any line returns no row, nothing may commit.
2. One `inventory_reservations` row per line with status `RESERVED`.
3. The `prescriptions` row, its items, the first status-history row, and any submission outbox event.

The Kotlin shape:

```kotlin
@Service
class SubmissionService(
    private val inventory: InventoryRepository,
    private val reservations: ReservationRepository,
    private val prescriptions: PrescriptionRepository
) {
    @Transactional
    fun submit(command: SubmitPrescription): UUID {
        for (line in command.lines.sortedBy { it.medicationId }) {
            inventory.tryReserve(line.medicationId, line.quantity)
                ?: throw InsufficientStockException(line.medicationId)
            reservations.insertReserved(
                command.prescriptionId, line.medicationId, line.quantity
            )
        }
        prescriptions.insertSubmitted(command)
        return command.prescriptionId
    }
}
```

Two details in this shape carry real interview weight:

- **All lines or none.** If line three of four lacks stock, the first two decrements must roll back. A partial reservation is recovery work the challenge never agreed to model. Note the trap in the code above: a Kotlin `return` from a `@Transactional` method commits. Escaping with a domain exception (mapped to `409` at the API boundary) is what makes Spring roll back. If the team prefers returning outcome objects over exceptions, mark the transaction rollback-only before returning the insufficient-stock outcome. Returning an outcome object is only safe when no writes have happened yet — as in the approval example from the transactions post, where the conditional update was the first statement.
- **Stable line order.** Sorting by `medication_id` is not cosmetic; it is the deadlock-avoidance rule discussed below.

If the reservation insert itself fails — a programming error, a violated foreign key — the decrement rolls back with it. The database never contains a stock change with no corresponding claim.

## Optimistic, Pessimistic, Or Neither

Interviewers like the optimistic-versus-pessimistic framing. The honest answer for this invariant is that the chosen design is neither classic pattern; it is a single atomic statement. The comparison is still worth rehearsing:

- **Conditional update (chosen).** No prior read, no lock held beyond the statement, the predicate inside the write. Ideal when the new value derives from the current row value — arithmetic on quantity is the canonical case. Under contention, losers do not retry; they get a definitive zero-row answer.
- **`SELECT FOR UPDATE` (pessimistic).** Lock the row, inspect it and related rows, then decide and write. Choose this when the decision genuinely needs a stable view that one statement cannot express — for example, allocation logic that inspects several inventory rows before choosing which to claim. The costs are real: the lock is held for the whole decision, contention on a hot row grows, every additional locked row widens the deadlock surface, and no broker or HTTP call may happen while the lock is held. If the locked read is followed by an update that does not touch key columns, `FOR NO KEY UPDATE` expresses exactly that and conflicts with fewer other operations — unlike `FOR UPDATE`, it does not block foreign-key checks that take `FOR KEY SHARE` on the row.
- **Version column with retry (classic optimistic).** Read the row with version `N`, then `UPDATE ... WHERE version = N`. This fits whole-entity transitions where the application must compute a new state from many fields. It is a poor fit for a hot inventory row: under contention most attempts lose and must re-read and retry, generating the most round trips exactly when the row is busiest.

The framing that lands well in an interview: use the weakest tool that expresses the invariant, and be able to say precisely what the stronger tools buy and cost.

## Deadlocks And How To Tame Them

A prescription often has several medication lines, and lines arrive in whatever order the patient submitted. Two concurrent submissions requesting the same two medications in opposite orders produce the textbook deadlock:

```text
Transaction A (lines M1, M2)           Transaction B (lines M2, M1)
---------------------------            ---------------------------
UPDATE inventory ... M1
  -- holds row lock on M1
                                       UPDATE inventory ... M2
                                         -- holds row lock on M2
UPDATE inventory ... M2
  -- waits for B's lock on M2
                                       UPDATE inventory ... M1
                                         -- waits for A's lock on M1
                                         -- deadlock detector fires
                                         -- (deadlock_timeout, default 1s)
                                       ERROR: deadlock detected
                                       SQLSTATE 40P01
```

PostgreSQL does not leave the transactions stuck: after `deadlock_timeout` of waiting, its deadlock detector finds the cycle and aborts one transaction so the other can proceed. The error is logged with the full lock-dependency chain.

The engineering response has three parts:

1. **Prevent by ordering.** Every command that touches inventory rows — reserve, release, consume — locks them in the same global order, such as ascending `medication_id`. Consistent ordering converts deadlocks into orderly waiting. This is the single most effective measure and costs one `sortedBy` in Kotlin.
2. **Keep transactions short.** No logging HTTP calls, no message publication, no streaming work inside the transaction. Lock duration is contention duration.
3. **Retry deliberately.** SQLSTATE `40P01` is safe to retry as a whole command, a small bounded number of times, because the aborted transaction committed nothing. This is a different category from a zero-row insufficient-stock outcome, which must never be retried — it is the correct answer. A rising deadlock count in production metrics is a signal that some code path broke the ordering rule, not a fact of life to tolerate.

## Recovery Across The Reservation Lifecycle

Reservation correctness is not only the moment of the race. Walk the full lifecycle and name the result of every crash:

- **Crash before commit.** PostgreSQL rolls back the open transaction. No decrement, no reservation row, no prescription. The patient retries safely.
- **Crash after commit, before the HTTP response.** The reservation is durable. Because the client supplies the prescription UUID, a retry hits the primary key on `prescriptions` and on `inventory_reservations` (SQLSTATE `23505`), which the API maps to a deliberate "already submitted" outcome — ideally returning the current state. The unique constraint is the idempotency mechanism; there is no double reservation and no double decrement.
- **Pharmacist rejects.** The release is atomic with the rejection transition, as the transactions post showed: inventory restored, reservation marked `RELEASED`, history and outbox written, one commit. The release statement carries `AND status = 'RESERVED'`, so a second release attempt affects zero rows and cannot restore the stock twice. Exactly-once effect, built from affected-row discipline rather than from exactly-once delivery.
- **Fulfillment completes.** The reservation becomes `CONSUMED`, and — the subtle point — no further decrement happens. The units left `available_quantity` at reservation time. Consuming is a status change on the reservation, not a second inventory change. A design that decrements at fulfillment after decrementing at reservation has double-counted.
- **Prescription is never decided.** The stock stays reserved. Production systems add expiry: a sweeper that releases stale `RESERVED` rows past a deadline, with care not to release a reservation that is concurrently being consumed. For a 2-5 hour challenge, the honest move is to document the limitation and, if anything, expose a simple operational release endpoint. Naming the tradeoff is the interview answer; hiding it is not.

## Proving It Against Real PostgreSQL

None of this is provable with a mock repository. Against real PostgreSQL with real migrations — Testcontainers, as the testing post will cover — the high-value tests are:

1. **Race for the last unit.** Seed `available_quantity = 1`; run two reservation transactions concurrently with a latch-synchronized start; assert exactly one success, one insufficient-stock outcome, and a final quantity of zero.
2. **N racers.** Seed `5`, fire twenty concurrent single-unit requests, assert exactly five wins and a final quantity of zero — never negative, in any run.
3. **All-or-nothing.** Submit a two-line prescription where line two lacks stock; assert line one's decrement was rolled back and no reservation rows exist.
4. **Release exactly once.** Reject a reserved prescription; attempt the release twice, including concurrently; assert inventory was restored exactly once.
5. **Retry after a lost response.** Submit, commit, discard the response, then submit again with the same prescription ID; assert one prescription, one reservation, one decrement, and a deliberate conflict outcome.
6. **Lock ordering.** Submit concurrent multi-line prescriptions with reversed line order and assert completion; separately, define and test the bounded retry behavior for `40P01` so a future ordering violation degrades gracefully.

A unit test with a fake repository still has its place: it checks the Kotlin mapping from zero rows to the insufficient-stock outcome. It cannot demonstrate any of the six behaviors above, and presenting it as if it does is an overclaim an interviewer can dismantle in one question.

## Interview Review Checklist

Be ready for these concurrency questions:

- One unit remains and two requests arrive together. Walk through exactly what PostgreSQL does, statement by statement.
- Why is the Kotlin-side `if (available >= quantity)` check not a guarantee, even inside a transaction?
- What does the losing request observe, and which HTTP status do you return? How do you distinguish unknown medication from insufficient stock?
- Does preventing overselling require `SERIALIZABLE`? Why not?
- If the update predicate already prevents negative quantities, what does the `CHECK` constraint add?
- When would you switch to `SELECT FOR UPDATE`, and what does it cost in contention and deadlock surface?
- Why is a version-column optimistic approach a poor fit for a hot inventory row?
- How do you prevent deadlocks when a prescription reserves multiple medications?
- Which database errors do you retry, and which do you map directly to a business response?
- The process crashes after the reservation commits but before the patient sees a response. What does the client's retry do, and why?
- How does rejection restore stock exactly once? What stops a double release?
- Why does fulfillment not decrement inventory again?
- Does a patient status read block behind a reservation update? Why not?
- What happens to reserved stock if a prescription is never approved or rejected, and what would you add in production?
- Which of these claims are covered by tests against real PostgreSQL?

## Interview Takeaway

Overselling is prevented by putting the invariant inside the write: `available_quantity >= :quantity` in the same statement that decrements, under plain `READ COMMITTED`, with the affected-row result mapped to a domain outcome. The reservation record, prescription, history, and outbox event commit in the same transaction; multi-row updates follow a global lock order; release and consume are affected-row-disciplined transitions of the reservation. The strongest interview signal is not the SQL itself — it is the ability to narrate the two-transaction timeline, state exactly what the loser observes, and describe the recovery result for every crash window in the reservation's lifecycle.
