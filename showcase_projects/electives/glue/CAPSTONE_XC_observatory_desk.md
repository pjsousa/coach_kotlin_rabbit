# XC Observatory Desk — Track Capstone (Code-Along)

## Objective

Build a tiny mission-control desk for telescope observation runs and then rehearse the interview story it makes possible. Operators submit an "observation run" (target, exposure seconds, tick interval); the run hops through a queue while a single correlation id follows it through every async boundary in structured JSON logs; an SSE "mission clock" streams run status ticks with monotonic event ids, `Last-Event-ID` replay, and keepalive; and you finish by writing and timing a 10-minute oral walkthrough script you could actually deliver in an interview. One new project, four milestones, one deliverable you can speak aloud.

## Time box

~5–7h total: M1 ~1.5h, M2 ~1.5h, M3 ~2–2.5h, M4 ~1h (timed double-run included — the walkthrough is a required milestone, not a bonus). Track E capstone; run it after X01–X04 are complete (or with a written waiver per the skill checklist below). If the clock squeezes, the cut order is defined: the M3 stress assertions and the walkthrough's appendix content go before the timed double-run.

## Prerequisites

**Track electives that should be complete** (waiver allowed per-row with notes, but the checklist below is the gate):

| ID | Title |
|---|---|
| X01 | [Docker Compose trio](X01_docker_compose_trio.md) — healthchecks, lose-a-service evidence |
| X02 | [Structured logging](X02_structured_logging.md) — correlation id via MDC, no-PII greps |
| X03 | [SSE Toy](X03_sse_toy.md) — ids, replay, keepalive, scripted client |
| X04 | [Walkthrough script](X04_walkthrough_script.md) — 10-minute delivery discipline |

X03's toy is explicitly the *predecessor* of M3 here: the toy proved the SSE contract against an in-memory store; this capstone re-proves it against a Postgres-backed store, which is the difference between "demo-grade replay" and "restart-survivable replay".

**Tools:** JDK 17+, Docker Desktop/OrbStack with `docker compose` ≥ 2.x, a browser-independent test client habit (WebClient), and a terminal you can run two sessions in.

**Position vs showcase:** before [`../../pharmacy-fulfillment/exercise_03_production.md`](../../pharmacy-fulfillment/exercise_03_production.md)'s SSE milestone — this is the rehearsal run for it. Pairs with `../glue/X03_sse_toy.md` and `../advanced/A11_sse_hard_edges.md`: the desk's per-run isolation is A11's multi-patient isolation in miniature.

## Blog & curriculum links

- Primary: `../../../posts/series-4-product-sse/02-sse-correctness.md` — the wire-format and crash tables, the "SSE is a reader of a store, never a queue consumer" architecture, snapshot/replay/`catchUp`.
- Secondary: `../../../posts/series-4-product-sse/03-testing-realtime.md` — the `WebClient` + `ServerSentEvent` scripted-client shape and the three test levels you will implement at the projection level.
- Tertiary: `../../../posts/series-3-rabbitmq/06-operational-testing.md` — the version-pin rule and "the test doubles as documentation of the operational interface"; your one-command grep is that interface.
- Interview: `../../../posts/series-5-interview/01-take-home-walkthrough.md` — the walkthrough structure M4 compresses into 10 minutes.
- Coach-assessment gap attacked: the SSE gap ("reconnects, Last-Event-ID, ordering, replay" unproven) plus the track-F delivery gap (timed walkthrough, system design, interview defense) — both are named targets in `../../../artifacts/coach-assessment.md`.

## Background & motivation

You have already built the pieces in isolation: a trio stack that survives losing a service (X01), a logging spine that makes a journey greppable (X02), and an SSE contract proven against an in-memory toy (X03). This capstone exists because interviews are not scored on pieces. The interviewer asks "show me something you built" and a queue-consumer toy plus an SSE toy plus a compose file reads as three demos, not one system. The observatory desk is deliberately *one* system with three seams you can narrate in a single arc: submit → queue → clock.

