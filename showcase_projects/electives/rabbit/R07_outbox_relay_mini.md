# R07 Outbox Relay Mini — Code-Along Elective

## Objective

Close the R06 loss window for real: an `outbox_events` table written in the same transaction as the state change, a scheduled relay that claims pending rows, publishes with correlated publisher confirms, and marks rows published only after the broker accepts. Primary objective: every committed event is published at least once — and you can reproduce the one remaining duplicate window and point at the R05 inbox that makes it harmless.

## Time box

~2.5–3 hours. Core — this is the reliability centerpiece of the whole curriculum; plan for the crash experiments to eat most of the time.

## Prerequisites

- `R06_dual_write_failure_demo.md` — you must have reproduced the loss first; this kata is its after-picture. (Explicit ordering: R06 before R07.)
- `R02_fire_and_forget_publisher.md` — publisher confirms from the publish side.
- `R03_manual_ack_consumer.md` and `R05_idempotent_consumer.md` — the consumer that receives relay output and the inbox that neutralizes relay duplicates.
- PostgreSQL + RabbitMQ + app from `../glue/X01_docker_compose_trio.md`.
- Showcase position: this is `../../pharmacy-fulfillment/exercise_03_production.md` Milestones 1–2, before `../advanced/A03_outbox_at_scale_local.md`, `../advanced/A04_inbox_exactly_once_effect.md`, `../advanced/A12_observability_slice.md`, and `../advanced/A13_chaos_drill_script.md`.

## Blog & curriculum links

- Primary: `../../../posts/series-3-rabbitmq/02-publisher-confirms-outbox.md` — the outbox table, the relay loop, "Why Duplicate Publication Remains Possible."
- Secondary: `../../../posts/series-3-rabbitmq/06-operational-testing.md` (duplicate-publication and relay-crash tests) and `../../../posts/series-3-rabbitmq/07-showcase-reliable-fulfillment.md` (Steps 1–2 and crash window 2).
- Coach-assessment gap: the outbox was the strongest named concept; this kata converts it into a running system with the confirm crash window demonstrated.

## Background & motivation

R06 proved the gap. This kata builds the standard fix, and the interesting part is what the fix does *not* claim: the outbox makes loss nearly impossible and makes duplication *certain to happen eventually*. The relay republishes a row whenever it is unsure — and it is always unsure about the crash between "broker confirmed" and "row marked." Every layer of this curriculum has been steering you here: the confirm is the receipt (R02), the inbox is the defense (R05), the topology is the destination (R01). This kata deliberately ignores scale — batching, multiple relay instances, stuck-row backoff are `../advanced/A03_outbox_at_scale_local.md` — and Postgres contention mechanics (`SKIP LOCKED` in depth is `../advanced/A09_postgres_under_contention.md`). You are building the smallest honest relay.

## Learning objectives

- Design the `outbox_events` schema (stable `event_id`, routing intent, payload, `published_at`) and write it inside the business transaction with no RabbitMQ call in the same method.
- Run a polling relay on a fixed schedule that claims pending rows and publishes with `CorrelationData(eventId)`.
- Mark `published_at` only inside the confirm callback; keep rows, never delete them.
- Reproduce the confirm-then-crash duplicate window and observe two copies of one `eventId` on the wire.
- Surface unroutable publishes (mandatory + returns) and explain what the relay does and does not know after each callback.

## Warm-up (3 min)

Re-read "The Outbox Table" and "The Relay Loop" in `../../../posts/series-3-rabbitmq/02-publisher-confirms-outbox.md`, and glance at your R06 loss ledger — the "after commit before publish" row is the one this kata moves from unrecoverable to duplicate.

## System specification

- **Scope in:** `outbox_events` table; `ApproveService` rewritten so the state UPDATE + history insert + outbox insert commit together; `OutboxRelay` (`@Scheduled(fixedDelay = 1000)`) that claims up to N pending rows and publishes each with a confirm; confirm callback marking; a mandatory/returns path; the R05 consumer with inbox; crash experiments.
- **Scope out:** multi-instance relay, batch claiming, backoff/stuck-row handling (A03), `LISTEN/NOTIFY`, retention policy (just keep rows), the event side (`pharmacy.events` fan-out can be a one-line second publish if time allows, but work-only is fine).
- **Functional requirements:** approve → outbox row → relay publish → confirm → `published_at` set → consumer effect with inbox → ack. Kill the app at any point after commit: the event still gets published, at least once.
- **Constraints:** local Docker stack; single module; relay interval and batch size as configuration values; `publisher-confirm-type: correlated`; `@EnableScheduling`.

