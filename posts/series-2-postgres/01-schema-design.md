# PostgreSQL Schema Design for a Workflow Product

The pharmacy challenge is small enough to tempt a single `prescriptions` table with a status column and a few JSON fields. That design is quick to demo and difficult to defend. It hides medication quantities, makes history unreliable, and leaves no durable place to coordinate database changes with RabbitMQ publication.

A better schema is still small. It stores the facts that must be queried or protected by the database, puts workflow history and integration records next to the business data, and leaves deliberately out-of-scope concepts outside the challenge.

The design in this post supports this path:

1. A patient submits a prescription with one or more medication lines.
2. The system verifies or reserves inventory.
3. A pharmacist approves or rejects the prescription.
4. Packaging and fulfillment workers advance the workflow asynchronously.
5. The patient reads current status and can later receive status events.

The goal is not a universal pharmacy data model. It is a minimal PostgreSQL model whose invariants can be explained in a Product Engineer interview.

## Design Around Invariants

Before writing DDL, write down what must never be false:

- Every prescription belongs to one patient.
- Every prescription line belongs to an existing prescription.
- A line has a positive quantity and a valid medication identifier.
- A prescription has one current status from the supported workflow.
- A status history row cannot refer to a different prescription.
- An event intended for publication has a stable unique ID.
- A consumer cannot apply the same event twice for the same consumer.
- Inventory quantities cannot become negative.
- A workflow change and its corresponding outbox event must commit together.

Some rules are local validation concerns. For example, Kotlin can reject a blank medication ID before persistence. Rules shared by concurrent requests belong in PostgreSQL. A Kotlin `val` cannot prevent two transactions from claiming the same inventory unit, and a service-level `if` cannot replace a foreign key.

This distinction also keeps the schema honest. The database protects facts that cross requests and processes; the application owns commands such as `approve` and `reject` rather than exposing a generic status setter.

## Keep The Core Tables Boring

For a time-boxed challenge, the core model can be represented by these tables:

- `prescriptions` stores the current workflow projection.
- `prescription_items` stores medication lines and quantities.
- `inventory` stores the quantity available for each medication.
- `inventory_reservations` records a claim when the design reserves stock before fulfillment.
- `prescription_status_history` stores the append-only user-visible trail.
- `outbox_events` stores events that must eventually be published.
- `inbox_events` makes message processing idempotent for each consumer.

There is no patient table or medication catalog in this version. Those may be owned by other systems. The challenge needs a patient identifier and a medication identifier, not a complete identity or catalog service. If those entities are owned locally, the identifiers can become foreign keys in a later migration.

## Prescription And Medication Lines

Use an application-generated UUID for the prescription. Generating it in Kotlin avoids requiring a PostgreSQL extension and gives the API a stable identifier before the first insert.

```sql
CREATE TABLE prescriptions (
    id                  uuid PRIMARY KEY,
    patient_id          uuid NOT NULL,
    status              text NOT NULL,
    status_version      bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT prescriptions_status_ck CHECK (
        status IN (
            'SUBMITTED',
            'AWAITING_APPROVAL',
            'APPROVED',
            'PACKAGING',
            'READY',
            'FULFILLED',
            'REJECTED'
        )
    )
);

CREATE TABLE prescription_items (
    prescription_id     uuid NOT NULL,
    line_number         integer NOT NULL,
    medication_id       uuid NOT NULL,
    quantity            integer NOT NULL,
    PRIMARY KEY (prescription_id, line_number),
    CONSTRAINT prescription_items_prescription_fk
        FOREIGN KEY (prescription_id) REFERENCES prescriptions (id),
    CONSTRAINT prescription_items_line_ck CHECK (line_number > 0),
    CONSTRAINT prescription_items_quantity_ck CHECK (quantity > 0),
    CONSTRAINT prescription_items_medication_uq
        UNIQUE (prescription_id, medication_id)
);
```