It is also deliberately *not* pharmacy fulfillment. The operator at the desk watches the same thing a patient watches: a status line that must be truthful, ordered, and recoverable after a dropped connection. When a telescope run's stream resyncs from the database after the app restarts, you are practicing the exact claim Ex3 will make about patient status. And the "never a Rabbit consumer per SSE connection" rule — the single most-asked correctness question in the SSE space — gets its concrete defense here: one queue consumer (the run worker) applies facts to a projection; every SSE connection reads that projection. No browser is ever a competing consumer.

## Skill checklist (mandatory)

Each row is a prior glue elective; the capstone forces it with a concrete behavior. Mark `pass` or `skip + waiver` (one sentence each) in the capstone README before M4.

| Elective | Concrete capstone behavior/test that forces it | pass / skip + waiver |
|---|---|---|
| X01 | Docker Compose trio (app + Postgres + Rabbit) with real healthchecks; fresh `down -v` bring-up reaches all healthy; losing any one service → observable behavior, stack returns to healthy unaided; startup log shows DB call + publish/consume without edits | ☐ |
| X02 | One observation-run journey (REST submit → DB → publish → consume → ack) visible in structured JSON logs under a single `correlation_id` via MDC; id survives failure lines; no-PII grep returns nothing; greppable in one command | ☐ |
| X03 | SSE "mission clock" stream: monotonic event ids, `Last-Event-ID` replay catching exactly the tail, keepalive comments; scripted client asserts `containsExactly` — no gaps/duplicates; store-backed SSE — never one Rabbit consumer per connection; per-run isolation (one stream per run) | ☐ |
| X04 | 10-minute oral walkthrough script of the observatory desk as final deliverable: four timed segments (2 min journey / 3 min architecture / 3 min failure mode / 2 min tradeoffs), cue cards, five prepared follow-up answers, full run lands within 10:00–10:30 twice in a row | ☐ |

Easy-to-skip risks the capstone forces on purpose: X04 is writing-only, so M4 is gated on the *timed double-run*; X01 is "already done" inertia, so M1's checkpoint is the lose-a-service evidence, not the happy bring-up; X02's evidence is forced as one-command greps, not eyeballing; X03's reconnect mid-run is forced as a test assertion, not a demo claim.

## Learning objectives

- Stand up a three-service compose stack and produce recorded evidence that losing any one service is observable and recoverable without edits.
- Thread one correlation id through REST → DB → RabbitMQ publish → consumer → ack using MDC and a message header, and prove the journey with a single grep.
- Prove a store-backed SSE stream's correctness with a scripted client: monotonic ids, exact `Last-Event-ID` tail replay, no gaps/duplicates, per-run isolation.
- Defend the topology in writing: RabbitMQ consumers and SSE connections are disjoint — the store is the only shared truth.
- Write, time, and rehearse a 10-minute oral walkthrough with hard segment timings, cue cards, and prepared follow-ups — deliverable, twice, on the clock.

## Warm-up

Read the wire-format table and the crash table in `../../../posts/series-4-product-sse/02-sse-correctness.md` (~5 minutes). Then answer in writing, one sentence each: (a) which SSE field makes replay possible and why a random UUID per send breaks it; (b) the version-pin rule from `../../../posts/series-3-rabbitmq/06-operational-testing.md` — what does your compose file and your future Testcontainers must agree on. Then run one probe: `docker compose version` and `docker run --rm rabbitmq:3.13-management rabbitmq-diagnostics -q ping`. If the ping fails, Docker networking is your first problem, not this capstone. Time-box: 8 minutes total.

## Project bootstrap

**Exact directory (create it now):**

```
showcase_projects/electives/projects/observatory-desk/
```

This is candidate-owned code — nothing is pre-built for you. A short `showcase_projects/electives/projects/README.md` is the only thing a writer should ever place here.

