# AC Nightshift Incident Lab — Track Capstone (Code-Along)

## Objective

You inherit a "mostly works" nightshift pipeline for a fictional game studio's build-artifact courier, and your job is not to build it — it is to *run it through an incident lab*: make its failures visible, classify its poisons, put a parking lot next to the dead-letter queue, prove its outbox/inbox at scale, defend per-entity ordering, turn status into a derived projection served over SSE with hard edges, and then prove the whole thing with a chaos drill, a runbook, a cut-line document, and security baselines. You leave with a project folder full of **evidence** (drill verdicts, lag signals, poison budgets, coverage matrix) and the ability to say one honest sentence about exactly-once *effects* — never exactly-once *delivery*.

This is Track F's capstone: it weaves **A01–A15 into one lab narrative** instead of fifteen separate apps. The pharmacy-fulfillment exercises built the product; this capstone is the *post-Ex3 grilling* made hands-on.

## Time box

~8–12h, multi-session allowed. Milestone budget:

| Milestone | What it covers | Budget |
|---|---|---|
| M1 — First watch, then touch | A12, A01, A02 | ~2–3h |
| M2 — Make the backbone operable | A03, A04, A07, A05, A08 | ~3–4h |
| M3 — Fast *and* consistent | A09, A10, A11, (A06 micro) | ~2–3h |
| M4 — Prove it, cut it, guard it | A13, A14, A15 + coverage matrix | ~2–3h |

Core path = A01, A03, A04, A09, A11, A13, A14, A15. The drills (A02, A05, A06, A07, A08, A10, A12) are skip-eligible *by design* — but most are already woven into core milestones, so skipping them costs little and saves nothing. See the checklist for the weaving.

## Prerequisites

**Track electives that should be complete** — finish them, or write a waiver for each row in the Skill checklist before you start:

**Core path (mandatory):**

| ID | Title | Where it lands in the lab |
|---|---|---|
| A01 | [Poison and parking lot](../advanced/A01_poison_and_parking_lot.md) | M1.2 — the poison incident |
| A03 | [Outbox at scale (local)](../advanced/A03_outbox_at_scale_local.md) | M2.1 — the operable outbox |
| A04 | [Inbox exactly-once effect](../advanced/A04_inbox_exactly_once_effect.md) | M2.2 — the dedup contract |
| A09 | [Postgres under contention](../advanced/A09_postgres_under_contention.md) | M3.1 — SKIP LOCKED nightshift workers |
| A11 | [SSE hard edges](../advanced/A11_sse_hard_edges.md) | M3.3 — the player feed |
| A13 | [Chaos drill script](../advanced/A13_chaos_drill_script.md) | M4.1 — the drill |
| A14 | [Cut-line architecture](../advanced/A14_cut_line_architecture.md) | M4.2 — CutLine.md |
| A15 | [Security baselines](../advanced/A15_security_baselines.md) | M4.3 — the guards |

**Drills (skip-eligible; each has a woven cheap home):**

| ID | Title | Woven into |
|---|---|---|
| A02 | [Backpressure and prefetch](../advanced/A02_backpressure_and_prefetch.md) | M1.3 — poison-saturation run |
| A05 | [Ordering keys](../advanced/A05_ordering_keys.md) | M2.4 — ordered pipeline |
| A06 | [Saga lite](../advanced/A06_saga_lite.md) | M3.4 — optional micro-milestone |
| A07 | [Idempotent HTTP + brokers](../advanced/A07_idempotent_http_and_brokers.md) | M2.3 — ingest |
| A08 | [Connection and channel lifecycle](../advanced/A08_connection_channel_lifecycle.md) | M2.5 — restart scenario |
| A10 | [Read models / projections](../advanced/A10_read_models_projections.md) | M3.2 — SSE projection source |
| A12 | [Observability slice](../advanced/A12_observability_slice.md) | M1.1 — the evidence spine |

**Tools:** JDK 17+, Docker Compose, RabbitMQ Management UI (`http://localhost:15672`, guest/guest), `psql`, `curl` + `jq`, `git`.

**Position vs showcase:** this capstone sits **after** [`../../pharmacy-fulfillment/exercise_03_production.md`](../../pharmacy-fulfillment/exercise_03_production.md) — it is the interview-grilling synthesis of everything Ex3 built, in a throw-away geek domain so the grilling questions ("what happens when the relay dies?", "prove the duplicate is safe", "defend the cut") get hands-on rehearsal *without* rebuilding the pharmacy product. It pairs with `../../../posts/series-5-interview/01-take-home-walkthrough.md` and `../../../posts/series-5-interview/04-showcase-interview-defense.md` as the evidence base for the oral defense.

## Blog & curriculum links

All paths are repo-relative from `showcase_projects/electives/advanced/`.

- `../../../posts/series-5-interview/01-take-home-walkthrough.md` — the walkthrough skeleton this lab feeds: start with the player, then architecture, then *messaging semantics said precisely* ("say exactly what your system does and does not guarantee").
- `../../../posts/series-5-interview/02-tradeoffs.md` — the four-part tradeoff statement ("state it, then prove you understand the consequences"), the ordering-scope rule (per prescription / per artifact, never global), and the exactly-once vocabulary check that this whole lab is built to make you pass.
- `../../../posts/series-3-rabbitmq/04-retries-dead-letters.md` — "classify the failure before you retry it," the three fates of a failed message, and bounding the retry budget with `x-death`. This is M1.2's theory.
- `../../../posts/series-3-rabbitmq/06-operational-testing.md` — the Management API as test oracle, "a mock cannot crash," and the DLQ forensics/replay section. This is M4.1's theory.
- `../../../posts/series-2-postgres/02-transactions-isolation.md` — the transaction failure cases (process dies before/after commit, outbox insert fails, broker unavailable after commit) that every milestone in this lab re-witnesses on the nightshift pipeline; pair with `../../../posts/series-3-rabbitmq/03-ack-prefetch-concurrency.md` for the ack-after-commit rule and `../../../posts/series-2-postgres/04-indexes-query-plans.md` for `SKIP LOCKED`.

