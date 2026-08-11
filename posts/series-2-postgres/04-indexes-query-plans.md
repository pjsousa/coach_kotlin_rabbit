# Indexes, Query Plans, and Queue-Facing Reads

A performance question in an interview is rarely about raw speed. It is about judgment: can you say which reads matter, prove what the database actually does for each one, and resist tuning things that have no workload behind them? The pharmacy challenge rewards exactly that judgment. The schema is small, the traffic is simulated, and almost every performance mistake a candidate can make comes from adding indexes before the query shapes exist.

The schema post left a deliberate hint: the migration order ends with "add indexes after confirming the first patient and worker query shapes." This post is that step. It walks through the real reads the challenge produces — patient status lookups, pharmacist queue polls, and the outbox relay poll — shows how to choose an index for each, how to verify the choice with `EXPLAIN ANALYZE`, and where to stop so a 2-5 hour challenge stays simple.

## Name The Reads Before The Indexes

An index is a response to a query, not a decoration. Before writing any DDL, write down the queries the application actually runs, with their predicates, ordering, and frequency. For this workflow there are four shapes that matter, and one that does not:

**1. Patient gets one prescription.** `WHERE id = :prescription_id`. Served by the primary key. Nothing to do. The mistake would be adding a second index on `id`.

**2. Patient lists recent prescriptions.** `WHERE patient_id = :patient_id ORDER BY created_at DESC LIMIT 20`. A patient-facing endpoint the product post will make central: this is the page a patient opens repeatedly while waiting.

**3. Pharmacist pulls the approval queue.** `WHERE status = 'AWAITING_APPROVAL' ORDER BY created_at, id LIMIT 25`. A poll: every pharmacist client, refreshed constantly, reading the same small slice of the table. The packaging worker later runs the analogous read on `'PACKAGING'`.

**4. Status history for one prescription.** `WHERE prescription_id = :id ORDER BY sequence_number`. Needed for the status timeline and, later, for SSE replay. The schema post already shipped an index for this shape.

**5. The outbox relay poll.** `WHERE published_at IS NULL AND available_at <= NOW() ORDER BY available_at, occurred_at LIMIT :batch`. Also already indexed in the schema post — a partial index — and the most queue-like read in the whole design.

Notice which read is not on the list: inventory lookups by `medication_id`. That is a primary key lookup on `inventory`, fast by definition, and the reservation write from the previous post never benefits from another index.

Write the queries down first because the alternative — indexing "what seems important" — produces indexes that get exercised once in a demo and add write cost forever. Every index on `prescriptions` is paid on every status transition, because PostgreSQL writes a new row version per update, and non-HOT updates touch every index on the table. An index that no query walks is a tax on the workflow's hottest writes.

## What An Index Actually Buys You

Coming from SQL Server, the B-tree concept transfers directly, but two PostgreSQL details do not:

- **PostgreSQL tables are heaps.** There is no maintained clustered order, as SQL Server has by default on its clustered index. The primary key in the schema is a unique B-tree index on heap row pointers, not an ordering of the table itself. "Make the patient list fast by clustering on `patient_id`" is a SQL Server instinct; in PostgreSQL `CLUSTER` is a one-time rewrite that the next writes undo, so it is not a design tool.
- **Sequential scans are not failures.** PostgreSQL's planner reads the whole table when it estimates that is cheaper, especially when the table is small or the filtered fraction is large. A `Seq Scan` node in a plan is information, not an error message.

An index helps when the query needs a small fraction of rows, needs them in a specific order, or both. The two questions to ask for each query shape are: how many rows will match, and does the index deliver the requested order without a sort?

## The Pharmacist Queue: A Partial Index

The approval queue query filters on `status` and orders on `created_at, id`. The natural reflex is a composite index:

```sql
CREATE INDEX prescriptions_status_created_idx
    ON prescriptions (status, created_at, id);
```

That index is correct, but think about what it contains: every prescription that ever existed, in status order. At steady state, maybe two percent of rows are `AWAITING_APPROVAL`; the rest of the index is never touched by this query. A partial index expresses exactly the subset the queue reads:

```sql
CREATE INDEX prescriptions_approval_queue_idx
    ON prescriptions (created_at, id)
    WHERE status = 'AWAITING_APPROVAL';
```

The benefits are concrete:

- The index is as small as the queue itself. Reads stay on a few pages even as the history of fulfilled prescriptions grows.
- Rows that enter the queue appear in the index in `created_at` order, so the `LIMIT 25` queue read returns rows in order with no sort node.
- The index's own writes are the churn that matters — and there is a tradeoff to name: every transition out of `AWAITING_APPROVAL` removes the row from this index (the new row version fails the predicate), so approvals write this index on the hot path. For a queue whose read frequency is far higher than its transition frequency, that is a good trade; say so instead of pretending indexes are free.

If a second worker queue exists — packaging reading `status = 'APPROVED'` — the same pattern gets its own partial index. Do not try to serve two queues with one `(status, created_at, id)` index "to save a file." Two small partial indexes are cheaper to maintain and each can be reasoned about independently.

The Kotlin repository then reads the queue with one statement and no ORM guesswork:

```kotlin
fun pendingApproval(cursor: PrescriptionCursor?, limit: Int): List<PrescriptionSummary> =
    jdbcClient.sql(
        """
        SELECT id, status, created_at
        FROM prescriptions
        WHERE status = 'AWAITING_APPROVAL'
          AND (:cursor_created_at IS NULL OR (created_at, id) > (:cursor_created_at, :cursor_id))
        ORDER BY created_at, id
        LIMIT :limit
        """
    )
        .param("cursor_created_at", cursor?.createdAt)
        .param("cursor_id", cursor?.id)
        .param("limit", limit)
        .query(PrescriptionSummary::class.java)
        .list()
```

## Patient List And History Reads

The patient list has the same shape as the queue but different characteristics: it filters on `patient_id`, a column with high cardinality, and orders by `created_at DESC` for the most recent page.

```sql
CREATE INDEX prescriptions_patient_created_idx
    ON prescriptions (patient_id, created_at DESC);
```

The composite covers both the equality predicate (`patient_id`) and the ordering (`created_at DESC`). The leading column rule applies exactly as it does in SQL Server: an index on `(created_at, patient_id)` would be useless here, because the `patient_id` predicate cannot be applied until the index has been scanned in time order. When in doubt, write the index for the query as written: equality columns first, then the ordering columns.

Status history already has its index from the schema post:

```sql
CREATE INDEX prescription_status_history_lookup_idx
    ON prescription_status_history (prescription_id, sequence_number DESC);
```

That shape is worth an `EXPLAIN ANALYZE` check because it is the one case where an index-only scan is plausible: if the columns the timeline needs are all in the index, PostgreSQL can answer from the index alone. That is a nice observation in an interview, but do not add `INCLUDE` columns to force it until a plan shows index fetches dominating.

## Reading The Plan: `EXPLAIN ANALYZE`

Claims like "the index makes it fast" are not evidence. The plan is the evidence. The command that settles an argument is:

```sql
EXPLAIN (ANALYZE, BUFFERS) SELECT id, status, created_at
FROM prescriptions
WHERE status = 'AWAITING_APPROVAL'
ORDER BY created_at, id
LIMIT 25;
```

Without the partial index, on a table with enough history to matter, the plan looks like this:

```text
Limit  (cost=1000.00..1035.90 rows=25 width=44) (actual time=2.4..2.6 rows=25 loops=1)
  ->  Gather Merge  (cost=1000.00..125836.03 rows=87541 width=44) (actual time=2.4..4.8 rows=25 loops=1)
        Workers Planned: 2
        ->  Sort  (cost=129.99..132.49 rows=1000 width=44) (actual time=1.9..1.9 rows=11 loops=3)
              Sort Key: created_at
              Sort Method: top-N heapsort
              ->  Parallel Seq Scan on prescriptions  (cost=0.00..129.99 rows=1000 width=44) (actual time=0.2..1.2 rows=32543 loops=3)
                    Filter: (status = 'AWAITING_APPROVAL'::text)
                    Rows Removed by Filter: 67457
```