1. **Create the Gradle skeleton.**
   - **Do:** `mkdir -p showcase_projects/electives/projects/observatory-desk` and scaffold a Kotlin Spring Boot project — either `curl https://start.spring.io/starter.zip -d dependencies=web,amqp,jdbc,postgresql,actuator,validation -d language=kotlin -d type=gradle-project -d groupId=dev.observatory -d artifactId=observatory-desk -d javaVersion=17 -d bootVersion=3.3.x -o observatory-desk.zip`, unzipped in place, or hand-write `build.gradle.kts` from any S-track project you already have (Kotlin JVM + Spring Boot 3.x plugins, the six starters, plus `logstash-logback-encoder`).
   - **Run:** `./gradlew bootJar` — must compile an empty app.
   - **Observe:** you now own a project; the next ~6 hours' evidence lives under this directory, and the walkthrough's "starting point" line writes itself.

2. **Write the compose trio.**
   - **Do:** add `docker-compose.yml` with `postgres` (`postgres:16-alpine`), `rabbitmq` (`rabbitmq:3.13-management`), and `app` (builds the bootJar), each with a real healthcheck, the app gated on the dependencies, and configuration threaded via `environment:` from an `.env` file. Shape:
     ```yaml
     services:
       postgres:
         image: postgres:16-alpine
         healthcheck:
           test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
           # ...
       rabbitmq:
         image: rabbitmq:3.13-management
         healthcheck:
           test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
       app:
         build: .
         depends_on:
           postgres: { condition: service_healthy }
           rabbitmq: { condition: service_healthy }
         environment:
           SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB}
           # ...
     ```
   - **Run:** `docker compose down -v && docker compose up -d --build` then `docker compose ps`.
   - **Observe:** the `STATUS` column goes `starting` → `healthy`; write down the image tags — the version-pin rule says your Testcontainers (later) must reuse them.

3. **Fill the README skeleton as you go.** Create `README.md` with these sections and one prompt each — you will fill them after every milestone:
   - **Overview** — what this is, who the operator is (journey sentence M4 will reuse).
   - **Quickstart** — the exact commands from step 2.
   - **Evidence** — pointers into `notes/` (you will create one markdown note per milestone: `notes/M1-*.md`, `notes/M2-*.md`, `notes/M3-*.md`).
   - **Skill checklist** — the table above with your pass/skip+waiver marks.
   - **Walkthrough** — link to `walkthrough/walkthrough-script.md` (M4).

## System specification

**Product fantasy / actors.** A tiny mission-control desk. The **operator** submits an observation run (target name, exposure seconds, tick interval) and watches the **mission clock** — an SSE stream of run ticks (SCHEDULED → SLEWING → EXPOSING → GUIDING → COMPLETE/FAILED). The **telescope** is simulated: a queue consumer "drives" the run, emitting one status event per tick. The operator is your patient; the mission clock is the patient's status page; the run worker is the fulfillment worker.

**Scope in:** one Spring Boot app; Postgres (the run + the status-event projection); RabbitMQ (the run-scheduled queue); REST submit; structured JSON logs with correlation id; the SSE mission clock; a scripted test client; the walkthrough script.

**Scope out:** real telescope drivers, UI/browser `EventSource` (tests use WebClient — the API cannot set headers anyway), cloud, auth, multi-operator authorization, second app instance, metrics dashboards, any product code for the pharmacy showcase.

**Functional requirements (minimal):**
- `POST /runs` (target, exposureSeconds, tickIntervalSeconds) persists the run, publishes a run-scheduled message, and returns the run id.
- A consumer receives the message, executes the run as a tick loop, and appends one row per tick to a `run_status_event` projection, updating run status. It acknowledges after the work (log the ack).
- Every log line for a journey carries the same `correlation_id` (inbound `X-Correlation-Id` or generated), producer and consumer sides, including failure lines.
- `GET /runs/{runId}/stream` returns `text/event-stream`: full history first, then live ticks, every event with `id: <sequenceNo>` from the store, `Last-Event-ID` replays the exact tail, keepalive comments every ~15s, and events from one run never appear on another run's stream.
- `GET /runs/{runId}` returns current status from the DB (the correctness baseline — the stream must never be ahead of it).