## Step-by-step code-along

1. **Do:** Add the table (migration): `outbox_events(event_id uuid primary key, prescription_id uuid not null, event_type text not null, routing_key text not null, payload jsonb not null, created_at timestamptz not null default now(), published_at timestamptz)`.
   **Run:** start the stack; `\d outbox_events`. **Observe:** the column that matters is `published_at` — nullable, set later, the relay's entire state machine.
   **Decision:** payload as full JSON vs a reference to the aggregate. Nudge: full JSON makes the message self-contained and replayable; a reference couples consumers to a second lookup. For this kata, full JSON.

2. **Do:** Rewrite `ApproveService.approve` — `@Transactional`, state UPDATE (row-count checked, see `../postgres/P03_approve_once_race.md`), history insert, then `outboxRepository.insert(OutboxEvent(...))` with `eventId = UUID.randomUUID()`. There is no `rabbitTemplate` in this method. Delete the R06 naive publish.
   **Run:** approve one prescription; then query `select event_id, routing_key, published_at from outbox_events`. **Observe:** one row, `published_at = null`, and no message anywhere in the broker — the event exists only in PostgreSQL. That is the durable handoff point; commit this state as evidence (screenshot of the query).
   **Decision:** does the outbox insert failure fail the approval? Nudge: yes — if the row cannot be written, the change must not commit, or you are back to R06.

3. **Do:** Build `OutboxRelay`: `@Scheduled(fixedDelayString = "\${relay.interval-ms:1000}")` claiming up to `\${relay.batch-size:10}` pending rows (`published_at is null order by created_at limit :n`), and for each, `template.convertAndSend("pharmacy.work", event.routingKey, envelope, CorrelationData(event.eventId.toString()))`. Wrap the batch in the confirmed-publish discipline: log every publish with the `eventId`.
   **Run:** with the app running, watch logs ~1s after approval. **Observe:** `publishing packaging.request for <eventId>` appears; then the consumer (R03/R05) logs `Processed`; then `published_at` is still null — because you have not written the confirm callback yet. That ordering — publish happened, row not marked — is your duplicate window standing in plain sight.
   **Decision:** 1s polling vs faster. Nudge: 1s is the blog's default and trivially explainable; latency is a product decision, not a correctness one.

4. **Do:** Wire the confirm: `spring.rabbitmq.publisher-confirm-type: correlated`, and `template.setConfirmCallback { data, ack, cause -> if (ack) outboxRepository.markPublished(data.id) else log.warn(...) }`. `markPublished` is `UPDATE outbox_events SET published_at = now() WHERE event_id = :id`.
   **Run:** approve again; then query. **Observe:** the confirm callback logged, `published_at` set, exactly one delivery and one effect (R05 inbox holds one row). The relay now knows — with a receipt — that the broker accepted. Screenshot the three synchronized rows: outbox row marked, inbox row claimed, effect row written.
   **Decision:** mark-then-publish or publish-then-mark? Nudge: publish-then-mark is the only ordering that cannot lose; the mark must never precede the confirm.

