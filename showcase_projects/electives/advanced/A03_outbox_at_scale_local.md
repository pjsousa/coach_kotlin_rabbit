# A03 Outbox at Scale (Local) — Code-Along Elective

## Objective

You already built the outbox mini-relay in R07: one table, one poller, publisher confirms, mark-after-confirm. This elective makes it stop being a demo: a **batch relay with claim-based concurrency**, **backoff on failure**, **stuck-row detection**, and **lag/age metrics** that make the outbox observable instead of trusted. One primary objective: scale the relay locally (batches, multiple claimers, failure paths) while keeping the one guarantee that matters — every committed event eventually reaches the broker, and any uncertainty produces a duplicate-safe retry, never silent loss.

## Time box

- Core: 2.5 hours
- Optional: 0.5h for `SKIP LOCKED` contention measurement or a stuck-row recovery endpoint

## Prerequisites

- R07 (`../rabbit/R07_outbox_relay_mini.md`) — the mini relay. You already saw the confirm-after-mark crash window; now prove you can scale it without breaking it.
- R02 (`../rabbit/R02_fire_and_forget_publisher.md`) — publishing basics.
- P03 (`../postgres/P03_approve_once_race.md`) — conditional updates and affected-row counts; the same tool is how claims work.
- Showcase position: **during Exercise 3** — this is the dress rehearsal for Milestones 1 and 2 in `../../pharmacy-fulfillment/exercise_03_production.md`.

## Blog & curriculum links

- Primary: `posts/series-3-rabbitmq/02-publisher-confirms-outbox.md`
- Secondary: `posts/series-3-rabbitmq/06-operational-testing.md` and `posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md` (the relay step)
- Coach-assessment gap: outbox "conceptual" → implemented at scale with operational evidence.

## Background & motivation

R07 proved the pattern fits in one file. The moment Ex3's workflow gets real traffic, three things break that a demo never hits: the single poller becomes the bottleneck, a *slow* broker turns the poll loop into a hot retry, and a row that is stuck (claimed, confirmed, but never marked, or marked-but-never-committed) hides in the queue forever because nobody measured its age. This kata exists to turn the relay into a *claim worker*: batches claimed atomically with `FOR UPDATE SKIP LOCKED`, confirms correlated per publish, backoff when the broker is down, and an `age` metric that surfaces stuck rows before an operator ever looks. It deliberately ignores consumer-side processing (A04) and delivery guarantees past broker acceptance — a confirm is a receipt, not a result, and this elective's job is to make receipts observable at volume.

## Learning objectives

- Claim outbox rows in batches with `FOR UPDATE SKIP LOCKED` and prove disjoint claims under concurrent relay workers.
- Correlate publisher confirms to batch claims and mark rows published only after confirm (never before).
- Add backoff and jitter so a down broker doesn't become a tight poll loop.
- Detect and recover stuck rows (age threshold, attempts counter, operator-facing status).
- Emit relay metrics: pending count, max age, confirm latency, attempts, duplicates published.
- State precisely the duplicate window that scaling cannot remove.

## Warm-up

Re-read the "Four Moments" table in `posts/series-3-rabbitmq/02-publisher-confirms-outbox.md` and the crash-window paragraph in `posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md`. Then, in the management UI, watch the `publish` rate while a burst of approvals runs through R07's relay. Ask yourself: *with one poller doing 1 row per loop, how many events per second can this relay sustain, and what is the first thing that saturates — the poll query, the round trip, or the confirm wait?* That bottleneck is what you're about to remove.

## System specification

**Scope in:** the R07 outbox table plus `attempts`, `locked_by`, `locked_at`, `last_error`; a batch-claim relay (configurable batch size) with publisher confirms and mandatory routing; backoff on broker failure; a stuck-row monitor and recovery action; a small metrics surface (logs or endpoint) with age/lag.