**Non-functional / evidence requirements:**
- Fresh `docker compose down -v && up -d --build` reaches all three `healthy`; startup log shows a DB call and a publish/consume without edits.
- One-command trace: `docker compose logs app | grep <correlation_id>` shows the whole journey as an interleaving-free timeline.
- No-PII greps return nothing (operator/target probes, M2).
- Reconnect evidence: a scripted client disconnects mid-run and replays exactly the missing tail — test output saved to `notes/`.
- Walkthrough timing: full run lands within 10:00–10:30 twice in a row.

**Constraints:** local only; one deployable app (worker, producer, projection, SSE all inside it); MDC-only correlation (no OTel); SSE is store-backed — **never one Rabbit competing consumer per SSE connection**; no browser code.

## Milestones (code-along)

### M1 — Trio with teeth: healthchecks and a service you can lose (X01)

- **Do:** complete the compose file from bootstrap (healthchecks, `depends_on` gating, env wiring, `.env`). Add a boot `ApplicationRunner` that logs a `SELECT 1` from Postgres and publishes-and-consumes one scratch message on Rabbit — the "without edits" proof from X01.
- **Run:** `docker compose down -v && docker compose up -d --build`, then `docker compose ps`, then `docker compose logs app`.
- **Observe:** the app's first log lines happen *after* both dependencies report healthy; the boot probe's DB line and publish/consume lines appear with no manual step. That ordering is the healthcheck contract, not luck.
- **Do:** the lose-a-service experiment. `docker compose stop postgres`, watch `docker compose ps` and the app log for ~20 seconds, then `docker compose start postgres`. Repeat for `rabbitmq`. Record what you observed — not what you hoped: did the app reconnect or crash? did publish fail fast, block, or retry?
- **Run:** verify the stack returns to `healthy` unaided both times, and repeat the boot-probe once more.
- **Observe:** you now own the evidence the walkthrough's failure segment will point at. Save `notes/M1-lose-service.md` with `docker compose ps` output and the log excerpts from each kill.
- **Mini trade-off (write 3–5 lines):** `depends_on: condition: service_healthy` on first boot vs. no gating and app-side retry for everything. Which mechanism covers *startup order*, which covers *runtime failure*, and what does each give up? (This is the same fork X01 made you take; the capstone asks you to hold both answers together.)

### M2 — One run, one id: the journey becomes a grep (X02)

- **Do:** model the domain — `ObservationRun(runId, target, exposureSeconds, tickIntervalSeconds, status)` and `RunStatusEvent(runId, sequenceNo, phase, tickNo, occurredAt)` — and their two tables (`schema.sql` or a migration; `sequenceNo` per run, monotonic). Kotlin idiom for the Java veteran: `data class` + `enum class Phase`; illegal transitions are an enum switch away.
- **Do:** the correlation seam. A `OncePerRequestFilter` accepts or generates `X-Correlation-Id`, puts it in the MDC, echoes it back. The publisher copies the MDC value onto the message as a header. The consumer's *first* statement restores it via `MDC.putCloseable(...)` with a `finally` close. Shape:
  ```kotlin
  @RabbitListener(queues = ["obs.runs"])
  fun onRunScheduled(msg: RunScheduled) {
      MDC.putCloseable("correlation_id", msg.correlationId).use {
          // log received -> apply ticks -> append projection rows -> update run -> log acked
      }
  }
  ```
- **Do:** JSON logging (`logstash-logback-encoder`), terse messages, data in fields. Log exactly four moments: submit accepted, publish confirmed, consumer received, ack sent — plus any failure lines.
- **Run:** submit one run. Then:
  ```bash
  docker compose logs app | grep <correlation_id>
  ```
- **Observe:** 4–8 lines, one JSON object each, one id, no interleaving — even with a second run fired concurrently from a second terminal with its own header.
- **Do:** the failure-line proof and the no-PII proof. Send a run whose message body will fail to deserialize on the consumer (corrupt the target field); confirm the *error* line still carries the id. Submit a run with an impossible operator/target name ("ZELDA-OPERATOR", "M31-ANDROMEDA") and grep for both: zero matches required.
- **Observe:** the id is the only handle in the log. Save the grep output and the zero-match greps in `notes/M2-grep-journey.md`.
- **Mini trade-off (write 3–5 lines):** MDC + header threading (implicit, zero call-site churn, breaks at the first coroutine/thread-pool boundary) vs. explicit context threading (testable, but every call site changes). This is the exact fork X02 staged; the capstone asks you to re-justify it now that a *second* boundary (consumer → tick loop) is in play.