## Background & motivation

**The fantasy.** *Emberline Games* ships patches overnight. Builds land at 02:00; by 07:00 the player-facing "patch live" window opens. Between those two times a small pipeline — the **Nightshift Courier** — has to take each artifact event from the build farm, through an outbox, across a RabbitMQ broker, into courier workers that stage chunks at the edge, and finally into a status feed each player can watch ("your patch will be ready at dawn"). It is "mostly works": last night's shift left a handover note listing what *seems* fine and what *nobody dares to touch*.

Your predecessor — a senior who left for a platform team — handed you a compose file, a seed script, a consumer that treats every failure as retryable, and a graveyard of incident notes. Your job this shift: **make the pipeline provable.** Every claim you make about it ("nothing is lost", "duplicates are neutralized", "players never see each other's data") must be an assertion you can run, not a paragraph.

Why the geek domain matters: the discipline is identical to medication fulfillment, and the interviewers know it. A build artifact that must not be applied twice is a prescription that must not be dispensed twice. A player who must never see another player's patch feed is a patient whose records must never leak. A patch that arrives late but complete beats a patch that arrives broken on time — same as a prescription. When you talk about "the player waits at the dawn window," the interviewer hears "the patient waits at the pharmacy counter." You get to rehearse the grilling on a system where the stakes are imaginary and the *evidence* is real.

What this lab deliberately is not: greenfield product work, a second pharmacy system, cloud, Kubernetes, a frontend. It is an **incident lab on an inherited system** — find the broken, classify it, fix it, prove the fix, document the cut, and walk out able to say what the system guarantees *and* what it does not.

## Skill checklist (mandatory)

Gate rule from the electives README: finish the lab electives before the capstone, **or** knowingly skip with a written waiver. For every row below, mark **pass** or **skip + waiver** (one sentence: why skipped, what you accept losing). A waiver is a legitimate planning decision, not a failure — but the interview will ask about the skipped rows, so write waivers you can defend.

| ID | Core/Drill | Elective | Concrete capstone behavior to demonstrate | Status |
|---|---|---|---|---|
| A01 | CORE | [Poison and parking lot](../advanced/A01_poison_and_parking_lot.md) | Poison classification (retryable vs permanent) + parking lot (TTL queue, budget via x-death) + operator replay from DLQ; every failed message lands in exactly one of three visible fates; budget survives broker restart | pass / skip + waiver |
| A02 | DRILL | [Backpressure and prefetch](../advanced/A02_backpressure_and_prefetch.md) | Prefetch/concurrency saturation run on the nightshift workers; comparison table of ≥3 settings (throughput, peak unacked, elapsed); chosen pair justified with blast radius | pass / skip + waiver |
| A03 | CORE | [Outbox at scale (local)](../advanced/A03_outbox_at_scale_local.md) | Outbox at scale: batch relay with claim-based concurrency, backoff on failure, stuck-row detection + release path, lag/age metrics; 500-event drain with disjoint claims; broker-down run loses zero rows | pass / skip + waiver |
| A04 | CORE | [Inbox exactly-once effect](../advanced/A04_inbox_exactly_once_effect.md) | Inbox exactly-once effect: same event delivered 2×/3×/concurrently → one durable effect, one inbox row, duplicate acked; failed effect rolls back claim + effect; precise guarantee language (never "exactly-once delivery") | pass / skip + waiver |
| A05 | DRILL | [Ordering keys](../advanced/A05_ordering_keys.md) | Per-artifact ordering keys: single effective consumer per key, competing consumers for independent artifacts; missing-sequence gap visible; throughput ordered vs competing recorded | pass / skip + waiver |
| A06 | DRILL | [Saga lite](../advanced/A06_saga_lite.md) | Saga-lite: reserve→approve→package with compensating actions; each failure arrow produces a recorded compensation; crash-restart at commit-then-ack re-executes zero business effects | pass / skip + waiver |
| A07 | DRILL | [Idempotent HTTP + brokers](../advanced/A07_idempotent_http_and_brokers.md) | Idempotency-Key on the ingest POST aligned with outbox event_id: retry → one artifact, one outbox row, one message, one consumer effect; same key + different payload → 409 | pass / skip + waiver |
| A08 | DRILL | [Connection and channel lifecycle](../advanced/A08_connection_channel_lifecycle.md) | Shared connection / channel-per-thread / reconnect: one app connection with N channels in the UI; broker-restart drill with zero lost work; manual queue deletion recovered by re-declaration | pass / skip + waiver |
| A09 | CORE | [Postgres under contention](../advanced/A09_postgres_under_contention.md) | SKIP LOCKED claim queue for nightshift workers (100 rows split across 3 workers, each claimed once); deadlock captured (40P01) then fixed with stable lock order, 20/20 clean runs; pg_locks evidence | pass / skip + waiver |
| A10 | DRILL | [Read models / projections](../advanced/A10_read_models_projections.md) | Status projection over append-only history: delete + rebuild with identical row counts; deliberately broken apply order shows gap; "projection is derived, never a second source of truth" defense | pass / skip + waiver |
| A11 | CORE | [SSE hard edges](../advanced/A11_sse_hard_edges.md) | SSE hard edges: per-player isolation (zero cross-player ids), slow-consumer behavior, heartbeat, authz before replay AND inside the replay query; never a Rabbit consumer per connection | pass / skip + waiver |
| A12 | DRILL | [Observability slice](../advanced/A12_observability_slice.md) | Observability slice (MDC only, no OpenTelemetry): one trace id across REST → outbox → relay → broker → consumer → projection; lag/confirm/nack/queue-depth/DLQ signals as runnable one-liners; blind follow-the-trace < 3 min | pass / skip + waiver |
| A13 | CORE | [Chaos drill script](../advanced/A13_chaos_drill_script.md) | Chaos drill script + runbook: kill relay mid-publish, restart broker with pending messages; PASS from durable state (effects once per event id, ≥1 observed duplicate); `--random --iterations 10` → 10 PASS verdicts | pass / skip + waiver |
| A14 | CORE | [Cut-line architecture](../advanced/A14_cut_line_architecture.md) | CutLine.md scoping ADR: two-plan table (2h honest vs 5h closed) with player-consequence cells, risk-ordered gap list, next-three-hours order; 2h slice passes the same journey assertions; three-sentence story aloud | pass / skip + waiver |
| A15 | CORE | [Security baselines](../advanced/A15_security_baselines.md) | Security baselines with stand-ins (no auth framework): tenant scoping test (REST + SSE replay deny cross-player reads, 403 zero bytes), least-privilege DB role with privilege-evidence test, log-assertion test that fails on PII leak; SECURITY.md with the one-line real-auth path | pass / skip + waiver |