5. **Do:** Prove the loop end-to-end under a clean restart: approve with the relay *stopped* (profile flag or pause scheduling), kill nothing yet — restart the app, watch the relay pick the pending row up and complete it.
   **Run:** approve; stop app; restart; check `published_at` + consumer log. **Observe:** the row was pending, the relay published it on first tick after restart, `published_at` set, effect once. Zero loss, zero duplicates in this window (R06's *first* crash window, closed). Add this to your ledger as the outbox row.

6. **Do:** Add the mandatory/returns path: `setMandatory(true)` + `setReturnsCallback`, and publish one event whose `routing_key` you corrupt in the outbox row by hand (`update outbox_events set routing_key = 'packaging.nonexistent'`).
   **Run:** let the relay tick. **Observe:** the return callback logs the unroutable publish with exchange and key — broker accepted (confirm fires first), then returned (no binding). The row's `published_at` gets marked by the confirm while the message goes nowhere: the relay cannot know routing by confirm alone. This is the exact moment-2-vs-moment-3 distinction, made visible.

## Try this

The duplicate window, reproduced: with the relay interval at 1000ms and the confirm callback slowed by a `Thread.sleep(3000)` *inside the callback before the mark*, approve a prescription and `kill -9` the app inside the confirm sleep. Restart. Observe: the row is still `published_at = null` (the mark never landed), the relay republishes the same `eventId`, and the R05 consumer logs `Processed` then `AlreadyProcessed` — two deliveries, one effect. That pair of log lines is the entire at-least-once story in one screenshot: the relay created a duplicate, the inbox made it harmless, and neither step claimed exactly-once. If you skipped R05's inbox, this experiment shows the effect running twice — run it both ways and caption the difference.

## Trade-off fork

**Option A — polling relay** (what you built): a 1s fixed-delay loop over the outbox table. Fewest moving parts, trivially explainable, works after restarts, self-healing.
**Option B — listener-based relay:** wake the relay via Postgres `LISTEN/NOTIFY` (or a channel message) so publish happens near-instantaneously after commit instead of within the polling tick.

Pick one and write 3–5 lines: what does B buy (latency, no empty polls) and what does it lose (a second connection path, a notification that can be missed between listener registration and commit — which forces the poller back in as a fallback anyway)? Why does A remain the defensible default for a pharmacy challenge, and what is the honest latency cost you are accepting? The curriculum builds A; naming B's failure modes is the interview muscle.

## Hints

- **Hint 1:** If confirms never fire, check `spring.rabbitmq.publisher-confirm-type: correlated` against the *auto-configured* template — and that the relay passes the same `CorrelationData` object whose id you read in the callback. The callback arrives on a separate thread; make `markPublished` idempotent and safe to call twice.
- **Hint 2:** For the kill-inside-the-confirm experiment, sleep *in the callback between the ack branch and the mark call* — not in the relay's publish path. If the app dies with the row unmarked but the broker confirmed, you have the exact window; if you never see `AlreadyProcessed` on restart, check that the relay claims the row again (it will, because `published_at` is null) and that the consumer's inbox actually stores the `eventId` from the envelope.

## Checkpoint / success criteria

You may leave when:

- Approval commits state + history + outbox row atomically, and no RabbitMQ call lives in the transaction (code + log evidence).
- The relay publishes with a correlated confirm, marks `published_at` only after ack, and the consumer completes the loop with the inbox (three synchronized rows as evidence).
- A restart with a pending row publishes it with zero loss and zero duplicates.
- The kill-inside-the-confirm experiment produced two deliveries of one `eventId` and exactly one effect — screenshotted.
- An unroutable publish produced a confirm *and* a return callback, and you can explain what each proves.
- Your R06 loss ledger now shows the outbox row for the previously unrecoverable case.

## Bottleneck & reflection questions

1. The relay crashes after the confirm and before the mark. What did the broker experience, what did the database record, and which layer converts the disagreement into a no-op?
2. Your `published_at` mark uses an `UPDATE ... WHERE event_id = :id` — two relay instances claim the same row. What happens, and why does `SKIP LOCKED` (in `../advanced/A03_outbox_at_scale_local.md` and `../advanced/A09_postgres_under_contention.md`) matter for the second instance?
3. Outbox rows are never deleted in this kata. What is the retention argument, and what bug does a 24-hour TTL cleanup introduce if the retry budget plus replay window can exceed it?
4. A poison payload sits in the outbox table and keeps failing the consumer. Where does it end up (R04), and what does the operator need to decide about the *source* row?
5. Which crash window still exists after this kata — and which one is now provably gone? Point at the ledger.

## Handoff

- Next: `../advanced/A03_outbox_at_scale_local.md` (batch relay, stuck rows, metrics), `../advanced/A04_inbox_exactly_once_effect.md` (this kata's duplicate window under stress), `../advanced/A12_observability_slice.md` (trace the eventId end-to-end), and `../advanced/A13_chaos_drill_script.md` (kill mid-publish, broker restart, runbook).
- Related showcase: `../../pharmacy-fulfillment/exercise_03_production.md` Milestones 1–2 — this kata *is* those milestones at minimal scope; the showcase adds the event fan-out and the measurement.
- Interview line to say aloud: *"My approval transaction writes the state change and an outbox row together, and a relay publishes that row with a correlated publisher confirm, marking it only after the broker accepts — so a crash before the confirm republishes and a crash after the confirm but before the mark republishes too, which is a duplicate my inbox neutralizes; every committed event is published at least once, and I have reproduced the one remaining duplicate window to prove it."*

## Optional stretch

Add `SELECT ... FOR UPDATE SKIP LOCKED` to the claim query and run two relay instances (two app processes or two scheduled beans with different names). Publish 50 approvals and verify: no row published twice by both relays (unique `eventId` per delivery), total deliveries ≥ 50, and `published_at` set once per row. Then write the one-sentence description of what `SKIP LOCKED` changed in your claim behavior — you are standing at the door of A03.