### M3 — The mission clock: SSE that survives the truth (X03)

- **Do:** the store-backed stream. `GET /runs/{runId}/stream` (produces `TEXT_EVENT_STREAM_VALUE`, returns `SseEmitter`). On connect: read the full ordered event history from `run_status_event`, send it with `id: <sequenceNo>`, record the last applied sequence, then register for live broadcast dropping events at or below it (`catchUp` — the boundary race from the correctness post). On a `Last-Event-ID` header: replay `sequenceNo > lastId` from the store, then live. Wire snippet:
  ```
  id: 1
  event: tick
  data: {"runId":7,"sequenceNo":1,"phase":"SLEWING","tickNo":0}
  
  : keep-alive
  
  id: 2
  event: tick
  data: {"runId":7,"sequenceNo":2,"phase":"EXPOSING","tickNo":1}
  ```
- **Do:** keepalive as comments on a ~15s schedule — never `data:` events.
- **Run:** `curl -N http://localhost:8080/runs/{id}/stream` in one terminal; submit a run in the other; watch snapshot then live ticks, then leave it idle for the keepalive.
- **Observe:** ids are stable, monotonic, store-derived. The stream and `GET /runs/{id}` agree — the stream is a *reader* of the same projection the GET reads.
- **Do:** the scripted client (WebClient, real random port — MockMvc cannot hold sockets). Four assertions: (a) fresh connect sees full ordered history; (b) `Last-Event-ID: N` receives exactly the tail, asserted with `containsExactly`; (c) reconnect *during a tick burst* — no gaps, no duplicates (run it 20×); (d) per-run isolation: two runs, two streams, and a client that flips between them never sees run B's ids on run A's stream.
- **Run:** `./gradlew test`; save output in `notes/M3-reconnect-evidence.md`.
- **Observe:** the gap experiment's semantics — replay comes from Postgres, not from any broadcaster buffer. Restart the whole app mid-run and reconnect: the replay still works, because the store outlived the process. That sentence is the upgrade from the X03 toy to this capstone, and it is the one to memorize.
- **Topology defense (mandatory, written):** in `notes/M3-topology-defense.md`, write 2–3 sentences defending: *the run worker is the only Rabbit consumer; every SSE connection reads the projection; a browser is never a competing consumer.* If you cannot write it in two sentences, you have not internalized it — reread the correctness post's architecture section.
- **Mini trade-off (write 3–5 lines):** where `catchUp` lives — in the SSE layer as a filter (toy style) vs. in the store as an atomic snapshot+position read. The lab's answer is "SSE layer is fine"; say what the atomic version would buy and what it would cost.

### M4 — The 10-minute walkthrough, timed twice (X04)

- **Do:** write `walkthrough/walkthrough-script.md` with four timed segments: **2 min journey** (open on the operator, before any component name), **3 min architecture** (store → stream → client; the one sentence "the stream reads the store, the store is the truth"), **3 min failure mode** (claim → mechanism → evidence, pointing at a real artifact), **2 min tradeoffs** (four-part statement: assumption, alternative, choice, sacrifice).
- **Do:** choose each segment's artifact from your `notes/` — the M1 `ps` output, an M2 grep, the M3 test output. Every artifact must be producible by one command, cold terminal.
- **Do:** cue cards (4 cards, ≤5 bullets each, ≤8 words per bullet) and five written follow-up answers — include "why didn't you publish ticks to a queue?" and "what did you sacrifice by skipping the in-memory bus?" — in claim → mechanism → honest-limitation shape.
- **Run:** time the full walkthrough twice, on consecutive days if possible. The gate: **both runs land within 10:00–10:30**. Log the timings in the script's header.
- **Observe:** the second run is faster and shorter — you are compressing, not adding. Anything still overrunning gets cut from the middle (detail), never from the journey or the sacrifice.
- **Mini trade-off (write 3–5 lines):** which artifact anchors the failure segment — the M1 lose-a-service evidence (safe, modest) vs. the M3 reconnect test output (showier, more attack surface). Say what the loser would have added and why you cut it.