Read it the way a reviewer would: a `Parallel Seq Scan` filtered the entire table, discarded 67,457 rows per worker, and a `Sort` imposed the queue order. Two properties stand out. First, `rows=1000` in the cost estimate versus `rows=32543` actual — a large estimate mismatch, because the planner's statistics were never updated for this distribution; run `ANALYZE` after seeding, and let autovacuum keep it fresh, or every plan discussion starts from stale numbers. Second, the work is proportional to the whole table, and the table only grows.

With the partial index in place:

```text
Limit  (cost=0.29..1.64 rows=25 width=44) (actual time=0.045..0.093 rows=25 loops=1)
  ->  Index Scan using prescriptions_approval_queue_idx on prescriptions  (cost=0.29..37.64 rows=875 width=44) (actual time=0.045..0.090 rows=25 loops=1)
```

One `Index Scan`, no filter, no sort: the index supplies both the subset and the order, and `rows` now matches reality because the index is only as large as the queue. The partial predicate (`status = 'AWAITING_APPROVAL'`) is implied by the index and does not appear as a filter node.

The discipline for reading any plan: look at the top-level `actual time`, look for `Seq Scan` over a filter, look for a `Sort` node that an ordered index should have eliminated, and compare estimated `rows` with actual `rows`. A plan that says "I guessed 1000 and found 32543" is telling you the statistics are stale before it tells you anything about the index.

## Pagination: Why OFFSET Degrades And Keyset Does Not

The pharmacist client pages through the queue. The tempting implementation is `OFFSET :page * 25 LIMIT 25`, and it is the one habit worth breaking deliberately:

- Every page re-scans the first `page * 25` rows and discards them. Page 200 reads 5,000 rows to return 25, and the queue is being polled by several clients, constantly.
- **New rows shift the window.** A prescription submitted between page requests can cause rows to be duplicated or skipped across pages — a correctness issue in a queue read, not merely a performance one.
- The index can serve a keyset predicate directly, because the queue is already ordered by `(created_at, id)`.

The keyset form, shown in the repository code above, uses the row-comparison tuple `(created_at, id) > (:cursor_created_at, :cursor_id)`. The cursor is the last row's `(created_at, id)`; the next page starts exactly after it. Stable under new submissions, bounded work per page, and the plan stays a narrow `Index Scan`.

Keyset pagination deserves one honesty note: it requires the API to expose the cursor, and it does not support arbitrary "jump to page 37" links. For a pharmacist queue, that is the right trade and an easy explanation. For a patient-facing history that needs page numbers, say why offset pagination was acceptable instead.

## Queue-Facing Reads And `SKIP LOCKED`

The outbox relay is the most queue-like read in the system, and its index already exists from the schema post:

```sql
CREATE INDEX outbox_unpublished_idx
    ON outbox_events (available_at, occurred_at)
    WHERE published_at IS NULL;
```

The relay's poll is a queue read with a concurrency twist: when two relay instances poll at once, both must not claim the same rows. The standard pattern is `FOR UPDATE SKIP LOCKED`, which the RabbitMQ series will cover in depth. What matters here is the index design interaction: `SKIP LOCKED` follows the `ORDER BY` and `LIMIT`, so an index ordered by `(available_at, occurred_at)` scoped to unpublished rows is exactly what keeps that read narrow while rows are being claimed. The same reasoning applies to any worker table poll. A queue read without a queue-shaped index is the most common performance surprise in these designs, because the table looks small in a demo and only reveals itself under accumulated history.

## Connection Pooling Is A Query Shape Problem

Spring Boot ships HikariCP with a sensible default pool of ten connections. The interview-ready mental model is that pool tuning starts from hold time, not from the number 10:

- A connection is a scarce resource held for the whole transaction. A long transaction — an HTTP call, a RabbitMQ publish, a slow loop of repository calls — holds a pool slot for its entire duration.
- When every slot is held, new requests queue on `connectionTimeout` (Hikari default 30 seconds) and fail with "Connection is not available, request timed out." That message is a hold-time diagnosis: something is sleeping on a connection, usually a blocking call inside `@Transactional`, not a pool-size problem.
- Pool size is not throughput. Ten connections can saturate far more work than one hundred held by slow transactions. The classic sizing rule of thumb — `(2 × cores) + effective spindles` — is a starting point, and for a challenge the default is defensible. Measure pool wait time before changing it.
- Set `leakDetectionThreshold` (for example 30 seconds) in development so a transaction that forgets to finish becomes a logged violation instead of a mystery. This is the same failure the transactions post described, observed from the pool's side.

The N+1 problem is this principle at the repository level: a loop that fetches each prescription line with its own query multiplies round trips and holds the connection longer for no correctness benefit. Fetch the lines for a prescription in one `WHERE prescription_id IN (:ids)` query, or read the whole aggregate with the row-comparison and ordering tools above. A reviewer who sees a query loop inside `@Transactional` will ask the question before the pool ever does.

## What Not To Optimize Prematurely

The post would be incomplete without naming the other direction, because the challenge rewards simplicity more than it rewards speed:

- **Do not index before the query exists.** Every index above was chosen for a read written down in advance. The moment an index has no query shape, it is a write tax.
- **Do not tune against a fake.** Plans from an in-memory database or a mocked repository are fiction. The testing post will make the rule explicit: index choices are validated with `EXPLAIN ANALYZE` against real PostgreSQL.
- **Do not reach for covering indexes, partitions, or materialized views without evidence.** The pharmacist queue at challenge scale is hundreds of rows. A `Seq Scan` on a 300-row table with warm cache is a non-event; the plan's structure matters for the explanation, not the milliseconds.
- **Do not rewrite correct code to avoid an index write.** The conditional-update transitions from the transactions post are the right design; a partial index's churn is small and bounded. Indexes serve the reads; the transactions serve the invariants, and the invariants win.
- **Do not add "both directions" indexes.** `DESC` on `created_at` in the patient index is deliberate; a second index with every ordering reversed is a reflex, not a decision.
- **Do not forget statistics.** After seeding test data, run `ANALYZE` before judging a plan. Autovacuum handles production; a fresh Docker volume holds whatever stale numbers the seed left behind.

## Interview Review Checklist

- Which reads exist in this system, and which ones need an index beyond the primary key?
- Why is a partial index the right tool for the pharmacist queue, and what does it cost on the approval transition?
- What is the leading-column rule, and why would `(created_at, patient_id)` be wrong for the patient list?
- Walk through an `EXPLAIN ANALYZE` output: what does a `Seq Scan` with a `Filter` tell you, and what does a `Sort` node tell you about an index?
- What does a large mismatch between estimated and actual rows mean, and what fixes it?
- Why does `OFFSET` pagination both slow down and change the contents of a queue page, and how does the keyset cursor fix both?
- How does `SKIP LOCKED` interact with the outbox index, and why must the poll follow the index order?
- Your pool is exhausted at default size 10. What is the diagnosis sequence, and when would you change the pool size versus the transaction?
- What is the N+1 pattern, and where does it show up in prescription reads?
- Which performance work did you deliberately not do, and what is the query shape that would justify it?

## Interview Takeaway

Index work starts with the query shapes, not the schema tour: patient lookups by primary key, the patient list on `(patient_id, created_at DESC)`, the pharmacist queue on a partial index that mirrors the queue itself, and the outbox poll on its partial index. Each choice is validated with `EXPLAIN ANALYZE`, which reads like an audit: no full-table filter, no sort the index should have removed, estimates that match reality. Pagination uses keysets, pools are managed by hold time rather than size, and everything else stays deliberately unoptimized until a real query shape demands it. That is the judgment a Product Engineer interview is testing: knowing which read deserves an index, proving it against the plan, and leaving the rest alone.