The composite primary key makes line identity local to a prescription. `line_number` is useful when displaying a submission and does not pretend to be a globally meaningful identifier. The additional unique constraint says that this model expects duplicate medication lines to be merged before persistence. If duplicate lines are meaningful, remove that constraint and use a separate line UUID instead.

The `status_version` column is not required for every implementation. It is a useful optimistic-concurrency hook: a transition can require the version it read and increment it when it wins. The next post will compare that approach with a conditional update based on the current status. Including the column now is reasonable if the application intends to expose a version in repository operations; adding unused concurrency fields is not automatically better.

The current status is intentionally stored on `prescriptions` even though history is also stored. Patient status reads should not reconstruct the latest state by scanning every history row. The current row is the fast, authoritative projection for the synchronous API; history explains how it got there.

## Inventory And Reservations

The smallest inventory table has one row per medication managed by this challenge:

```sql
CREATE TABLE inventory (
    medication_id       uuid PRIMARY KEY,
    available_quantity   integer NOT NULL,
    updated_at           timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT inventory_available_quantity_ck
        CHECK (available_quantity >= 0)
);
```

The check constraint is valuable even though the reservation code should also prevent a negative result. It turns a serious invariant violation into a failed transaction instead of silently persisting bad stock.

There are two different product decisions that are often called “inventory verification”:

- A check-only flow confirms that enough stock exists now, but stock can disappear before approval.
- A reservation flow claims stock during a transaction and later releases or consumes the claim.

For a workflow that may wait for pharmacist approval, reservation is easier to defend. A small reservation table provides an audit and recovery record without introducing batches, warehouses, or a full ledger:

```sql
CREATE TABLE inventory_reservations (
    prescription_id     uuid NOT NULL,
    medication_id       uuid NOT NULL,
    quantity            integer NOT NULL,
    status              text NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (prescription_id, medication_id),
    FOREIGN KEY (prescription_id) REFERENCES prescriptions (id),
    FOREIGN KEY (medication_id) REFERENCES inventory (medication_id),
    CONSTRAINT reservations_quantity_ck CHECK (quantity > 0),
    CONSTRAINT reservations_status_ck CHECK (
        status IN ('RESERVED', 'RELEASED', 'CONSUMED')
    )
);
```

The schema records the reservation state; it does not by itself make reservation atomic. The service still needs a transaction and an update that cannot produce a negative quantity. That implementation detail belongs in the concurrency article, but the schema should make the intended lifecycle representable.

If the two-hour version only verifies stock and does not reserve it, omit `inventory_reservations` rather than pretending verification provides a guarantee it cannot provide. State that limitation in the README and add the reservation table when the workflow requires a wait between submission and approval.

## Status History Is An Append-Only Fact

The current status answers “where is the prescription now?” History answers “what did the system say happened, and in what order?” That distinction matters for support, debugging, patient notifications, and future SSE replay.

```sql
CREATE TABLE prescription_status_history (
    prescription_id     uuid NOT NULL,
    sequence_number     bigint NOT NULL,
    status              text NOT NULL,
    reason              text,
    actor_type          text NOT NULL,
    occurred_at         timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (prescription_id, sequence_number),
    FOREIGN KEY (prescription_id) REFERENCES prescriptions (id),
    CONSTRAINT status_history_sequence_ck CHECK (sequence_number > 0),
    CONSTRAINT status_history_status_ck CHECK (
        status IN (
            'SUBMITTED',
            'AWAITING_APPROVAL',
            'APPROVED',
            'PACKAGING',
            'READY',
            'FULFILLED',
            'REJECTED'
        )
    )
);

CREATE INDEX prescription_status_history_lookup_idx
    ON prescription_status_history (prescription_id, sequence_number DESC);
```

The sequence is scoped to a prescription, not globally generated by an identity column. That gives one patient stream a monotonic ordering key without claiming that timestamps are perfectly ordered. The application must allocate the next sequence inside the same transaction as the state change. A later SSE design can use this value for event IDs or replay boundaries.