## Try this

1. **Reconnect mid-run (the M3 headline experiment).** Open a client, let it receive a few ticks, cancel it (not the server). Submit a second run so ticks keep flowing while nobody listens. Reconnect with `Last-Event-ID` set to the last id you saw. The stream must deliver exactly the missing tail, `containsExactly`, then continue live. If this passes, replay is proven to come from Postgres — the assertion that separates a correct design from a demo.
2. **One id, one command, two journeys.** Fire two runs concurrently with different `X-Correlation-Id` headers. `grep` each id — each timeline is clean and interleaving-free. Then corrupt one payload so it fails: the error line must carry its id too. A support ticket maps to a grep; now prove it twice.
3. **Break the healthcheck, watch compose.** While an SSE client is connected, `docker compose stop postgres`. Watch `docker compose ps` move to `unhealthy` and note what the stream does (freezes? errors?). Restart Postgres and watch the stack return to `healthy` unaided. Reconnect the stream — the store was never gone, so replay resumes. This is the X01 kill drilled *through* the M3 stream, and it is your strongest "I understand failure end to end" evidence.

## Trade-off forks

1. **Log JSON vs. log key=value.** JSON (logstash encoder): greppable, parseable, one line per event, and it is what the walkthrough's "support ticket becomes a grep" story needs — but it is noisy to eyeball and tempts field sprawl. key=value: human-readable in a terminal, but every parser story breaks and your "one-command evidence" gets fuzzier. Pick one and name what you traded; the interviewer probe is "why not the other one?" and the honest answer is usually *evidence shape, not aesthetics*.
2. **SSE backed by a DB projection vs. an in-memory bus — for this lab.** The in-memory bus (the X03 toy shape) is less code, zero schema, and perfectly fine for a demo that never restarts. The DB projection costs a table and a write path, and buys the property the interview actually tests: restart-survivable replay and the "stream and GET read the same truth" claim. For the capstone, the projection is the required choice — but the fork is mandatory because you must be able to say *what the bus would have cost the demo* and *what the projection taxes* (write amplification on the tick path, latency on tick→stream). Do not pretend either side is free.
3. **What belongs in the 10-minute demo vs. the appendix.** The demo is 10 minutes; the desk's surface (submit, queue, logs, stream, restart, failure) is much larger. Decide deliberately: the journey and the failure evidence are always in; the compose-level detail and the concurrent-runs proof live in the appendix (your `notes/` + README), nameable in one sentence each when the interviewer asks "what else did you verify?". The rule from the walkthrough post: any claim in the demo gets an artifact; everything else is appendix and must be introduced as "I verified X, happy to go deeper".

## Hints

- **Hint 1 (M2):** if consumer logs show a missing id, the header name and the MDC key disagree, or the restore happened after the first log call. Put the restore in the *first* statement of the listener. And prefer `MDC.putCloseable` — a thrown exception leaks the MDC otherwise.
- **Hint 2 (M1):** if `pg_isready` reports ready against the wrong role, you forgot `-U`. If the app healthcheck says `unhealthy` while logs show a healthy app, it is `start_period` math or a missing `curl` in the image — verify the check command with `docker compose exec app <command>` before debugging Compose.
- **Hint 3 (M3):** if `Last-Event-ID` replay duplicates the boundary event, your query is `>=` where the contract needs `>`, or the emitter registered *after* the snapshot read — a live tick landed between the two. The `catchUp` drop-lower-than rule is one line and it is the line you are missing. If the reconnect test flakes, run it 20 times before calling it fixed.
- **Hint 4 (M4):** if a segment keeps overrunning, delete the sentence that is *context* rather than *claim*. The journey segment survives on one verb — what the operator *experiences*. And time the commands cold: the demo dies the moment you fumble the grep.

