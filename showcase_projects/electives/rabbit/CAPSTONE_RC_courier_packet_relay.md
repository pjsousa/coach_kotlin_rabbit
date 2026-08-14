# RC Courier Packet Relay — Track Capstone (Code-Along)

## Objective

Synthesize the whole RabbitMQ track into one small, running system: a neighborhood courier drop network where a dispatcher requests pickups over RabbitMQ and couriers deliver packets to customers. You will build the topology, the confirmed publisher, the manual-ack consumer with retry/DLQ topology, and an inbox — then deliberately reproduce the dual-write loss, and close it with a transactional outbox relay. By the end you hold a loss ledger, a duplicate window you reproduced twice, and a one-paragraph at-least-once / exactly-once-effect statement you can defend aloud. Primary objective: prove the whole track works *as one story*, not as seven isolated labs — and let the interview collapse any single R-kata question into "show me the evidence in the relay."

## Time box

~6–8 hours, budgeted: **M1 ~1.5h, M2 ~2.5h, M3 ~2.5h, M4 ~1h.** The crash experiments (M3 part 1, M4) will eat every spare minute — protect them. Nothing here is a production capacity claim; every milestone is a *local* correctness demonstration.

## Prerequisites

### Track electives that should be complete

Per the gate rule in [`../README.md`](../README.md#gate-rule): finish the R-track labs before this capstone — or knowingly skip with a written waiver in the checklist below. The capstone forces every one of them.

| ID | Title | Link |
|---|---|---|
| R01 | Topology scratchpad | [`R01_topology_scratchpad.md`](R01_topology_scratchpad.md) |
| R02 | Fire-and-forget publisher | [`R02_fire_and_forget_publisher.md`](R02_fire_and_forget_publisher.md) |
| R03 | Manual ack consumer | [`R03_manual_ack_consumer.md`](R03_manual_ack_consumer.md) |
| R04 | Poison to DLQ | [`R04_poison_to_dlq.md`](R04_poison_to_dlq.md) |
| R05 | Idempotent consumer | [`R05_idempotent_consumer.md`](R05_idempotent_consumer.md) |
| R06 | Dual-write failure demo | [`R06_dual_write_failure_demo.md`](R06_dual_write_failure_demo.md) |
| R07 | Outbox relay mini | [`R07_outbox_relay_mini.md`](R07_outbox_relay_mini.md) |

Also useful, in order of value: [`../glue/X01_docker_compose_trio.md`](../glue/X01_docker_compose_trio.md) (the stack), [`../postgres/P03_approve_once_race.md`](../postgres/P03_approve_once_race.md) (row-count habit), [`../postgres/P07_testcontainers_postgres.md`](../postgres/P07_testcontainers_postgres.md) (turn the race evidence into a test).

### Tools

- JDK 17+
- Docker Compose (RabbitMQ `3.13-management` on 15672 + Postgres 16 on 5432)
- RabbitMQ Management UI (guest/guest) — your evidence camera
- Gradle (any recent 8.x; or the wrapper)
- Two terminals, and the will to `kill -9` your own app

### Position vs showcase

Before/during [`../../pharmacy-fulfillment/exercise_03_production.md`](../../pharmacy-fulfillment/exercise_03_production.md) — this capstone *is* Milestones 1–5 of that exercise in a geek domain, at minimal scope. Do it before Ex3 M1–M2 (outbox) and M4–M5 (retry/DLQ + idempotency); return to the showcase with the evidence vocabulary already warm.

## Blog & curriculum links

- [`../../../posts/series-3-rabbitmq/01-amqp-topology.md`](../../../posts/series-3-rabbitmq/01-amqp-topology.md) — work vs fact, durability, the topology you will adapt for zones.
- [`../../../posts/series-3-rabbitmq/02-publisher-confirms-outbox.md`](../../../posts/series-3-rabbitmq/02-publisher-confirms-outbox.md) — the Four Moments; the outbox table and relay loop.
- [`../../../posts/series-3-rabbitmq/03-ack-prefetch-concurrency.md`](../../../posts/series-3-rabbitmq/03-ack-prefetch-concurrency.md) — ack-after-effect rule, prefetch math, redelivery.
- [`../../../posts/series-3-rabbitmq/04-retries-dead-letters.md`](../../../posts/series-3-rabbitmq/04-retries-dead-letters.md) — the three fates, x-death budget, DLQ forensics.
- [`../../../posts/series-3-rabbitmq/05-idempotency-ordering.md`](../../../posts/series-3-rabbitmq/05-idempotency-ordering.md) — the inbox, the claim transaction, the four duplicate sources.

Read the tied post section before each milestone, then re-read it after — the second pass is where the interview sentences form.

## Background & motivation

The R-labs were deliberately isolated: R01 had no consumers, R03 had no retries, R05 had no relay. Real systems do not arrive that way. This capstone makes you hold seven contracts in one process at the same time, which is exactly where the showcase's reliability story lives and where interviews go next. The domain is a neighborhood courier drop network — shippers drop packets, a dispatcher asks for pickups, couriers accept and deliver to customers — not pharmacy fulfillment, so the *shape* of the reliability argument stays pure while you practice it on geeky ground.

The through-line you will finish with: the dispatcher's "packet was picked up" fact and the courier's "delivered to the right house" effect must both survive crashes, and a duplicate pickup request must never result in the same customer getting the same packet handed over twice. A package delivered to the wrong house twice is your capstone's version of a patient receiving a double dose — same shape, better snacks. RabbitMQ delivers at-least-once; the database makes the effect at-most-once; the outbox makes the publish survivable; and the honest sentence is *exactly-once effect under at-least-once delivery*, never "exactly-once."

## Skill checklist (mandatory)

Each prior elective is forced by a concrete capstone behavior below. Mark **pass / skip + waiver** for every line as you complete it (waiver = you skipped the lab and wrote why the capstone still covers you, or explicitly accept the gap). This table is your proof of synthesis.

| ID | Required capstone behavior (verbatim) | Where forced | Verdict |
|---|---|---|---|
| R01 | Declared durable topology in one config class: pickups exchange + zone/neighborhood bindings + DLQ; prove it exists and survives a broker restart via Management UI/HTTP API evidence; publish to a missing exchange produces a visible channel error | M1 steps 1–3, Try this #1 | pass / skip + waiver |
| R02 | Publisher to explicit exchange + routing key with publisher confirms correlated to eventId; negative case (broker stopped) logged; persistent messages survive restart while unconsumed | M1 steps 4–6 | pass / skip + waiver |
| R03 | Courier consumer: manual ack after a durable effect (customer delivery record), bounded prefetch, deliberate concurrency; kill -9 between effect and ack → visible redelivery with redelivered = true and a second effect run | M2 steps 1–2 | pass / skip + waiver |
| R04 | Poison packets: classified retryable-vs-permanent; delayed retry queue with budget read from x-death; permanent failures land in DLQ on attempt one; healthy packets flow while poison is parked (ready-count evidence) | M2 steps 3–4, Try this #3 | pass / skip + waiver |
| R05 | Inbox keyed on (consumer_id, event_id); claim insert inside the same transaction as the delivery effect; duplicate delivery → one effect, duplicate acked as success; concurrent 10-duplicate race → 10 effects / 20 deliveries | M2 step 5–6, Try this #2 | pass / skip + waiver |
| R06 | Dual-write loss demo as explicit milestone: commit delivery record, kill -9 in the commit→publish gap, evidence ledger (committed DB row, empty queue, silent consumer); states why no broker config prevents it | M3 part 1 | pass / skip + waiver |
| R07 | outbox_events table written in same transaction as delivery state; relay claims rows, publishes with correlated confirms, marks published_at only after ack; restart with pending row → zero loss, zero duplicates; kill-inside-confirm duplicate absorbed by the R05 inbox | M3 part 2 | pass / skip + waiver |

**Easy-to-skip risks, named so you do not skip them:** R06 demos a *failure* instead of building a feature — it is a mandatory M3 milestone with a written loss ledger, not a stretch. R01 could silently degrade into "one default queue" — force explicit exchange + routing keys + restart-survival evidence. R04's retry budget is only real if you read `x-death` from broker headers — the always-throws Try this #3 asserts it. R05 only bites if you actually deliver the same `eventId` twice and count rows — Try this #2 asserts the row counts.

## Learning objectives

- Declare one durable courier topology — pickups exchange, zone bindings, retry queue, DLQ — and prove each piece in the broker, not in your head.
- Publish with correlated confirms and say exactly what a confirm does and does not prove.
- Run a courier consumer whose ack ordering, prefetch, and concurrency are deliberate, and read redelivery in the UI.
- Give every failed pickup a classified fate (retryable / permanent / exhausted) with an x-death budget.
- Neutralize duplicates with an inbox whose claim and effect share one transaction, under a 10-event race.
- Reproduce dual-write loss with a four-row evidence ledger, then close the window with a transactional outbox relay.
- Write the at-least-once / exactly-once-effect paragraph and defend it without notes.

## Warm-up (3–5 min)

Re-read the "Four Moments" table in [`../../../posts/series-3-rabbitmq/02-publisher-confirms-outbox.md`](../../../posts/series-3-rabbitmq/02-publisher-confirms-outbox.md), then open the Management UI (http://localhost:15672, guest/guest) and tick off the four moments against a queue you still have running from the R-labs: where is "accepted", where is "routed", where is "processed"? Then glance at your R06 loss ledger from the elective — the "after commit before publish" row is the one M3 part 1 will reproduce *in the capstone's own domain* before the outbox of M3 part 2 moves it from unrecoverable to duplicate.

## Project bootstrap

**Exact directory:** `showcase_projects/electives/projects/courier-packet-relay/` — candidate-owned code; consider adding `showcase_projects/electives/projects/` to `.gitignore` (optional, per the electives README).

1. **Do:** Create the directory and a `settings.gradle.kts` naming the project `courier-packet-relay`. Create `build.gradle.kts` with the Kotlin JVM + Spring Boot 3.x plugins and these dependencies: `spring-boot-starter-amqp`, `spring-boot-starter-jdbc`, `postgresql` driver, and (if you want the discipline) `flyway-core`. No web starter — there is no API in this capstone; everything is driven from a `CommandLineRunner` demo mode and the Management UI.
   **Run:** `./gradlew build`. **Observe:** a compiling skeleton. This is a hand-made Gradle project, not a solution repo — you own every line.
2. **Do:** Write `docker-compose.yml` with two services: `rabbitmq:3.13-management` (ports 5672 + 15672) and `postgres:16` (5432). Mount a volume for the Postgres data; RabbitMQ can use its default in-container storage for this lab.
   **Run:** `docker compose up -d`. **Observe:** Management UI answers on 15672, `psql` answers on 5432. Screenshot the empty "Exchanges" tab — before the end of M1 you will compare it.
3. **Do:** Write `application.yml` with the Rabbit and Postgres connection properties plus the config knobs you know are coming: `relay.interval-ms`, `relay.batch-size`, `demo.gap-ms`, `courier.prefetch`, `courier.concurrency`. Comment each knob with one line about which milestone uses it.
   **Run:** `./gradlew bootRun` and let it fail to connect to a queue (nothing declared yet). **Observe:** the failure mode — an app that boots against a broker with *no* topology should fail loudly, not silently work by accident.
4. **Do:** Create the `README.md` skeleton with these fixed headings: **Product**, **Architecture sketch**, **How to run**, **Topology**, **Evidence** (subheads `notes/` and `chaos/`), **Loss ledger**, **Trade-off log**, **Reliability statement**. Leave each heading empty except one line describing what lands there.
   **Run:** nothing. **Observe:** the skeleton is your to-do list; the README is the interview artifact this capstone produces.

**Domain vocabulary you will use from now on:** a *packet* is the shipper's parcel; a *pickup request* is the work message; the *delivery record* is the courier's durable effect (the customer's handover); a *poison packet* is a request that can never be fulfilled; a *duplicate handover* is the same delivery effect applied twice.

## System specification

### Product fantasy / actors

| Actor | Role in the story |
|---|---|
| **Shipper** | Submits a packet (packetId, customerId, zone) at a drop point. In this capstone the shipper is a `CommandLineRunner` demo command — no HTTP. |
| **Dispatcher** | Decides the pickup: records the packet as `PICKED_UP`-assigned and publishes a `pickup.<zone>` request to the courier network. The dispatcher is the dual-write and later the outbox owner. |
| **Courier** | A competing consumer of pickup requests. On pickup, delivers the packet and writes the durable `deliveries` record (customer delivery record), then acks. Concurrency > 1 means several couriers on the street. |
| **Customer** | The recipient. Their experience is the capstone's contract: one packet, one handover, exactly one delivery record per (customer, packet), never a silent stall after the shipper saw "picked up". |

### Scope in

The pickup path end-to-end: submit → dispatch → publish → courier deliver → ack, with retry/DLQ topology, an inbox, and (from M3) an outbox relay. Evidence: Management UI/HTTP API screenshots, row counts, log lines, and the loss ledger. Schema: `packets`, `deliveries`, `inbox_events`, `outbox_events`.

### Scope out

Any HTTP API or frontend, customer tracking webhooks, real payment/signature capture, delivery history timeline (that is the SSE track), multi-region or multi-broker anything, quorum-queue tuning (a stretch), production capacity claims. Keep it a single process + broker + database on localhost.

### Functional requirements

- A submitted packet produces exactly one pickup request per zone routing key, visible in the expected queue with JSON payload and persistent delivery mode.
- The courier writes exactly one delivery record per (customer, packet) even if the same request is delivered twice.
- A packet whose handler always throws follows the retry budget and lands in the DLQ with `x-death` evidence; healthy packets keep flowing while it is parked.
- A crash anywhere between "packet record committed" and "broker accepted the publish" still results in the pickup request being published — at least once.
- A duplicate delivery never causes a second handover.

### Non-functional / evidence requirements

- **Loss ledger:** a table in the README (or `notes/loss-ledger.md`) with one row per crash point — before commit, after commit before publish, during publish, after publish, after confirm before mark — columns: DB state, broker state, consumer state, recoverable?, recovery action.
- **Redelivery evidence:** Management UI screenshot showing a message with `redelivered = true`, plus the consumer log line proving a second effect run.
- **DLQ evidence:** a DLQ message whose headers show the full attempt history (x-death count), and the ready-count proof that healthy packets flow while poison is parked.
- **Reliability statement:** a written paragraph in the README that says at-least-once delivery and exactly-once *effect*, and never says "exactly-once" alone.

### Constraints

- Local only: Docker Compose RabbitMQ + Postgres; no cloud, no managed anything.
- RabbitMQ is at-least-once: the capstone must never claim exactly-once delivery — only safe effects.
- At-least-once is a *design input*: every duplicate source in the blog's four-row table should be reproducible here.

## Milestones (code-along)

### M1 — Topology and a confirmed publisher (R01, R02) · ~1.5h

**1. Do:** Write `RabbitTopology` — one `@Configuration` class declaring the full skeleton you will use all day, all durable:

```kotlin
@Bean fun pickupsExchange() = TopicExchange("pickups", true, false)          // zone routing
@Bean fun pickupsRequests() = QueueBuilder.durable("pickups.requests").build()
@Bean fun pickupsNorth()    = QueueBuilder.durable("pickups.north").build()  // zone evidence queue
@Bean fun pickupsDlq()      = QueueBuilder.durable("pickups.dlq").build()
// bindings: pickup.#  -> pickups.requests ; pickup.north.# -> pickups.north
// plus DLQ exchange + dead-letter wiring coming in M2 (declare them now if you want one config class)
```

**Run:** `./gradlew bootRun`, then restart the Rabbit container and *check the UI before the app reconnects*. **Observe:** exchanges and queues survive the broker restart — durable declarations, not accidental ones. Screenshot with the app stopped: this is your R01 restart-survival evidence. Then, from the HTTP API (`curl localhost:15672/api/queues/%2F/pickups.requests` with guest/guest), capture the same truth as text — interviews like curl more than screenshots.

**Force R01:** the routing key carries the zone (`pickup.north`), the binding decides the destination, and a queue named after a zone exists to prove bindings are real, not decorative.

**2. Do:** Write the envelope and the dispatcher publisher:

```kotlin
data class PickupRequest(
    val eventId: UUID, val packetId: UUID, val customerId: UUID,
    val zone: String, val occurredAt: Instant,
)
// DispatcherService.publishPickupRequest(req) ->
// rabbitTemplate.convertAndSend("pickups", "pickup.${req.zone}", req, CorrelationData(req.eventId.toString()))
```

**Run:** publish one `pickup.north` request. **Observe:** it lands in *both* `pickups.requests` and `pickups.north` — one publish, two queues, the zone binding made visible. Expand "Get message": JSON payload, `delivery_mode = 2`. Screenshot.

**3. Do:** The deliberate break that proves the topology is not magic: publish to `pickupsx` (typo).
**Run:** observe. **Observe:** a channel-level error in the log and a closed channel — that log line is your R01 "missing exchange" evidence. Then add `template.setMandatory(true)` + a returns callback and publish `pickup.unknownzone`: confirm fires, return fires, no queue grows. The Four Moments, now all physical.

**4. Do:** Enable `spring.rabbitmq.publisher-confirm-type: correlated` and set a confirm callback that logs the `eventId` and the ack outcome.
**Run:** publish three requests. **Observe:** three confirm log lines matched to your own `eventId` — the broker's receipt, correlated to *your* identity, because nothing broker-generated survives the round trip.

**5. Do:** The negative case: `docker compose stop rabbitmq`, publish, restart the broker.
**Run:** publish with the broker down, then watch the logs. **Observe:** nacks or timeouts logged with a `cause` — a loud failure, exactly what R02's checkpoint demanded. Write one line in your trade-off log: *confirms prove broker acceptance (moment 2), not routing (moment 3), not processing (moment 4).*

**Mini trade-off fork (write 3–5 lines, pick one):** **Option A — topic exchange** for pickups, single `pickup.#` binding plus a per-zone evidence queue (what you just built). **Option B — direct exchange** with one explicit binding per zone. What does A buy (one binding, wildcard zone scoping, a free audit queue)? What does B buy (typos are loud, a new zone requires an explicit binding — a deployment step, which is a feature)? Which property of "neighborhood zones that will grow" decides it? Log your answer — this is R01's fork restaged at capstone scale.

**Checkpoint for M1:** the topology survives a broker restart (screenshot with app stopped); a confirmed publish correlates to its `eventId`; a missing exchange produced a visible channel error; the negative confirm case is logged. Mark R01 and R02 pass.

### M2 — The courier: ack, poison, inbox (R03, R04, R05) · ~2.5h

**1. Do:** Write `CourierWorker` — `@RabbitListener(queues = ["pickups.requests"], ackMode = "MANUAL")`, container factory with `prefetch` (start at 5) and `concurrency` (start at 2). The durable effect is a `deliveries` insert — customer delivery record with `packet_id`, `event_id`, `delivered_at`. Order: effect first, `channel.basicAck(deliveryTag, false)` second. **No inbox yet** — this step is deliberately unsafe so the duplicate is visible.

```sql
CREATE TABLE deliveries (
    id bigserial primary key,
    customer_id uuid not null, packet_id uuid not null, event_id uuid not null,
    delivered_at timestamptz not null default now(), signed_by text
);
```

**Run:** publish one request; watch the log; then check `Unacked` in the UI under load (publish 20 at once). **Observe:** `Unacked` never exceeds `consumers × prefetch` — your prefetch math, visible as a number. Screenshot the drained queue on the happy path.

**2. Do:** Widen the effect window (500ms sleep inside the handler after the insert, logged) and `kill -9` the app in that window.
**Run:** restart, inspect. **Observe:** the message redelivers with `redelivered = true` in the UI **and** a second delivery record was inserted — two effect runs for one request. This is the honest cost of ack-after-effect without idempotency: safe from loss, unsafe from duplication. Screenshot the pair. **Force R03:** this redelivery-with-second-effect *is* the R03 checkpoint, reproduced in the capstone's own effect table.

**Mini trade-off fork (write 3–5 lines, pick one):** **Option A — ack after the DB effect commits** (what you just did). **Option B — ack after the work completes in memory** (the R03 elective's file/map effect, faster, and crash-lossy). What does A cost (a duplicate window only an inbox can close) and what does B cost (work that never happened — a silent stall, worse than a duplicate)? Which does the capstone mandate and why is the duplicate the *safer* failure for a handover? — this is your "package delivered twice ≈ patient double-dosed, but package never delivered ≈ patient untreated" trade.

**3. Do:** Add the failure vocabulary and the retry topology. `sealed interface` outcome — `Processed / Retryable / Permanent / Exhausted` — and `classify(ex)` whose `else` is retryable. Declare `pickups.retry` exchange + `pickups.requests.retry` (durable, queue-level TTL, zero consumers, dead-lettering back to `pickups` with key `pickup.<zone>`... careful: the routing key must survive the bounce) and give `pickups.requests` dead-letter arguments pointing at `pickups.retry`.
**Run:** declare, restart, inspect the UI's "Dead letter exchange/routing key" columns. **Observe:** the topology now has a timer and a quarantine; screenshot both queues' properties.

**4. Do:** Wire the decision tree: success → ack; retryable and budget remaining → `basicNack(tag, false, false)`; permanent or exhausted → publish to the DLQ yourself and ack the original. Budget from `x-death` headers, not an in-memory counter:

```kotlin
fun retryAttempts(msg: Message): Int = msg.messageProperties.headers["x-death"]
    ?.let { deaths -> deaths as? List<*> ?: emptyList<Any>() }
    ?.filterIsInstance<Map<String, Any?>>()
    ?.firstOrNull { it["queue"] == "pickups.requests.retry" }?["count"] as? Int ?: 0
```

**Run:** publish a packet whose handler always throws a retryable failure, and one healthy packet. **Observe:** the poison leaves the work queue, sits in `pickups.requests.retry` for the TTL, bounces back, and the healthy packet gets delivered *while the poison is parked* — ready-count evidence in the UI (work queue drains, retry queue holds 1). Let the poison exhaust; it lands in `pickups.dlq` with its full `x-death` history. **Force R04:** a permanent-classified failure (corrupt payload that cannot parse) must go to the DLQ on attempt one — assert zero bounces in its x-death. Screenshot both poison messages' headers.

**5. Do:** The inbox. `inbox_events(consumer_id, event_id, packet_id, received_at, primary key (consumer_id, event_id))`. Rewrite the handler as one `@Transactional` method: claim insert (`INSERT ... ON CONFLICT DO NOTHING RETURNING id`), if claimed → delivery effect insert; if not claimed → duplicate, still ack as success. Never nack a known duplicate into the retry topology.
**Run:** repeat the step-2 kill experiment. **Observe:** the second run now logs `already processed, acking duplicate` and the `deliveries` count stays at 1 — two deliveries, one handover. The screenshot pair from step 2 and this step is your entire at-least-once story.

**6. Do:** The race: a scratch script (a `CommandLineRunner` profile or plain SQL + republish) that publishes 10 pickup requests and then *republishes the same 10 eventIds* back-to-back, so ~20 deliveries hit the queue concurrently.
**Run:** watch the logs. **Observe:** exactly 10 `deliveries` rows and 10 `inbox_events` rows for 20 deliveries — the constraint arbitrated the race, not a lucky lookup. **Force R05:** assert row counts, don't eyeball them: `select count(*) from deliveries` = 10, `from inbox_events` = 10.

**Checkpoint for M2:** redelivery with `redelivered = true` produced one effect before the inbox and one effect after it (two screenshots); a poison packet exhausted its x-death budget and a permanent failure landed in the DLQ on attempt one while healthy packets flowed; the 10-duplicate race produced 10 effects / 20 deliveries. Mark R03, R04, R05 pass.

### M3 — Reproduce the loss, then close it (R06, R07) · ~2.5h

**Part 1 — the dual-write loss (R06). This is a milestone, not a footnote.**

**1. Do:** The naive dispatcher: `DispatchService.submitPacket` is `@Transactional` — insert `packets` row (status `PICKED_UP`), then, *after* the method returns, publish `pickup.<zone>` with `demo.gap-ms` sleeping between commit and publish, and a log line right before the publish: `about to publish pickup for packet <id>`. No outbox, no confirms-dance, no retries of the publish. This is the R06 code path on purpose.
**Run:** `demo.gap-ms=10000`, submit, and `kill -9` the app inside the gap. Restart. **Observe:** the three-part evidence — Postgres shows `PICKED_UP`; `pickups.requests` depth is 0; the courier logged nothing. No error anywhere, because the code that would have logged never ran. Screenshot all three and caption: *silent stall*.

**2. Do:** Repeat with the kill during the publish call itself, five times.
**Run:** tally. **Observe:** sometimes the queue has the message, sometimes not, and the app's perspective is identical either way — no exception. Write the sentence: *the exception cannot tell the relay whether the broker accepted the message.* That void is what publisher confirms exist to fill.

**3. Do:** Write the loss ledger — four rows (before commit / after commit before publish / during publish / after publish), columns: DB state, broker state, consumer state, recoverable?, recovery action. Exactly one row has no recovery action. Add the ledger to your README skeleton now. **Force R06:** this written ledger, with the unrecoverable row, is the R06 skill — say out loud why no broker configuration can fix it: *the message was never created; the failure happens before the broker exists.*

**Part 2 — the outbox relay (R07).**

**4. Do:** Add the table and rewrite the dispatcher's transaction:

```sql
CREATE TABLE outbox_events (
    event_id uuid primary key, packet_id uuid not null, event_type text not null,
    routing_key text not null, payload jsonb not null,
    created_at timestamptz not null default now(), published_at timestamptz
);
```

`submitPacket` now inserts the packet row **and** the outbox row in one transaction. No `rabbitTemplate` in the method — delete the M3 part 1 publish. **Run:** submit one packet; query `select event_id, routing_key, published_at from outbox_events`. **Observe:** one row, `published_at = null`, zero messages in the broker — the event exists only in Postgres. Screenshot: the durable handoff point.

**5. Do:** `OutboxRelay` — `@Scheduled(fixedDelayString = "\${relay.interval-ms:1000}")`, claim up to `batch-size` pending rows (`published_at is null order by created_at`), publish each with `CorrelationData(event.eventId.toString())`, and mark `published_at` **only inside the confirm callback** (`UPDATE outbox_events SET published_at = now() WHERE event_id = :id`). Keep rows; never delete.
**Run:** submit; watch the log order — `publishing <eventId>` then the courier's effect then `published_at` set. **Observe:** the window between publish and mark is standing in plain sight as a log gap. Screenshot the three synchronized rows: outbox marked, inbox claimed, delivery written.

**6. Do:** The two proofs that make the outbox honest. First: stop the relay's scheduling (a profile flag), submit a packet, restart the app — the relay picks the pending row up on its first tick: zero loss, zero duplicates. Second: slow the confirm callback by sleeping 3s *between the ack branch and the mark*, submit, `kill -9` inside that sleep, restart. **Observe:** the row is still `published_at = null`, the relay republishes the *same* `eventId`, and the courier logs `processed` then `already processed` — two deliveries, one handover. **Force R07:** that log pair, with the row counts unchanged, is the duplicate window absorbed by the M2 inbox.

**7. Do:** The unroutable case: hand-edit one outbox row's `routing_key` to `pickup.nonexistent` and let the relay tick.
**Run:** observe. **Observe:** confirm fires (broker accepted), return fires (no binding) — `published_at` gets marked while the message goes nowhere. The relay cannot see routing from confirms alone; write the moment-2-vs-moment-3 distinction into your notes.

**Mini trade-off fork (write 3–5 lines, pick one):** **Option A — poll every 1s** (what you built). **Option B — poll every 100ms.** What does B buy (pickup latency), what does it cost (empty queries, more database churn, no correctness change at all)? Why is latency a *product* decision and not a *correctness* one here — and which customer-visible behavior would actually degrade if you chose A? Keep the knob in `application.yml` and defend the number you pick.

**Checkpoint for M3:** the ledger has exactly one unrecoverable row plus the outbox's new row; a restart with a pending row published it with zero loss and zero duplicates; the kill-inside-the-confirm experiment produced two deliveries of one `eventId` and exactly one effect, screenshotted; an unroutable row produced confirm + return. Mark R06 and R07 pass.

### M4 — The reliability evidence pack (all R) · ~1h

**1. Do:** Formalize the chaos. In `chaos/`, write three mini-runbooks, each with command, expected log lines, and evidence capture:
- **kill-mid-publish.md** — the M3 part 1 tally run 10 times with a randomized gap offset; tally outcomes in the ledger.
- **redeliver-same-messageId.md** — requeue (or republish with the identical `eventId`) one message five times; assert `deliveries` stays at 1 and `inbox_events` at 1 after each.
- **always-throws.md** — the poison packet from M2 with a printout of its `x-death` count in the DLQ and a ready-count screenshot showing healthy packets flowing past it.

**2. Do:** Write the reliability statement into the README — exactly the shape the curriculum allows:
> The broker delivers at-least-once. The outbox relay guarantees every committed event is published at least once, even across crashes; its republish window manufactures duplicates on purpose. The courier inbox makes every duplicate a no-op by claiming the event in the same transaction as the delivery effect. Therefore the system provides exactly-once *effect* under at-least-once delivery — and I never claim exactly-once delivery.

**Run:** read it aloud. **Observe:** if the word "exactly-once" appears without "effect" next to it, fix it.

**3. Do:** Update the skill checklist table above to its final state, and drop the README skeleton's remaining empty headings (Product one-liner, Architecture sketch) with the evidence captured.

**Checkpoint for M4:** three runbooks executed with captured evidence; the statement written; every checklist row marked pass or waived with a written reason.

## Try this

1. **Missing-exchange channel error (R01):** publish to `pickupsx` from a second instance while the first is mid-work. Screenshot the channel-closed log line and confirm the app continues on its remaining channels. What does this teach about channel lifecycle that the UI cannot show?
2. **Redeliver the same `messageId` (R05):** republish one packet's request with the identical `eventId` ten times, including concurrent deliveries. Assert with SQL, not eyeballs: `deliveries` = 1, `inbox_events` = 1. Then do the same *before* the inbox existed (disable it via profile) and capture the contrast row.
3. **The always-throws packet (R04):** publish a request whose handler always throws a retryable failure, then check the DLQ message's `x-death` header — count bounces, match them against `MAX_RETRIES + 1` deliveries, and screenshot a healthy packet completing while the poison sits parked.

## Trade-off forks

1. **Topic vs direct exchange for neighborhood zones (M1):** one wildcard binding + evidence queue vs one explicit binding per zone. Defend the losses of the loser.
2. **Ack-after-DB-commit vs ack-after-work-in-memory (M2):** duplicate window vs silent-stall window. Which failure does a customer experience as worse, and which can the next layer neutralize?
3. **Outbox poller interval vs pickup latency (M3):** 1s vs 100ms. Show that the correctness properties are unchanged and that the interval is a product knob, not a safety knob.

Collect all three in the README's trade-off log — this is `../../../posts/series-5-interview/02-tradeoffs.md` rehearsal material.

## Hints

- **Hint 1 (M1):** If confirms never fire, check `spring.rabbitmq.publisher-confirm-type: correlated` against the *auto-configured* template — and that you pass the same `CorrelationData` object whose id the callback reads. Callbacks arrive on a different thread; log the `eventId`, never assume log order.
- **Hint 2 (M2):** `kill -9` (not SIGTERM, not the IDE's stop button) is the only honest way to hit the ack window — Spring's graceful shutdown may flush or close in ways that hide it. If your observer dies with the killer, run the courier on a second instance (`--spring.rabbitmq.listener.simple.acknowledge-mode=manual`) or accept the first reproduction with a shared evidence log. The x-death map in AMQP headers is a `List<Map<String, Any?>>`; index by the `queue` name.
- **Hint 3 (M3):** The claim query and the effect must share one Spring-managed transaction, or the race returns. `@Transactional` on the listener method is enough at this scale; `SELECT ... FOR UPDATE SKIP LOCKED` is the stretch. For the kill-inside-confirm experiment, sleep in the callback *between* the ack branch and the mark — if you never see `already processed` on restart, the mark landed, and you reproduced the wrong window.
- **Hint 4 (M4):** When the retry bounce returns, the routing key is the one the dead-letter headers carry — if the bounced message stops matching `pickup.#`, your retry queue's `deadLetterRoutingKey` is wrong, and the UI's queue properties page is the fastest debugger you have.

## Checkpoint / success criteria

You may leave when:

- The skill checklist table is fully marked pass or skip+waiver, with evidence links for each pass.
- M1–M4 checkpoints are met: restart-survival, correlated confirms with a logged negative case, redelivery with a second effect *before* the inbox and a single effect *after* it, x-death-bounded retries with a permanent-failure-on-attempt-one, a 10-effects/20-deliveries race, the four-row loss ledger with one unrecoverable row, a pending outbox row that published after restart, and the kill-inside-confirm pair absorbed by the inbox.
- `notes/` holds the loss ledger and the reliability statement; `chaos/` holds the three runbooks with their captured evidence (screenshots or curl output, plus SQL row counts).
- You can say the reliability sentence aloud without reading it, and it never says "exactly-once delivery."

## Bottleneck & reflection questions

1. Which of the four moments (commit → broker acceptance → routing → processing) was the hardest to prove in your capstone, and what evidence made it provable? (simplicity / system design)
2. The outbox relay made duplicates *guaranteed*; the inbox made them harmless. Which layer owns safety, which owns correctness, and what would a team that only built the outbox say about a double handover? (failure handling)
3. Your customer's only signal is the status on the packet. Where in your ledger is the longest silent window *after* the fix, and what is the cheapest detection signal that does not require a sweeper? (patient experience)
4. You publish with mandatory + returns, confirms, and an outbox. Which of those three would you cut first if an ops team complained, and what duplicate or loss does cutting it reopen? (tradeoffs)
5. Two couriers race the same pickup. Your inbox produces one handover — but what does the *dispatcher* still need to know that no queue depth will tell it, and where would you store that fact? (system design)

## Handoff

- **Next track/capstone:** [`../glue/CAPSTONE_XC_observatory_desk.md`](../glue/CAPSTONE_XC_observatory_desk.md) (E — Glue) if you want the delivery-timeline/SSE layer for this same courier story; or the advanced [`../advanced/CAPSTONE_AC_nightshift_incident_lab.md`](../advanced/CAPSTONE_AC_nightshift_incident_lab.md) (F) to turn the chaos runbooks into a drill. Either way, the next R-adjacent labs are [`../advanced/A03_outbox_at_scale_local.md`](../advanced/A03_outbox_at_scale_local.md) (SKIP LOCKED, two relays), [`../advanced/A04_inbox_exactly_once_effect.md`](../advanced/A04_inbox_exactly_once_effect.md) (this race under stress), and [`../advanced/A12_observability_slice.md`](../advanced/A12_observability_slice.md) (trace `eventId` end-to-end).
- **Back to the showcase:** return to [`../../pharmacy-fulfillment/exercise_03_production.md`](../../pharmacy-fulfillment/exercise_03_production.md) — the capstone's outbox *is* its Milestone 1, the retry/DLQ its Milestone 4, the inbox its Milestone 5; port the evidence, not the code.
- **Interview one-liner:** *"I demo the dual-write hole, then close it with outbox and idempotent consumers under at-least-once delivery."*

## Optional stretch

- `SELECT ... FOR UPDATE SKIP LOCKED` on the relay's claim query and run two relay instances (two processes or two scheduled beans). Publish 50 packets; verify each `eventId` is delivered at least once and `published_at` set exactly once per row. One sentence: what did `SKIP LOCKED` change in your claim behavior?
- A replay utility for the DLQ that republishes a dead letter *preserving its `eventId`* — then replay the same packet twice and prove the inbox still holds the line.
- Swap the retry queue to quorum with `x-delivery-limit`, and diff the x-death forensics against the classic queue you built.
- Turn the M2 race into a `../postgres/P07_testcontainers_postgres.md`-style test that fails when the inbox transaction is removed — the regression proof you can carry into the showcase.