History should normally be append-only. Deleting a prescription with `ON DELETE CASCADE` would also delete the evidence needed to explain a patient-facing decision. For the challenge, it is safer to avoid destructive deletes and use test cleanup in a controlled database or namespace.

## Outbox Events Belong To The Same Commit

The dangerous implementation is still common:

```text
update prescription status
commit
publish RabbitMQ message
```

If the process crashes between those operations, the database says packaging should happen but no message may exist. Reversing the order creates the opposite failure: a message can describe a state change that rolled back.

The outbox table gives the database transaction a durable handoff point:

```sql
CREATE TABLE outbox_events (
    event_id            uuid PRIMARY KEY,
    aggregate_type      text NOT NULL,
    aggregate_id        uuid NOT NULL,
    event_type          text NOT NULL,
    payload             jsonb NOT NULL,
    occurred_at         timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    available_at        timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at        timestamptz,
    attempt_count       integer NOT NULL DEFAULT 0,
    last_error          text,
    CONSTRAINT outbox_attempt_count_ck CHECK (attempt_count >= 0)
);

CREATE INDEX outbox_unpublished_idx
    ON outbox_events (available_at, occurred_at)
    WHERE published_at IS NULL;

CREATE INDEX outbox_aggregate_idx
    ON outbox_events (aggregate_id, occurred_at);
```

When approval changes the prescription, the transaction inserts the status history row and the corresponding outbox event before commit. A relay later reads unpublished rows and publishes them to RabbitMQ with publisher confirms. A relay crash can still cause duplicate publication, so `event_id` must be stable and consumers need idempotency. The outbox removes the database-to-broker dual-write gap; it does not provide exactly-once delivery.

`payload` as `jsonb` is a pragmatic boundary for a small challenge. The event type and stable ID remain relational metadata used for routing and deduplication. If event payloads become a long-lived public contract, version them explicitly and validate them at the application boundary rather than allowing arbitrary JSON to become an undocumented API.

## Inbox Events Protect Consumer Retries

RabbitMQ delivery is at least once in the design. A consumer can commit its business effect and crash before acknowledging the message. The broker then redelivers it. The inbox table makes the duplicate visible to the database:

```sql
CREATE TABLE inbox_events (
    consumer_name       text NOT NULL,
    event_id            uuid NOT NULL,
    received_at         timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at        timestamptz,
    PRIMARY KEY (consumer_name, event_id)
);
```

The consumer inserts `(consumer_name, event_id)` and applies its business change in one transaction. If the primary key already exists, the consumer knows this delivery was already accepted for that consumer and can safely avoid repeating the effect. It acknowledges only after the transaction commits.

The consumer name is part of the key because two independent consumers may legitimately process the same event. `packaging-worker` and `status-projector` should not interfere with one another's deduplication records. An event ID alone would incorrectly treat those separate effects as duplicates.

## Foreign Keys And Uniqueness Are Product Decisions

Constraints are not database decoration. Each one communicates a decision:

- A foreign key from items to prescriptions prevents orphaned medication lines.
- A foreign key from reservations to inventory prevents reservations for unknown stock rows.
- The item uniqueness constraint defines how duplicate medications in one submission are handled.
- The history primary key prevents two events from claiming the same per-prescription sequence.
- The outbox primary key gives an event a stable identity across relay retries.
- The inbox primary key scopes idempotency to one consumer.

Be careful with constraints that encode an assumption the product has not made. A foreign key to a local `patients` table is wrong if patient identity is owned by another service and this application only stores an external ID. Likewise, a global unique constraint on `medication_id` in `prescription_items` would incorrectly prevent different prescriptions from using the same medication.

Database constraints should be paired with application error mapping. A duplicate submission may be a user-visible conflict, while an unexpected foreign-key violation is an engineering defect or an integration-data problem. Do not turn every SQL exception into a generic “try again” response.

## Migration Discipline

The schema is part of the application and should be created by versioned migrations, such as Flyway migrations run during deployment or application startup. A reasonable sequence is:

1. Create prescriptions and prescription items.
2. Add inventory and reservations if the chosen workflow reserves stock.
3. Add status history, outbox, and inbox tables.
4. Add indexes after confirming the first patient and worker query shapes.

In a real service, migrations should be forward-only and reviewed with the code that uses them. Prefer an expand-and-contract change when a table is already in production: add a nullable or compatible column, deploy code that can read both forms, backfill, switch writes, and remove the old form in a later migration.

Avoid relying on an application startup method that silently creates or alters tables. That makes the deployed schema difficult to audit and can allow two application instances to race during startup. `CREATE TABLE IF NOT EXISTS` is useful in disposable local experiments, but it is not a migration strategy.

For this challenge, the migration set should be small enough to read in one sitting. A reviewer should be able to start PostgreSQL with Docker Compose, run the migrations, seed inventory, and understand the schema without reverse-engineering ORM metadata.

## PostgreSQL Choices From A SQL Server Background

The conceptual transfer from SQL Server is strong, but several PostgreSQL details deserve deliberate attention:

- `uuid` is the PostgreSQL type commonly used where SQL Server uses `uniqueidentifier`.
- `timestamptz` represents an instant and is a safer default for workflow timestamps than an ambiguous timestamp without time zone. It does not preserve the original display time zone.
- `GENERATED ALWAYS AS IDENTITY` is the modern PostgreSQL identity syntax for database-generated numeric keys. It is preferable to older sequence-plus-`serial` habits for new tables.
- `boolean` is a real type, so a flag does not need SQL Server-style bit conventions.
- `jsonb` supports indexed binary JSON operations, but it should not replace relational columns that participate in core invariants.
- PostgreSQL has useful partial indexes, `RETURNING`, and `INSERT ... ON CONFLICT`. These are practical tools for queue-facing reads and idempotency, not merely syntax differences.
- PostgreSQL defaults to `READ COMMITTED`. A transaction sees a statement-level view of committed data, and later statements can observe changes committed after an earlier statement. The next post will apply that behavior to transitions and reservations.

The most important migration is not changing data types. It is changing the habit of assuming that a service-level read followed by a write is automatically safe. PostgreSQL gives precise transaction and locking tools, but the schema and SQL must use them intentionally.

## What Is Intentionally Omitted

This model does not include medication batches, expiry dates, multiple warehouses, insurance claims, pharmacist identity tables, audit signatures, a full event-sourcing model, or separate microservices. Those are plausible production concerns and poor defaults for a 2-5 hour challenge unless the requirements demand them.

It also does not make the outbox a second copy of every domain table. The outbox stores integration events, while the relational tables remain the source of truth for current workflow state. A status history row is not automatically an outbox row, even if one transition may create both records in the same transaction.

The design can grow later. A warehouse key can be added to inventory, a medication catalog can become a local reference table, and an event envelope can gain schema versions. Starting with fewer tables makes those changes easier to justify because the first version has clear ownership and invariants.

## Interview Review Checklist

Before presenting this schema, be able to answer:

- Which tables are authoritative for current status, history, integration publication, and consumer deduplication?
- Why is a status check different from an inventory reservation?
- Which constraints protect against malformed data, and which rules require a transaction?
- What happens if the outbox relay crashes after RabbitMQ accepts a message?
- Why is the inbox key `(consumer_name, event_id)` rather than just `event_id`?
- Why does a patient status read use the current prescription row instead of scanning history?
- Which tables would you add only if the product introduced warehouses, batches, or a local medication catalog?
- How would a migration change a production table without requiring a risky stop-the-world rewrite?

## Interview Takeaway

A defensible PostgreSQL schema for the pharmacy workflow is not the one with the most entities. It is the smallest model that makes ownership, history, uniqueness, idempotency, and durable handoff explicit. Keep current status easy to read, keep facts append-only where explanation matters, enforce cross-request invariants in the database, and commit outbox records with the business change. Then use the next layer of PostgreSQL knowledge, transactions and conditional updates, to make those constraints hold under concurrent demand.