## Checkpoint / success criteria

You may leave when:

- Fresh `docker compose down -v` bring-up reaches all three `healthy`; the startup log shows the DB call and publish/consume without edits; killing either dependency is observable and the stack returns to `healthy` unaided — evidence in `notes/M1-lose-service.md`.
- One submit produces a JSON-logged journey under a single `correlation_id` greppable in one command; a corrupted-payload failure line carries the id; `grep -i <probe-name>` returns nothing — evidence in `notes/M2-grep-journey.md`.
- The mission clock streams monotonic store-derived ids, replays exactly the tail on `Last-Event-ID`, sends keepalives, passes the reconnect-mid-run and 20× burst tests with `containsExactly`, and the isolation test never cross-talks — evidence in `notes/M3-reconnect-evidence.md`, with `notes/M3-topology-defense.md` written.
- The walkthrough script exists with four timed segments, cue cards, and five follow-up answers, and **both timed runs land within 10:00–10:30** — timings recorded in the script header.
- The README's skill checklist shows all four rows `pass` (or `skip + waiver` with one honest sentence each).
- You can say aloud, without notes: *"One queue consumer applies facts to a projection; every SSE connection reads that projection — no browser is ever a competing consumer."*

## Bottleneck & reflection questions

1. **Patient experience by analogy:** the operator's mission clock and a patient's status page are the same artifact. What does the operator *feel* differently between polling `GET /runs/{id}` and watching the clock — and what must remain true about the GET if the SSE layer were deleted tomorrow?
2. **Failure handling:** the worker dies mid-run. The clock freezes mid-tick. What does the operator see, what does a support-grep find (hint: the last acked line), and what is missing before you can call that "resumable" rather than "observable"?
3. **System design:** the tick path writes one row per tick to Postgres, then the stream reads it back — a write-then-read round trip that a broadcast bus would skip. Where does that design break first at scale, and why is it still the right trade for a 10-worker lab?
4. **Simplicity:** the X03 toy's in-memory store was ~100 lines and proved the contract. What single change in M3 made replay survive a process restart, and why was that change mandatory here but correctly skipped in the toy?
5. **Bottleneck:** which of the four walkthrough segments is your personal bottleneck, and what does that say about the gap the coach assessment flagged? If the failure segment is the hardest, the artifact work was probably skipped; if the tradeoffs are, the fork writing was probably skipped.

## Handoff

- **Next track/showcase:** [`../../pharmacy-fulfillment/exercise_03_production.md`](../../pharmacy-fulfillment/exercise_03_production.md) — its SSE milestones (ordered events, `Last-Event-ID` replay, no cross-patient data) are exactly what M3 already asserts; reuse the WebClient test client verbatim, and the walkthrough script becomes the opening act of Ex3's interview-polish phase. For the harder edges (slow consumers, authorization, real multi-patient isolation), continue to `../advanced/A11_sse_hard_edges.md`; for the operational seams this capstone only names, `../advanced/A12_observability_slice.md` and `../advanced/A13_chaos_drill_script.md`.
- **Interview one-liner (say it aloud):** *"I can trace one request across async boundaries and demo reconnect-safe patient-style status without coupling SSE to competing consumers."*

## Optional stretch

Three, pick one:

1. **Per-connection keepalive:** let a client request a keepalive interval (query param or header) and prove with a test that a client asking for 2s receives comments at ~2s. A small preview of A11's per-connection concern, and a live tunable artifact for the walkthrough.
2. **Guide-star loss:** a simulated failure mid-run — the telescope "loses guide lock", the run transitions to FAILED, the clock shows it, and the failure log line carries the correlation id. That gives the M4 failure segment a *product* failure (not just an infra kill) and rehearses the corrupted-payload discipline from M2 on a realistic path.
3. **Isolation under reconnect:** the two-run isolation test, but with both streams reconnecting mid-burst while both runs tick. If it ever cross-talks, your replay query is missing the run filter — and that is precisely the bug class A11 exists to harden.