**Scope out:** ordering guarantees for consumers (A04/A05), retry/DLQ topology (A01), Postgres LISTEN/NOTIFY as the only trigger (it's a fork option below), anything beyond the local machine.

**Functional requirements:**
- N relay workers (2–3 locally) never claim the same row.
- A row is marked published only after its confirm; a negative confirm or timeout leaves it pending and increments `attempts`.
- A committed event with no broker reachable stays pending and is retried with backoff — never lost, never tight-looped.
- A row stuck > threshold is visible via metrics and recoverable by an operator command.
- Duplicate publication (confirm lost after broker accepted) is a *demonstrated* outcome, with the stable event ID intact.

**Constraints:** local Docker Compose, pinned broker, one Spring Boot app (relay is a `@Scheduled`-or-`CommandLineRunner` component, not a second service), PostgreSQL 16 pinned, Kotlin.

## Step-by-step code-along

1. **Do:** Extend the R07 schema with `attempts int not null default 0`, `locked_by text`, `locked_at timestamptz`, `last_error text`. Backfill the migration on the existing table (Flyway, P01 style).
   **Run:** `docker compose up -d`; migrate. **Observe:** existing R07 rows still queryable; migration log clean. **Decision:** keep `published_at` as the only terminal marker (nudge: a separate receipt table is fork material below, not a default).

2. **Do:** Replace the single-row poll with a batch claim: `UPDATE outbox_events SET locked_by = :worker, locked_at = now() WHERE event_id IN (SELECT event_id FROM outbox_events WHERE published_at IS NULL AND (locked_by IS NULL OR locked_at < now() - interval '30 seconds') ORDER BY created_at LIMIT :batch FOR UPDATE SKIP LOCKED) RETURNING event_id, payload, routing_key`. Use `@Transactional` and count affected rows.
   **Run:** seed 500 events; run with 2 workers, batch 20. **Observe:** total claimed per cycle = 40, no overlap in `locked_by`, and the `RETURNING` set is the only work performed. **Decision:** whether `locked_at` expiry is a recovery safety net (nudge: keep it — a crashed worker's claims must not hold the table hostage).

3. **Do:** Publish the batch with publisher confirms; correlate each confirm back to its event ID (a `PendingConfirm` map keyed by publish sequence number, mirroring R07 but per batch). Mark `published_at = now()` only after the confirm. On negative confirm or timeout, clear the lock and increment `attempts` with `last_error`.
   **Run:** publish 500; watch the table. **Observe:** `published_at` moves monotonically; a deliberately closed channel (see Try this) leaves rows pending with `attempts` incremented, not lost. **Decision:** the confirm map lives in memory — what happens to in-flight claims on relay restart? (Hint: `locked_at` expiry + duplicate-safe event IDs are the answer; that's the point of step 2's safety net.)

4. **Do:** Add backoff: on broker connection failure, stop claiming and wait with exponential backoff + jitter (e.g. 1s → 2s → 4s, capped at 30s). Log the state clearly.
   **Run:** `docker compose stop rabbit`; watch the relay logs. **Observe:** no tight loop — log lines show `backoff: 4s`, and the DB keeps its pending rows. Restart the broker; the relay resumes claiming without manual intervention. **Decision:** the backoff is about *broker* health — a *confirm timeout* is not the same failure; document the difference in your log lines.

5. **Do:** Add the stuck-row monitor: every cycle, log `pending count`, `max pending age`, and any rows with `attempts > 5` or age > 5 minutes. Add an operator endpoint `POST /relay/release` that clears locks on rows whose `locked_at` is older than the expiry threshold (idempotent — it only touches rows your worker owns or that are expired).
   **Run:** poison one row by hand (`UPDATE ... SET last_error='x'`); watch metrics show its age grow; call `/relay/release`. **Observe:** the row is reclaimed and republished with its original `event_id`. **Decision:** whether `attempts` is a hard cap (nudge: for this kata, cap = 0; the DLQ story is A01's).

6. **Do:** Wire into Ex3: the approval transaction now inserts into the outbox with this schema (`../../pharmacy-fulfillment/exercise_03_production.md` Milestone 1), and the relay runs as the Milestone 2 component.
   **Run:** Ex3's existing tests. **Observe:** no behavior regression; every committed approval produces a relayed, confirmed event.

7. **Do:** The concurrency proof test (Testcontainers, `posts/series-3-rabbitmq/06-operational-testing.md`): seed 1000 events, run 3 relay workers with batch 20, assert (a) every row ends `published_at` set exactly once, (b) no two workers ever share a `locked_by` value at the same time (record claim log), (c) the duplicate window is demonstrated: simulate a lost confirm (kill the app between confirm and mark) and assert the row is republished with the same `event_id`.
   **Run:** `./gradlew test`. **Observe:** the test's failure messages show the disjoint-claim invariant and the duplicate-safe republish — both proven against the real broker.

## Try this

**The lost confirm.** This is the whole reason the outbox exists. With a batch of 100 pending, stop the app (SIGKILL) at the instant a confirm has been received but before `published_at` was written. Because the app died, the confirm map is gone; on restart, the row is pending again (its `locked_at` expired) and gets republished. Watch the management UI: the same `event_id` appears **twice** in the work queue. That duplicate is the *price* of no-loss; consumer-side idempotency (A04) is what makes it safe. Write the sentence: "the relay can republish an event it already published — that's a duplicate, not a bug, and the consumer must expect it."

## Trade-off fork

Pick one pair, implement it, justify in 3–5 lines:

- **Batch poll vs single-row relay:** batch claim amortizes round trips but makes one slow confirm stall a whole batch; single-row is simple but caps throughput. Implement the batch; justify why the stall cost is acceptable or design around it.
- **Poller vs transactional LISTEN/NOTIFY:** a poller is simple, portable, and self-healing but adds latency and load; `NOTIFY` on insert wakes the relay instantly but adds a failure mode (missed notification → must keep a slow poller anyway) and a Postgres-specific coupling. Either is defensible — name the lost benefits of the one you didn't pick.

## Hints

- **Hint 1:** `FOR UPDATE SKIP LOCKED` only skips rows locked *by other transactions*. Two relay workers in the same app share the same Hikari pool, so the lock contention happens at the transaction level — prove it with two separate `@Transactional` methods or two app instances against the same DB.
- **Hint 2:** The confirm correlation map must be keyed by the publish **sequence number** (`Channel#getNextPublishSeqNo`), not by the event ID — the broker confirms by sequence number. When the channel is recreated after a connection failure, start a fresh map; never reuse sequence numbers across channels.

## Checkpoint / success criteria

Done when:

- 500 seeded events drain with disjoint claims (claim log proves no overlap) and every row reaches `published_at`.
- Broker-down run shows backoff log lines and zero lost rows; broker restart resumes relay without manual intervention.
- A stuck-row metric and `/relay/release` recovery are demonstrated with evidence (row republished with original `event_id`).
- The lost-confirm experiment produces a visible duplicate with identical `event_id` in the work queue, and you have written the explanation.
- The Testcontainers test passes: disjoint claims, exactly-once marking per row, duplicate-safe republish.

## Bottleneck & reflection questions

1. Your relay marks `published_at` only after confirm. If the confirm map is in-memory, what does a relay restart *between* publish and confirm cost — and is that cost a bug or a design? (Interviewers love this.)
2. `locked_at` expiry is the recovery net. How long should the expiry be, and what happens if a worker is genuinely stuck mid-batch *after* publishing but *before* marking — how many duplicates does the consumer tolerate before you're called?
3. The outbox table grows forever. What's the retention story, and how does deleting old published rows interact with a slow consumer that replays events (R05/A04)?
4. If the broker is down for an hour, `pending` grows. Which metric tells you it's a broker outage and not a stuck row — queue depth alone, or something in the outbox?
5. In Ex3's final architecture record (Milestone 10), where does the outbox's duplicate window get documented — and what does your answer say about how you think about guarantees?

## Handoff

- Next: A04 (`A04_inbox_exactly_once_effect.md`) — the consumer side that makes the relay's duplicates harmless. Or A05 (`A05_ordering_keys.md`) if ordering is your bigger worry.
- Related showcase work: `../../pharmacy-fulfillment/exercise_03_production.md` **Milestones 1–2** — this kata is their evidence source.
- Interview line: *"The relay claims outbox rows in batches with SKIP LOCKED so concurrent workers never double-claim, marks published only after a correlated publisher confirm, and treats the lost-confirm window as a duplicate-safe republish with the same stable event ID — the database is the handoff point, and the consumer's inbox is what absorbs the duplicate."*

## Optional stretch

Measure contention: run the batch-claim test with 1, 2, and 4 relay workers and record `locked` contention time and drain time per configuration. Plot the three runs. Then answer in your notes: at what worker count does contention outweigh parallelism, and how would you detect that threshold in production without a load test?