**Coverage matrix** (fill at the end of M4; this is the file the interviewer can read in 30 seconds):

| Skill | M-marked at | Verdict (pass / skip + waiver) |
|---|---|---|
| A01 | M1.2 |  |
| A02 | M1.3 |  |
| A03 | M2.1 |  |
| A04 | M2.2 |  |
| A05 | M2.4 |  |
| A06 | M3.4 |  |
| A07 | M2.3 |  |
| A08 | M2.5 |  |
| A09 | M3.1 |  |
| A10 | M3.2 |  |
| A11 | M3.3 |  |
| A12 | M1.1 |  |
| A13 | M4.1 |  |
| A14 | M4.2 |  |
| A15 | M4.3 |  |

**Why the drill-eligibility rarely matters in practice.** The cheap drills are *woven into core milestones by design*: A12 is the evidence spine of the whole lab (M1.1), A07 rides on the ingest you must build anyway (M2.3), A10 becomes the SSE projection source (M3.2), A05 is the ordered variant of the queue M2 already runs (M2.4), A08 is just the restart scenario M4 needs (M2.5), A02 is a half-hour extension of the M1 poison run (M1.3), and A06 is an optional micro-milestone that reuses A04's inbox (M3.4). Skipping a woven drill costs you a table row of interview evidence and saves almost no time — so the honest default is **pass** everywhere except A06, which you may waive if the saga doesn't interest you.

## Learning objectives

- Run an inherited pipeline as an *incident lab*: observe before fixing, reproduce before claiming, and leave every claim as a runnable assertion.
- Classify poison (retryable vs permanent), bound its budget with `x-death`, park it in a TTL queue, and replay it from the DLQ as an operator — with every failed message visibly landing in exactly one of three fates.
- Operate an outbox at scale: batch relay with claim-based concurrency, backoff, stuck-row detection/release, and lag/age metrics — with zero rows lost across a broker-down run.
- Prove exactly-once *effect* under at-least-once delivery (inbox dedup inside the effect transaction) and use the correct vocabulary every time you describe it.
- Apply per-entity ordering keys, then defend the throughput you gave up.
- Run Postgres workers under contention: capture a real 40P01 deadlock, fix it with stable lock order, and show 20/20 clean runs plus `pg_locks` evidence.
- Derive a status projection from append-only history and serve it over SSE with per-player isolation, heartbeats, and authz in the replay path — never one Rabbit consumer per connection.
- Run a chaos drill with binary PASS/FAIL verdicts, write a runbook that is the script's assertions as human steps, and cut a two-plan `CutLine.md` with player-visible consequences.
- Enforce security baselines with stand-ins: tenant scoping, least-privilege DB roles, and a log-assertion test that fails on PII.

## Warm-up

Read "Classify the Failure Before You Retry It" and "Bounding the Budget with x-death" in `../../../posts/series-3-rabbitmq/04-retries-dead-letters.md`. Then answer, in three lines, for the Nightshift Courier: *what are the three fates a failed artifact event can land in, what distinguishes a retryable from a permanent failure in this domain, and what does the budget have to do with a broker restart?* Then, as a tiny probe: after booting the stack (Project bootstrap below), open the Management UI and write down the number of queues, the connection count, and the DLQ depth. You will re-read that screen every milestone — the UI is the lab's test oracle.

## Project bootstrap

**Exact directory:** `showcase_projects/electives/projects/nightshift-incident-lab/`

You are not given a solution repo. You are given a *handover*. Build the minimal skeleton the handover implies, seed it, and watch it misbehave the way the handover says it does.

### Do

Create the project skeleton (empty Gradle Spring Boot app, Kotlin; if you want to copy the dependency shape from your Ex3 build, that is exactly what the handover would have done):

```bash
mkdir -p showcase_projects/electives/projects/nightshift-incident-lab
cd showcase_projects/electives/projects/nightshift-incident-lab
# settings.gradle.kts, build.gradle.kts (spring-boot-starter-web, amqp, jdbc, postgresql, testcontainers for later)
```

Create `docker-compose.yml` with **pinned** images (the version-pin rule from the blog post — pins, not `latest`):

```yaml
services:
  db:
    image: postgres:16-alpine   # pinned exactly
    environment: { POSTGRES_DB: nightshift, POSTGRES_USER: nightshift, POSTGRES_PASSWORD: nightshift }
    ports: ["5432:5432"]
  broker:
    image: rabbitmq:3.13-management   # pinned exactly
    ports: ["5672:5672", "15672:15672"]
```

Create the schema the handover mentions (sketch; fill the `-- ...` parts):

