# A12 Observability Slice — Code-Along Elective

## Objective

Prove that one trace id can be followed end-to-end across the path you already built — REST submit → outbox → relay publish → broker → consumer → projection — and that lag and error signals tell a true story when things break. You will instrument with MDC correlation propagation (no OpenTelemetry dependency), expose the four signal families the Exercise 3 spec names (lag, confirm/nack, queue depth, DLQ), and run one deliberate failure whose trail reads clean from logs alone.

## Time box

~2h. Core: steps 1–5. Optional: step 6 (metric counters) and the "follow the trace blind" exercise in "Try this".

## Prerequisites

- `../glue/X02_structured_logging.md` — you already have structured logs and a correlation id on the REST side; this kata *extends* that correlation across the message boundary.
- `../rabbit/R07_outbox_relay_mini.md` — the outbox + relay path you built there is the trace's spine.
- `A10_read_models_projections.md` (or P05) — the projection lag you measured there becomes a signal here.
- Position: **interview polish — after Exercise 3.** This kata turns the Milestone 6 evidence into a rehearsed defense; tie it to `posts/series-5-interview/*`.

## Blog & curriculum links

- Primary: `posts/series-3-rabbitmq/06-operational-testing.md` — "the broker records evidence, the application makes policy, and the test's job is to make both visible"; the Management API as the oracle; the DLQ-depth-vs-work-queue-depth trap.
- Secondary: `posts/series-5-interview/02-tradeoffs.md` — the four-part tradeoff statement you will use to explain *why* MDC + logs + counters rather than a tracing vendor.
- Coach-assessment gap: production judgment and interview defense — "explain every decision in terms of patient experience, simplicity, failure behavior, and time budget."

## Background & motivation

Exercise 3 Milestone 6 asks for "structured logs with a correlation identifier... and a failure can be followed from request to durable outcome without reading source code." You already saw the pieces in X02 and R07: a correlation id in, and an outbox row + confirm in. What is missing is the *thread* — and the honest version of this kata is the one interviewers actually probe: a trace id is only as good as the context propagation that carries it across threads, transactions, and the broker.

- **The relay is the break point.** The REST request's thread dies when the response returns; the relay is a poller on its own thread. If the relay does not *read* the correlation from the outbox row (not from a thread-local it cannot see), the trace dies at the database. That one decision — store the trace id on the outbox row — is the whole kata.
- **Lag is the early-warning signal, and it needs a definition.** "Projection behind by N events," "outbox pending older than X," "queue depth climbing" — each is a number with a threshold, and each is checkable locally with one SQL query or one Management API call.
- **Error signals are the ones teams skip.** A deadlock counter, a nack counter, a DLQ-depth gauge: the work queue can look healthy while poison messages accumulate invisibly — the blog post's exact warning, and the reason DLQ depth is the alert.
- **Vendors are a tradeoff, not a default.** OpenTelemetry is the production-grade answer — and it is also a dependency, an exporter, and a vocabulary the 2–5h challenge does not need. Choosing MDC + structured logs *on purpose* is the interview-grade move; choosing it by accident is the trap.

What this kata deliberately ignores: dashboards, alert routing, log aggregation servers, and OpenTelemetry itself. Everything here is observable from `docker compose logs`, one SQL prompt, and the RabbitMQ Management UI — which is exactly what a reviewer can reproduce.

## Learning objectives

- Propagate a trace id across process boundaries the way the message does: HTTP header → outbox row → relay claim → AMQP header → consumer MDC → projection log.
- Make the relay and consumer inherit and *re-emit* the trace id without a single global mutable.
- Define and read three lag signals and two error signals, with threshold values you can defend.
- Follow one failed path end-to-end from logs alone, and prove it by doing it blind.
- Explain the MDC-vs-OpenTelemetry choice as a four-part tradeoff statement.

## Warm-up

Re-read the "Observability: The Management API As The Test Oracle" section of `posts/series-3-rabbitmq/06-operational-testing.md`, then look at the log line your R07 relay prints today and answer: does it contain the same id the REST request logged? If not, you have found the exact gap this kata closes.