```sql
-- artifact_events: the outbox, written in the same tx as the artifact row
CREATE TABLE artifact_events (
  id           BIGSERIAL PRIMARY KEY,
  event_id     UUID NOT NULL UNIQUE,
  artifact_id  TEXT NOT NULL,
  player_id    TEXT NOT NULL,
  type         TEXT NOT NULL,          -- ARTIFACT_BUILT | CHUNKS_STAGED | PATCH_LIVE ...
  payload      JSONB NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at TIMESTAMPTZ,
  claimed_by   TEXT,
  claim_seq    BIGINT
);
CREATE TABLE courier_inbox ( ... );           -- consumer dedup, one row per event_id
CREATE TABLE artifact_event_history ( ... );  -- append-only fact log
CREATE TABLE artifact_status ( ... );         -- derived projection (M3.2's target)
CREATE TABLE nightshift_claim_queue ( ... );  -- worker claim queue (M3.1's target)
```

Seed script `seed.sql`: 5 artifacts, each with the full event chain, plus **one artifact whose checksum payload is corrupted** (this is the poison the handover says "keeps getting redelivered all night") and one whose `player_id` is empty.

Write the "handover" consumer exactly as described in the note — every failure is retried forever, no classification:

```kotlin
@RabbitListener(queues = ["artifact.work"])
fun onEvent(msg: ArtifactEvent) {
    try { stageChunks(msg) ; ack(msg) }
    catch (e: Exception) { requeue(msg) }   // the inherited bug: no classification, no budget
}
```

Add `README.md` with only these section headers (you fill them as you go):

```markdown
# Nightshift Courier — Incident Lab
## The pipeline (one paragraph, player-first)
## Topology (queues, exchanges, bindings)
## How to run (compose up, seed, ingest one event)
## The five signals (lag / confirms / nacks / queue depth / DLQ depth)
## Incidents and verdicts (evidence/)
## CutLine.md (the 2h vs 5h story)
## Skill checklist coverage
```

### Run

`docker compose up -d`, apply `seed.sql`, boot the app, and watch the Management UI for 60 seconds.

### Observe

The handover's claims reproduce themselves: the corrupted-checksum event is requeued endlessly (watch `messages_requeued` climb in the UI), queue depth oscillates instead of draining, and there is *no DLQ yet* — nothing is ever classified. You are now looking at the three fates problem from the warm-up, live, with a dead-queues screen and a handover note you can improve. Welcome to the night shift.

## System specification

**Product fantasy.** Emberline Games builds overnight; the Nightshift Courier gets artifacts to players by dawn. The player sees one thing: a status feed — "patch available at 07:00." The operator sees the pipeline. The studio sees lag numbers.

**Actors:**

| Actor | Wants | Failure that hurts them |
|---|---|---|
| Player | Their patch feed, correct, on time, theirs alone | Wrong/missing status, another player's data, stalled feed |
| Nightshift operator (you) | To answer "is it working?" in one command | Silence, invisible retries, queues that never drain |
| Studio release team | Evidence that the dawn window will be met | A "mostly works" pipeline nobody can characterize |

**Scope in:** ingest REST endpoint (`POST /artifacts` with `Idempotency-Key`), outbox + relay, RabbitMQ work/retry/DLQ topology, courier workers, per-artifact ordered queues, status projection, per-player SSE feed, chaos drill + runbook, `CutLine.md`, security baselines, evidence folder. All local Docker.

**Scope out:** cloud, Kubernetes, a frontend beyond a `curl`able SSE endpoint, any auth framework (stand-ins with documented one-line real path in SECURITY.md), OpenTelemetry (MDC only), multi-node anything, and any claim of production capacity.

**Functional requirements (the lab's contract):**

1. Every artifact event is ingested, relayed, and effected **at least once**; effects run **exactly once** per event id (inbox).
2. Every failed message lands in exactly one of three visible fates: processed, parked-and-replayed, or dead-lettered — never silently requeued forever.
3. `GET /players/{id}/patch-feed` streams per-player status over SSE; cross-player reads return `403` with zero bytes.
4. The pipeline survives: relay killed mid-publish, broker restart with pending messages, worker deadlock — with zero lost rows and observable safe duplicates.
5. `CutLine.md` names what a 2-hour incident response ships vs what the 5-hour version closes, with a player-visible consequence per cut.

**Non-functional / evidence requirements:**

| Requirement | Evidence artifact |
|---|---|
| Lag is a number, not a feeling | One-liner lag query (oldest unpublished outbox row / oldest unconsumed event) |
| Poison budget survives restarts | `x-death` header after broker restart + DLQ message replayed through a fresh run |
| Prefetch choice is justified | ≥3-setting comparison table with blast-radius note (A02) |
| 500-event drain with disjoint claims | Relay log + row counts: each event claimed by exactly one claimer |
| Deadlock fixed for good | 40P01 captured once, then 20/20 clean runs + `pg_locks` snapshot |
| Chaos verdicts are binary | `evidence/run-00N/` with PASS/FAIL lines; `--random --iterations 10` → 10 PASS |
| No PII in logs/queues | Log-assertion test that *fails* on a leaked player id/email |

**Constraints:** local Docker only with pinned images; single narrative (this lab, not fifteen apps); core path is mandatory, drills are waiver-eligible but woven in; every milestone ends with a verdict line written to `evidence/`.

## Milestones (code-along)

### M1 — First watch, then touch (A12, A01, A02)

#### M1.1 The evidence spine (A12, DRILL)

**Do.** Add MDC-only tracing: an `X-Trace-Id` header on the ingest POST; carry the same id into the outbox row, into the message headers, into the consumer's MDC, and into projection logs. No OpenTelemetry — one `MDC.put("traceId", ...)` per hop, copied, never regenerated. Then write the five one-liners the handover's successor will run at 3am:

```bash
# lag: oldest event not yet published
psql ... -c "SELECT now() - max(created_at) AS lag FROM artifact_events WHERE published_at IS NULL;"
# queue depth + DLQ depth (Management API)
curl -s localhost:15672/api/queues | jq '[.[] | {name: .name, depth: .messages, dlq: .name | contains("dlq")}]'
# nack/requeue rate and confirm count — from logs, grep-filtered by traceId
```

**Run.** Ingest 20 events (a small curl loop), then **blind follow-the-trace**: pick one trace id at random from the logs and, without reading the app code, find it in the REST access log, the relay log, the broker's view, and the consumer log — in order. Time yourself.

**Observe.** The id survives every hop or it does not; whichever you find, write the gap into `README.md`. The < 3-minute blind-trace rule is the acceptance test — if you cannot follow a single event through the pipeline in three minutes, the spine is broken and every later milestone's evidence will be suspect.

#### M1.2 The poison incident (A01, CORE)

**Do.** Classify. The handover consumer requeues everything; replace that with an explicit policy table:

```kotlin
fun classify(msg: ArtifactEvent): Fate = when {
    msg.checksumInvalid() -> Fate.PERMANENT        // corrupted artifact manifest
    msg.edgeStagingFull() -> Fate.RETRYABLE        // transient capacity
    msg.payloadMissing()  -> Fate.PERMANENT
    else                  -> Fate.RETRYABLE
}
```

Declare the topology the A01 elective taught you: `artifact.work` → `artifact.retry` (TTL queue, e.g. 30s TTL, DLX on exhaustion) → `artifact.dlq`. Set the budget with `x-death` (e.g. 5 attempts) and keep the DLQ for permanent + exhausted messages. Add the operator replay path: a tiny endpoint or script that republishes a DLQ message with its **original event_id** (never a new one).

**Run.** Re-seed the corrupted artifact. Watch the retry queue pulse (ready → TTL → back), then the DLQ fill. Restart the broker with a message mid-retry, then replay one DLQ message.

**Observe.** Three fates, each visible: the corrupted event dead-lettered once and only once; the transient one parked and retried; every replayed event landing at the same inbox-protected effect. The `x-death` header survives the broker restart — that is the "budget survives restart" row of the checklist; save a screenshot of the headers. Grep the DLQ message to confirm its original `event_id` is intact (the replay path depends on it).

**Mini trade-off (record in the log):** classify by exception type at the consumer vs classify by payload validation before the consumer ever sees the message. The second catches poison earlier — the first catches it where the error actually occurs. Pick one, write 3–5 lines, name the lost benefit.

#### M1.3 Prefetch saturation run (A02, DRILL)

**Do.** With the retry topology in place, instrument a run: seed 300 healthy events, run the consumer at **prefetch 1, 5, 20** (and one concurrency variant), measuring per setting: throughput (events/min), peak unacked (Management API), and elapsed to drain.

**Run.** The three runs, recording into a table in `evidence/m1-prefetch.tsv` (or a markdown table in `README.md`).

**Observe.** Saturation behavior: unacked climbs with prefetch, drain time drops, and — the interesting part — the *blast radius*: at prefetch 20, a single poison's retries occupy more unacked slots for longer. Pick the pair (e.g. "5 for the workers, 20 would be tempting but...) justify the choice in one sentence with blast radius, not peak throughput. Save the table.

### M2 — Make the backbone operable (A03, A04, A07, A05, A08)

#### M2.1 The outbox at scale (A03, CORE)

**Do.** The handover's relay is a single loop that publishes one row at a time and marks `published_at` eagerly (the classic silent-loss bug). Replace it: batch relay claiming rows with **claim-based concurrency** — the claim is the `SKIP LOCKED` pattern from A09 previewed here:

```sql
UPDATE artifact_events
   SET claimed_by = :worker, claim_seq = nextval('claim_seq')
 WHERE id IN (
   SELECT id FROM artifact_events
    WHERE published_at IS NULL AND claimed_by IS NULL
    ORDER BY id
    LIMIT 100
    FOR UPDATE SKIP LOCKED
 )
RETURNING id, event_id;
```

Add: backoff on broker failure (stop claiming, retry with sleep), **stuck-row detection** (rows claimed longer than N seconds → release claim for re-claim), and lag/age metrics on `published_at` (the M1.1 lag query becomes the M2.1 lag query).

**Run.** Seed **500 events**. Run the relay with 3 concurrent claimers (workers/threads). Record disjoint claims (each event_id claimed by exactly one worker — log or SQL `SELECT claimed_by, count(*)`). Then the broker-down run: stop the RabbitMQ container mid-drain, wait, bring it back.

**Observe.** Drain of 500 with disjoint claims; zero rows lost across the broker-down run (rows stay unpublished, relay backs off, then drains after restart — `published_at` count = 500, never "lost"). The eager-mark bug would show as `published_at IS NOT NULL` rows that never reached the broker — if you see that, you just witnessed the exact bug the handover warned about; fix it by marking only after confirm.

#### M2.2 The inbox contract (A04, CORE)

**Do.** The handover's consumer effects chunks without dedup — a redelivery re-stages chunks. Add `courier_inbox` dedup **inside the effect transaction**:

```kotlin
@Transactional
fun effect(msg: ArtifactEvent): EffectOutcome {
    if (inboxRepo.insertIgnoreDuplicate(msg.event_id) == 0) return DUPLICATE_ACK  // ack, no effect
    stageChunks(msg)                                   // the business effect
    historyRepo.append(msg.event_id, "CHUNKS_STAGED")  // same tx
    return EFFECTED
}
```

**Run.** Deliver the same event 2×, 3×, and concurrently (two consumers racing). Then the failure path: make `stageChunks` throw, and observe what happens to the claim and the inbox row.

**Observe.** One durable effect, one inbox row, duplicates acked — regardless of delivery count or concurrency. On failure, claim + effect + inbox row all roll back together (the failed effect never leaves a half-effect). Write the sentence you will say in the interview, verbatim, into `README.md`: **at-least-once delivery, exactly-once effect — the broker may redeliver, the effect may not run twice.** Cross out "exactly-once delivery" everywhere it appears in your notes.

#### M2.3 Idempotent ingest (A07, DRILL)

**Do.** On `POST /artifacts`, accept an `Idempotency-Key` header; derive the outbox `event_id` from it (deterministic UUID v5 of the key, or store key→event_id). Retries of the same key must return the *first* response, not a second artifact.

**Run.** POST the same key 3×; then POST the same key with a **different payload**.

**Observe.** One artifact row, one outbox row, one message, one consumer effect — the whole path from A07 collapses to a single row of evidence. The same key with a different payload → `409`; you now have a test for that, and it belongs in the evidence folder.

#### M2.4 Per-artifact ordering keys (A05, DRILL)

**Do.** Chunk events for one artifact must apply in sequence (chunk 3 before chunk 4). Introduce ordered queues: routing key `artifact.<id>`, one queue per artifact, each bound to a **single effective consumer** (one consumer per queue — not one listener per message). Independent artifacts compete normally.

**Run.** Send chunks of artifact A with a deliberately dropped sequence in the middle; then record the throughput table: ordered run vs fully-competing run, same event count.

**Observe.** The missing-sequence gap is *visible* (a gap detector — a consumer-side check or a SQL query on history — logs `GAP artifact=A expected=4 got=5`). The competing run is faster; the ordered run is correct. Record both numbers; the difference is the price of ordering, and it will reappear in the trade-off fork below.

#### M2.5 Channel lifecycle (A08, DRILL)

**Do.** Check the Management UI: **one connection, N channels** (channel per thread, shared connection — the pattern, not a new connection per consumer). Then the restart drill: with messages pending, restart the broker. Then delete a queue by hand and watch.

**Run.** Broker restart with pending messages; then `rabbitmqadmin delete queue name=artifact.work`; then let the app idle 10s.

**Observe.** Zero lost work across the restart (the outbox absorbs it — this is the M4 drill's dry run). The manually deleted queue is **re-declared** by the app on reconnect — topology re-declaration is your recovery path; if the app does not re-declare, that is a finding (record it: "the queue the operator deleted stays deleted until redeploy").

### M3 — Fast *and* consistent (A09, A10, A11, A06)

#### M3.1 SKIP LOCKED nightshift workers (A09, CORE)

**Do.** The claim queue: 100 rows in `nightshift_claim_queue`, 3 worker instances (3 JVM processes or 3 threads with distinct worker names). First, implement the *wrong* version deliberately — claim with `FOR UPDATE` and then update the artifact row in a second statement (two-row lock order: `claim_queue` then `artifact_events`) under concurrency.

**Run.** The wrong version under 3 workers → capture the **40P01 deadlock** (log + `pg_locks` snapshot). Then fix: stable lock order — always lock `artifact_events` first, then the claim row, or collapse to a single `SKIP LOCKED` claim statement so only one lock is ever taken. Re-run 20 times.

**Observe.** The deadlock appears once, is captured, and disappears: **20/20 clean runs**, each of the 100 rows claimed by exactly one worker (`claimed_by` counts sum to 100, no overlaps). The `pg_locks` evidence goes into `evidence/m3-1/`. Note how the fixed claim statement is the same shape as M2.1's relay claim — the two skills are one idiom.

#### M3.2 Status projection (A10, DRILL)

**Do.** `artifact_status` is currently written directly by consumers (the classic second source of truth). Rebuild it as a **derived projection**: consumers only append facts to `artifact_event_history`; the projection table is rebuilt from history (`apply(history)` where the apply order = event order).

**Run.** Delete `artifact_status` rows and rebuild → compare row counts to before (must be identical). Then deliberately apply events in **wrong order** (simplest: apply with a broken ORDER BY) and observe.

**Observe.** Delete+rebuild reproduces identical counts — the projection is a pure function of history. Wrong order shows a gap (artifact shows `BUILT` after `PATCH_LIVE`, or a chunk count mismatch). The defense line, written into `README.md`: **the projection is derived, never a second source of truth — when they disagree, history wins, and rebuild is the fix.**

#### M3.3 SSE hard edges (A11, CORE)

**Do.** `GET /players/{id}/patch-feed` (SSE) streams the player's artifact status from the **projection** — read from Postgres, not from Rabbit. Never one Rabbit consumer per connection; a feed is a query, not a subscription. Implement: per-player isolation (every event's `player_id` must match the requester's), heartbeat (comment frame every 15s), and **authz in two places** — before replay (reject the request) *and inside the replay query* (`WHERE player_id = ? AND scope = 'feed'` — belt and suspenders, because the second one survives a future code path that forgets the first).

**Run.** Open a feed for player A, then fetch player B's feed with A's credentials. Pause reading the stream for 60s (slow consumer) and watch.

**Observe.** Zero cross-player ids appear in A's stream (add a test that asserts this over 1000 events). The slow consumer sees heartbeats keep the connection alive instead of the server silently stalling. Cross-player fetch → `403`, zero bytes. Record the isolation test result; it is A11's core evidence and A15's warm-up.

#### M3.4 Saga-lite micro-milestone (A06, DRILL — optional but cheap)

**Do.** The "edge capacity" workflow: **reserve** edge slot → **approve** → **package**. Each arrow can fail, and each failure produces a recorded **compensation** (reserve rollback frees the slot; approve-undo resets state). Reuse the A04 inbox as the saga's idempotency so a crash-restart at commit-then-ack re-executes **zero** business effects.

**Run.** Fail each arrow in turn (a switch that forces `approve` to fail); then kill the app between the effect commit and the ack.

**Observe.** A recorded compensation per failure arrow (history rows: `COMPENSATED reserve=...`), and the kill-run shows the inbox neutralizing the re-execution. If time is short, write the waiver row for A06 now and move on — M4 is the priority.

### M4 — Prove it, cut it, guard it (A13, A14, A15)

**Order matters: A13's evidence feeds A14's gap list, so do A13 → A14 → A15, never the reverse.**

#### M4.1 Chaos drill + runbook (A13, CORE)

**Do.** Write `drill.sh` with scenarios: (1) **kill relay mid-publish** (compose-kill the relay when ~half the batch is confirmed), (2) **broker restart with pending messages** (messages sitting ready + unacked), and verdicts asserted **from durable state**: `effects = events` (once per event id) and `≥ 1 observed duplicate delivery` (the kill actually hit the window). Never a "no duplicates" claim — the PASS line reads *"duplicates observed and neutralized."* Then `--random --iterations 10` mode.

**Run.** Scenarios 1 and 2 twice each; then `./drill.sh run --random --iterations 10`.

**Observe.** 10 PASS verdicts; some runs show zero duplicates (the kill missed the window) and some show ≥1 — log the distribution; the spread is the honest crash-window statistics. Write `runbook.md` whose recovery steps are the *script's assertions as human steps*, and prove it by executing scenario 1 by hand, in a fresh shell, without the script. Evidence → `evidence/run-00N/`.

#### M4.2 The cut line (A14, CORE)

**Do.** Write `CutLine.md` for *the incident response, not the greenfield build*: what a **2-hour honest response** ships vs what the **5-hour closed response** adds. Two-plan table where every row has a **player-consequence cell** (what the player experiences without that piece), a risk-ordered gap list, and a next-three-hours order. Example row: *"Ordered queues: 2h = single work queue, ordering best-effort; 5h = per-artifact keys. Player consequence: a misordered chunk can momentarily serve a broken patch window — self-healing, visible in lag, not a loss."*

**Run.** Build a 2h-slice profile (flags disable outbox/SSE/ordered queues) and run the same journey assertions as the full system against it. Read the three-sentence story aloud.

**Observe.** The slice passes the journey; the gap list is provably ordered by risk, with A13's drill runs as the evidence column. If any 2h gap was *not* demonstrated by a drill, A14's contract is unmet — go back to M4.1 and add the scenario.

#### M4.3 Security baselines (A15, CORE — the 30-minute core is the log-assertion test)

**Do.** With no auth framework, stand-ins: a `X-Player-Token` header mapped to a player id (documented as the seam where real auth plugs in). Then three tests: (1) **tenant scoping** — REST and SSE replay deny cross-player reads (`403`, zero bytes); (2) **least-privilege DB role** — a `nightshift_app` role granted only what the app needs; a privilege-evidence test asserting `DELETE`/`DROP` fails under that role; (3) **log-assertion test** — capture the app's logs during a full journey run and assert no PII appears (no player email, no full IPs, no raw payloads). This third test is the 30-minute core — write it first; it catches the most common real leak.

**Run.** All three tests; make the log-assertion test **fail once on purpose** (log a player email in a throwaway branch) to prove it bites.

**Observe.** Cross-player reads return 403 with zero bytes; the DB role's privilege test fails before the grant fix and passes after; the PII test fails on purpose and passes once the leak is removed. Write `SECURITY.md` with the one-line real-auth path (OAuth2/PKCE → validate in middleware *and* inside the query), and note that the player scoping you proved here is the same shape as patient scoping in the pharmacy system.

#### M4.4 Final coverage matrix

**Do.** Fill the coverage matrix from the Skill checklist: pass or skip + waiver for all 15 rows, commit `README.md`, `CutLine.md`, `SECURITY.md`, `runbook.md`, and `evidence/`.

**Run.** Read the entire README aloud, end to end.

**Observe.** If any sentence makes a claim without a pointer to an evidence file, it is vibes, not evidence — fix the sentence or produce the file. This README is the artifact an interviewer reads first.

## Try this

**Experiment 1 — the dual kill (chaos, must-do).** Restart the broker while the relay is mid-publish. The relay's confirm is lost *and* the channel dies; on restart the relay republishes rows whose confirm outcome it never learned. Assert: every event delivered at least once, effects once per event id (inbox), queue drains, PASS. This single run composes two failures the unit tests cannot compose — it is the most valuable evidence in the folder for the interview.

**Experiment 2 — poison classification fuzz.** Send a message that is *retryable on first delivery* (edge staging full) and becomes *permanent on second* (manifest corrupted by the retry). Verify your classifier routes it to the DLQ on the second attempt and that the `x-death` budget was consumed, not reset. This proves classification is per-delivery, not per-message-type.

**Experiment 3 — slow SSE client.** Open a feed, stop reading for 90 seconds, then resume. Record heartbeats seen, connection state, and whether the player misses any events *that occurred while you weren't reading* (the replay path is what rescues them — the feed is a view of the projection, and the projection never paused).

**Experiment 4 — deadlock, then the fix.** Re-introduce the wrong lock order from M3.1 on purpose, hammer it with 5 workers for 60s, capture the 40P01, then apply the stable-order fix and run 20/20 clean. The capture-and-fix pair is the strongest "I've seen production concurrency" story in this whole lab.

## Trade-off forks

Pick **one** per fork (at least the first two), write 3–5 lines each, name the sacrificed property, and file them in `README.md` under a "trade-off log" section. The interviewer will ask about these; the four-part statement from `posts/series-5-interview/02-tradeoffs.md` is the shape.

- **Fork 1 — Retry vs park.** A: classify at the consumer into a bounded TTL retry (small budget, fast cycling) with everything else parked in the DLQ immediately. B: one generous budget for everything, with the DLQ as the rare escape hatch. A keeps poison from hogging unacked slots (blast radius shrinks) but risks parking a transient failure that a longer wait would have healed. B is simpler and friendlier to flaky infra but lets one poison occupy a worker's budget for hours. Which does your M1.2 evidence support, and what does the player experience in each?
- **Fork 2 — Global queue vs ordered keys.** A: one work queue, max throughput, ordering best-effort (gap detector catches misorder late). B: per-artifact ordered queues, ordering guaranteed, throughput capped by single effective consumer per key. Your M2.4 table has the numbers — use them. Which property do you sacrifice, and how does that sacrifice surface to the player (wrong patch window vs later patch window)?
- **Fork 3 — The 2-hour incident cut.** When the dawn window is at risk, the 2h response is: A — *stabilize the existing path* (classify poison, pin prefetch, expose the five signals; SSE stays as-is, possibly lagging) vs B — *rebuild the hot path in the proven shape* (outbox + inbox + DLQ from scratch, SSE down while it lands). A is fast and honest but leaves known gaps; B closes the architecture but risks shipping a half-proven pipeline at dawn. Your `CutLine.md` is the written answer; the fork is why it has a two-plan table at all.

## Hints

**Hint 1 (mild).** If a queue never drains, suspect classification before throughput: grep the consumer log for the exception and ask whether it is retryable. The nightshift rule of thumb: *if you cannot name the fate of a failed message, it has no fate — it is requeued forever.* If the `x-death` budget resets across a restart, you are counting retries in a header the broker rewrites; read the header on the *delivered* message, not the published one. If the relay loses rows during the broker-down run, check `published_at` is only marked after the confirm — the eager-mark bug is the #1 silent loss path in this lab.

**Hint 2 (medium).** Deadlock captures: 3 workers and a wrong lock order will not deadlock on every run — run it long enough (60s of hammering) or add a second statement that touches `artifact_events` before the claim row. And `SKIP LOCKED` in Postgres needs a transaction for the lock to mean anything — a claim without `@Transactional` will *look* right in a single-worker test and corrupt under 3. If SSE replays leak a cross-player row, the bug is almost always the replay query missing the `WHERE player_id = ?` clause, not the endpoint guard — which is exactly why the lab demands authz in both places.

**Hint 3 (strongest).** If the chaos drill PASSes but you still distrust it: check whether the drill's kill *actually hit the window*. A drill that never produces a duplicate is a drill that never exercised the failure — the "≥1 observed duplicate" assertion is what separates a real drill from a ceremony. And if the runbook, executed by hand, requires a single improvised command, the runbook is fiction; the fix is to copy the assertion from the script into the runbook verbatim.

## Checkpoint / success criteria

You may leave the night shift when:

- The coverage matrix shows **pass** on all CORE rows (A01, A03, A04, A09, A11, A13, A14, A15) and pass or a written waiver on every DRILL row — with the woven drills (A02, A05, A07, A08, A10, A12) all passing because they were already inside core milestones.
- `evidence/` contains: the prefetch comparison table, the 500-event disjoint-claim drain, the broker-down zero-loss run, the 40P01 capture + 20/20 clean runs + `pg_locks` snapshot, the SSE isolation test (1000 events, zero cross-player ids), the 403-zero-bytes cross-player reads, and the drill runs with `--random --iterations 10` → 10 PASS verdicts (including ≥1 duplicate observed and neutralized).
- `runbook.md` executed by hand reproduces the drill's PASS without improvising.
- `CutLine.md` has the two-plan table with player-consequence cells, risk-ordered gaps, and a next-three-hours order, and the 2h slice passed the same journey assertions.
- The log-assertion test fails on an intentional PII leak and passes once fixed; the least-privilege role test has evidence; `SECURITY.md` names the one-line real-auth path.
- You can say aloud, pointing at the folder: *"I inherited a pipeline that requeued everything forever; I classified its poisons, bounded them, proved exactly-once effects under at-least-once delivery, cut the scope in writing, and every claim in the README points at a runnable assertion."*

## Bottleneck & reflection questions

1. The player's patch feed is derived from a projection, and the projection is derived from history. Where in the Nightshift Courier did you have to stop deriving and start *asserting* — and what does that say about which parts of the pharmacy system can tolerate eventual consistency and which cannot?
2. M1.3 showed prefetch's blast radius on poison. If a patient-facing system runs its workers at max throughput, what is the *patient-visible* version of "poison occupying unacked slots" — and which signal (lag, queue depth, DLQ depth) tells the operator first?
3. A13's PASS line says "duplicates observed and neutralized." Which of your other failure windows — retry exhaustion, DLQ replay, operator republish — ends in the same inbox path, and what does that tell you about where the exactly-once *effect* guarantee actually lives?
4. Your 2h cut in `CutLine.md` left something out. What is the player-visible consequence of that omission, in one sentence — and is that sentence in the document?
5. The log-assertion test failed on a PII leak you introduced on purpose. In the pharmacy system, what PII would the same test catch first — and why does the queue itself count as a leak surface even when the logs are clean?
6. M2.4's table showed ordering costs throughput. If a stakeholder demands both ordered effects *and* competing-consumer throughput for artifacts, what is your one-sentence reply — and does it match the ordering-scope rule from `posts/series-5-interview/02-tradeoffs.md`?

## Handoff

- **Interview line:** "I can run a chaos drill, show lag and poison policy, and defend exactly-once effects without lying about broker delivery."
- **Back to the showcase:** re-run [`../../pharmacy-fulfillment/exercise_03_production.md`](../../pharmacy-fulfillment/exercise_03_production.md) Milestone 10's grilling session with this lab's evidence folder on the table — the questions ("what happens when the relay dies?", "prove the duplicate is safe", "defend the cut") now have answers with runnable assertions behind them.
- **Next rehearsal:** `../glue/X04_walkthrough_script.md` and `../../../posts/series-5-interview/04-showcase-interview-defense.md` — convert the README + evidence into the oral defense; the three-sentence story from `CutLine.md` is the opening.
- **Related capstone:** `../glue/CAPSTONE_XC_observatory_desk.md` (if written) pairs naturally — same evidence discipline, different domain.

## Optional stretch

One harder twist: **the three-way kill matrix.** Run `--random --iterations 10` against *each* kill target (relay, consumer, broker) and then the composition (relay + broker in one window), and produce a single `matrix.md` table: target × iterations × PASS/FAIL × duplicates-observed. The composition column is the one that will carry the interview — it is the failure window no latch test can compose. If you want the second twist: assert a **dawn-window SLA** — 95th percentile lag (build event → PATCH_LIVE) under prefetch saturation stays under a threshold, and make the drill's verdict *include* the lag requirement so "PASS" always means "on time," not merely "not lost."