## System specification

**Scope in**

- Instrumentation on the existing R07/X02 stack: a `traceId` column on `outbox_events` (or reuse a header slot), MDC population at REST entry, relay claim → publish with the trace id in the AMQP headers, consumer read-header → MDC, projection log lines carrying it.
- A small metrics surface: two gauges/counters printed to logs on a schedule or exposed via a simple `/actuator`-free endpoint — outbox age (max), projection lag (max), queue depth, DLQ depth, nack count. A one-liner `curl` + `docker compose logs` inspection is the target, per the blog.
- Evidence folder: `trace-followed.txt`, `lag-run.txt`, `error-run.txt`.

**Scope out**

- No OpenTelemetry, no Jaeger, no Prometheus, no Grafana — the whole point is that you *don't* need them to tell the story.
- No alerting system; thresholds are documented, not wired to pagers.
- No changes to the broker topology or the outbox semantics — R07's design stays untouched.

**Functional requirements (minimal)**

1. One `traceId` value appears in ≥5 log lines spanning at least three processes (REST, relay, consumer) for a single submission.
2. A failed delivery produces a complete trace: submit → outbox → publish → consumer error → retry/DLQ — with the same id throughout.
3. The lag query from A10 and the Management API queue-depth query are both runnable and their outputs saved.
4. The write-up explains the correlation-storage decision (MDC + row/header propagation) as a four-part tradeoff.

**Constraints**

- Kotlin/Spring single app, local Docker (Postgres + RabbitMQ), no cloud.
- No PII in log lines — `traceId`, prescription id, event id, role, outcome only (A15 will make this a formal baseline; here it is a habit).

## Step-by-step code-along

### Step 1: Give the trace an address — the outbox row

**Do:** Migration: add `trace_id text` to `outbox_events` (nullable, because replay rows created by an operator may not have one). In the submit path (the X02 controller), generate or accept the trace id, put it in MDC, and write it onto the outbox row in the same transaction as the event — the P03/P05 discipline you already own.

**Run:** Submit one prescription via your X02 API; `SELECT trace_id, event_id FROM outbox_events ORDER BY created_at DESC LIMIT 1;` then grep the app log for that trace id.

**Observe:** The REST-side log lines (X02's pattern) carry the id. The outbox row carries it. The relay's current log does **not** — that contrast is your baseline evidence.

**Decision:** Client-provided `X-Trace-Id` header vs server-generated? Nudge: accept-and-fall-back — a provided id links your log to the client's own system, but you must generate one when absent so the trace is never empty.

### Step 2: Cross the relay boundary — claim carries context

**Do:** Change the relay to read `trace_id` from the outbox row it claims (A09's `SKIP LOCKED` claim loop is where this reads naturally), put it in MDC around the publish call, and add it to the AMQP message headers (`messageProperties.headers["traceId"]`). Log the claim and the confirm with the id. Restore the previous MDC value after the batch — MDC is per-thread and thread pools recycle threads.

**Run:** Submit again; grep the log for the trace id; inspect the message in the RabbitMQ Management UI (`Queues → Get messages` shows headers).

**Observe:** The id now appears on the relay's claim line, the confirm line, and the message headers — the trace crossed the process boundary the way the message did. This is the "trace id via MDC" option in the fork below, made concrete.

**Decision:** MDC `put`/`remove` manually vs `MDC.putCloseable` in a `use {}` block? Nudge: `putCloseable` restores the previous value on close — it is the Kotlin-friendly idiom and it kills the leaked-context-on-pooled-thread bug in one move.

### Step 3: Cross the consumer boundary — header → MDC

**Do:** In the consumer (R07's listener or the projection applier from A10), read `traceId` from the message headers at delivery, put it in MDC for the duration of processing, and log the ack/nack outcome with it. Use the same `putCloseable` idiom.

**Run:** The full path: submit → relay → consumer → projection. Then `grep traceId app.log | cut -d' ' -f1 | uniq -c` (or whatever your format makes trivially greppable).

**Observe:** One id, ≥5 lines, across REST/relay/consumer. Save the grep to `evidence/trace-followed.txt`. This is the exact artifact Milestone 6 asks for: "a failure can be followed from request to durable outcome without reading source code."

**Decision:** Should the consumer *generate* a new id when the header is absent (operator replay)? Nudge: preserve the original id for correlation, but append a suffix or log "no-trace" — the audit value of knowing the message came from a manual replay outweighs cosmetics.

### Step 4: The lag and error signals

**Do:** Three small queries/scripts you can run anytime:

```sql
-- outbox age
SELECT max(now() - created_at) AS oldest_pending FROM outbox_events WHERE published_at IS NULL;
-- projection lag (your A10 view)
SELECT max(h.sequence_no) - coalesce(max(p.sequence_no), 0) AS behind
FROM prescription_status_history h LEFT JOIN status_projection p USING (prescription_id);
```

and one Management API call: `curl -s http://localhost:15672/api/queues | jq '.[] | {name: .name, depth: .messages, unacked: .messages_unacknowledged}'`. Add two counters to the consumer log lines — `outcome=nack` and `outcome=ack` — so `grep -c 'outcome=nack' app.log` is your error count.

**Run:** All three while the system is idle; save to `evidence/lag-run.txt`.

**Observe:** Idle values: outbox age ≈ 0, lag 0, depth 0. Define three thresholds and write them in a comment block at the top of the instrumented class, e.g. `outbox > 30s`, `lag > 50`, `dlq > 0`. The DLQ threshold of *zero* is the blog post's lesson: poison messages accumulate where the work queue looks healthy.

**Decision:** Queue-depth-only alerting vs DLQ-aware? Nudge: the blog post is explicit — DLQ depth, not work depth, is the alert; write that sentence in your README because it is the operational claim the tests assert.

### Step 5: The failure run — one trace, one story

**Do:** Instrument nothing new; just *use* what exists. Induce a failure: point the consumer at a malformed payload, or stop the projection applier and let the consumer nack into retry. Then read the story from logs and queue state.

**Run:** Induce the failure, wait for retry/DLQ per your R07 topology, then: `grep <traceId> app.log` + the queue-depth curl + the outbox-age query.

**Observe:** The trace shows submit → outbox → publish → consumer error → retry → DLQ, with `outcome=nack` counted, DLQ depth = 1, and the same trace id throughout. Save to `evidence/error-run.txt`. Nothing here required a dashboard.

**Decision:** Do you *log* the malformed payload or only its size/hash? Nudge: this is the A15 question in miniature — log the diagnosis (size, hash, exception), not the medication details.

### Step 6: Counters without a framework (optional)

**Do:** A scheduled log line (or a plain endpoint) that prints the five numbers: oldest pending outbox, projection behind, queue depth, DLQ depth, nack count since boot — one line, one timestamp.

**Run:** The failure run again; watch the scheduled line change.

**Observe:** The single line is the whole health story; `docker compose logs | grep health` is your dashboard. If you later want real metrics, this line is the spec for the OpenTelemetry export — which is the interview point about not overbuilding.

## Try this

**Follow the trace blind.** Reproduce the Step 5 failure, then hand your notes (or a colleague, or tomorrow-you) only the log file and the three runnable queries, and ask: "what failed, when, and what was the durable outcome?" If the answer takes more than three minutes or requires reading source, the trace is incomplete — go back and find which hop lost the id. This is the operational definition of Milestone 6's exit criteria, and it is the demo that lands in a walkthrough because it is reproducible from a cold repo.

**Second experiment — the false-healthy trap.** Stop the projection consumer, leave everything else running, and run the signal queries. The work queues are empty, confirms are green, outbox age is 0 — and projection lag climbs. That is the exact "quiet and healthy while something silently grows" shape the blog post warns about; you should be able to explain why *lag* is the signal that caught it, and why queue depth would have lied.

## Trade-off fork

Pick **one**, write 3–5 lines justifying it, and name the lost benefit.

- **A: trace id via MDC + outbox row + AMQP headers vs B: OpenTelemetry/OTel propagators.** MDC is zero-dependency, greppable, and matches what a 2–5h challenge can prove — it is also per-app, per-thread, and silent across JVMs, so a multi-service future outgrows it. OTel propagates the context for you and survives process boundaries by standard — it is a dependency, an exporter, and an integration budget this exercise would spend on queue depth instead.
- **A: structured JSON logs vs B: plain text logs.** JSON is trivially greppable, machine-parseable, and what a reviewer expects from "structured" — it is harder to read in `docker compose logs` and needs a `jq` habit. Plain text reads well in a terminal — and every trace-following test you wrote today becomes string surgery instead of a field query.

## Hints

**Hint 1:** If the trace dies at the relay, the cause is almost always that the relay logs *after* the publish inside a scope where the MDC was already cleared, or that the claim query selected rows without `trace_id` in the `SELECT` list — the id must be part of the claimed row, not re-read later. If the trace dies at the consumer, check whether you read the header *before* Spring's listener invokes your handler (a `MessagePostProcessor` or the `Message` parameter itself is the reliable place).

**Hint 2:** If a pooled thread's log lines intermittently carry *another* request's trace id, you found a leaked MDC: the `put` without a `remove`/`putCloseable` pairing. The fix is the `use {}` idiom from step 2 — and note that this bug is invisible in single-threaded tests, which is why the two-submission sequential run in step 3 must be asserted by grepping, not by eyeballing.

## Checkpoint / success criteria

You may leave when:

- `evidence/trace-followed.txt` shows one id across ≥5 lines and ≥3 roles (REST, relay, consumer).
- `evidence/error-run.txt` shows the same id surviving a failure into retry/DLQ with `outcome=nack` counted.
- All five signals are runnable as one-liners, with thresholds written next to them.
- A blind run of "follow the trace" takes under three minutes and names the durable outcome.
- You can deliver the MDC-vs-OpenTelemetry and JSON-vs-plain tradeoffs as four-part statements (assumption / alternative / chosen / sacrificed) without notes.

## Bottleneck & reflection questions

1. Your trace id lives in the outbox row, the message headers, and the logs. Which of those three survives an operator *replay* of a dead-lettered message, and what does your step-3 decision do about it?
2. The lag signal caught the stopped-consumer case that queue depth missed. What failure does *lag* miss that outbox age would catch, and what failure do both miss?
3. You chose MDC because the challenge is one app. What is the first concrete thing that breaks when the app becomes two processes — and is the fix still MDC-shaped?
4. Milestone 6 says "no patient-sensitive details" in logs. Where in today's log lines is a medication name or patient identifier one `toString()` away from leaking, and which of the five signals would reveal that leak to a reviewer?
5. The health line you built in step 6 is a spec for future metrics. Which of its five numbers would you alert on at what threshold, and what patient-visible symptom does each threshold correspond to?

## Handoff

- **Next elective:** `A13_chaos_drill_script.md` — the drill reuses these five signals as its "system healthy again" checks, then `A15_security_baselines.md` formalizes the no-PII rule the traces must obey.
- **Related showcase exercise:** `../../pharmacy-fulfillment/exercise_03_production.md`, Milestone 6 — this kata is the rehearsal for its evidence: correlation lifecycle, metric names without sensitive payloads, and a recovery record for a dead letter or stuck outbox row.
- **Interview line:** "Correlation is propagated the way the message is — HTTP header to outbox row to AMQP header to consumer MDC — so one trace id survives across REST, relay, and consumer, and I proved it by following a failed delivery to the DLQ from logs alone. Lag and DLQ depth, not queue depth, are the signals I alert on."

## Optional stretch

One harder twist: add a *stuck outbox row* failure — a relay that fails to confirm (stop the broker mid-batch) — and make the outbox-age signal drive a written recovery procedure: "row older than threshold → inspect attempts → republish via the A09 claim path." Measure time-to-recovery, and state the residual risk window in one sentence, in the spirit of the four-part tradeoff statement.
