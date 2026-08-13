# X03 SSE Toy — Code-Along Elective

## Objective

Build a minimal Spring Boot SSE endpoint that streams one patient's prescription statuses over a real HTTP connection with durable event ids, `Last-Event-ID` replay, and keepalive — and prove the correctness claims (no gaps, no duplicates, ordered) with a scripted client. One patient stream only; RabbitMQ is explicitly not in the picture.

## Time box

~2–2.5h. Core (before `A10` and `A11`). This is the largest of the glue electives and the one that de-risks the biggest assessed gap — SSE has not yet been designed or proven for this candidate.

## Prerequisites

- X01 stack (not strictly required — the toy runs on plain `bootRun` — but the keepalive probe reads nicer against the trio).
- Any `../spring/` elective (S01–S04) — you need a working Spring Boot project and the habit of a real HTTP test client.
- No RabbitMQ electives required. That is deliberate: the toy must teach the SSE contract without the broker, so you can later separate "SSE broke" from "RabbitMQ broke".
- Position: after the R-track basics, before `../advanced/A10_read_models_projections.md` (which replaces the toy's in-memory store with a DB-backed projection) and `../advanced/A11_sse_hard_edges.md` (isolation, slow consumers, authorization).

## Blog & curriculum links

- Primary: `posts/series-4-product-sse/02-sse-correctness.md` — the wire-format table, the crash table, the "never a RabbitMQ consumer" architecture, and the snapshot/replay/`catchUp` shape.
- Secondary: `posts/series-4-product-sse/03-testing-realtime.md` — the `WebClient` + `ServerSentEvent` test client and the three test levels; this elective implements the projection-level slice of that post.
- Coach-assessment gap attacked: "Patient status over SSE has not yet been designed or proven… reconnects, `Last-Event-ID`, event ordering, replay" — exactly the high-risk gap flagged in `artifacts/coach-assessment.md`.

## Background & motivation

SSE fails in four places, and none of them show up in a happy-path demo: the client reconnects after a signal drop and gets a gap; the server restarts and the client's `Last-Event-ID` is meaningless because event ids were random per-send; the replay races the live broadcast and duplicates the boundary event; and — the assessment's own flagged mistake — someone wires each SSE connection as a RabbitMQ competing consumer, so patient 3's browser receives patient 7's events.

This kata exists to burn down the first three on purpose, with the fourth enforced as a hard rule: **no SSE connection may consume a queue.** The toy deliberately ignores authorization, isolation, and multi-patient routing — one patient, one stream, one in-memory store. Those are `A11`'s job. It also ignores RabbitMQ entirely; when the real projection consumer appears in the showcase, the SSE layer's contract (replay by id from a store) does not change one line.

## Learning objectives

- Write a `@GetMapping(produces = [MediaType.TEXT_EVENT_STREAM_VALUE])` endpoint that returns a long-lived `SseEmitter`.
- Assign event ids from a durable, monotonic sequence (per prescription), never a random UUID per send.
- Handle `Last-Event-ID` on connect and replay `sequence_no > lastId` from the store before switching to live.
- Race the snapshot against live events correctly (the `catchUp` step from the correctness post).
- Send keepalive as comment lines (`: keep-alive`), not data events, on a schedule.
- Prove the properties with a scripted `WebClient` client: ordered ids, no gaps, no duplicates, exact replay after a simulated disconnect.

## Warm-up

Read the wire-format table and the crash table in `posts/series-4-product-sse/02-sse-correctness.md` (the first two tables — about 5 minutes). Then name, in your own words, the difference between the four fields `data:`, `id:`, `event:`, and `retry:` and which one makes replay possible. If "id" was not your answer, re-read that section before writing code.

## System specification

**Scope in:** one patient's status stream; in-memory append-only event store with per-prescription monotonic sequence numbers; snapshot on fresh connect; `Last-Event-ID` replay; live broadcast; keepalive; scripted tests.
**Scope out:** RabbitMQ (explicitly out — or at most a clearly optional later experiment); multi-patient isolation; authorization; slow-consumer backpressure; server restart recovery via a durable store; heartbeats tuning beyond one schedule. All of it is named future work for `A10`/`A11`.
**Functional requirements (minimal):**
- `GET /prescriptions/{id}/events` returns a `text/event-stream` that first sends the full ordered history (snapshot), then pushes new events as they are appended.
- Every event carries `id: <sequence>` where the sequence is the store's per-prescription counter.
- A request with `Last-Event-ID: N` receives only events with sequence > N, in order, followed by live events.
- A comment line arrives at least every ~15–20 seconds while the connection is idle.
- A status change endpoint (e.g. `POST /prescriptions/{id}/status`) appends an event and broadcasts it — the toy's only way to produce events.
**Constraints:** single Spring Boot module; the store is an in-memory `ConcurrentHashMap`-backed sequence holder; no RabbitMQ; no browser-level `EventSource` code (the `EventSource` API cannot set custom headers anyway — tests use `WebClient`).

## Step-by-step code-along

1. **Model the store.**
   - **Do:** define a `StatusEvent(patientId, prescriptionId, sequenceNo, status, occurredAt)` data class and an in-memory store that appends events per prescription and hands out monotonic sequence numbers. Return the list in order for a given prescription, and support "events after sequence N".
   - **Run:** write a plain unit test (no Spring) that appends 5 events and asserts `after(2)` returns exactly 3, 4, 5.
   - **Observe:** the sequence arithmetic is the entire correctness contract — test it before any HTTP exists. Note the Kotlin idiom: `data class` gives you structural equality and `copy` for free; Java veterans should resist the urge to hand-write equals/hashCode.
   - **Decision:** sequence numbers per prescription (as the post demands) vs global counter — pick per-prescription now; a global counter would serialize every stream and is the interview trap the correctness post names.

2. **Expose the stream endpoint.**
   - **Do:** add a controller with `@GetMapping(value = ["/prescriptions/{id}/events"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])` returning `SseEmitter`. On connect: send the full snapshot, then register the emitter for live broadcasts. Set `SseEmitter(0L)` so the client owns the connection lifetime.
   - **Run:** `curl -N http://localhost:8080/prescriptions/p1/events` in one terminal; append two events via the status endpoint in another.
   - **Observe:** curl prints the `id:` and `data:` lines of the snapshot immediately, then the live events appear as you append them. The stream stays open — that is the "SSE is a long-lived HTTP response" moment.
   - **Decision:** where the snapshot read and the live registration happen relative to each other — you will get this subtly wrong and the burst test in step 5 exists to catch it.

3. **Set real event ids.**
   - **Do:** send each event with `.id(sequenceNo.toString())` — the id must be the store's sequence number, nothing generated per send.
   - **Run:** repeat step 2 and inspect the raw bytes with `curl -N` or `nc`.
   - **Observe:** ids are 1, 2, 3… — stable across reconnects because they come from the store. This is the exact property the correctness post says most demo implementations miss.
   - **Decision:** none — this is a hard constraint. A random UUID here makes `Last-Event-ID` meaningless and replay impossible; that sentence is worth memorizing verbatim.

4. **Honor `Last-Event-ID`.**
   - **Do:** add `@RequestHeader(value = "Last-Event-ID", required = false) lastEventId: String?`; parse it (defensively — never trust client numbers), and when present, replay `store.after(prescriptionId, lastId)` instead of the full snapshot before switching to live.
   - **Run:** `curl -N -H "Last-Event-ID: 2" http://localhost:8080/prescriptions/p1/events` after appending 5 events.
   - **Observe:** the stream starts at event 3 — no gap, no replay of 2. The boundary event is *not* replayed; that is the "at-most-once per ID" replay the post describes.
   - **Decision:** what to do with a non-numeric or negative `Last-Event-ID` — a `400` is defensible, and so is treating garbage as "no last id". Pick one and log the choice; it will be asked about in the walkthrough.

5. **Race the snapshot against live events.**
   - **Do:** after replaying the snapshot, record the latest applied sequence and drop live broadcasts at or below it (the `catchUp` step from the correctness post). Broadcasts append to the stream only when `sequenceNo > lastApplied`.
   - **Run:** write the burst test from the testing post — open a client whose subscription fires broadcasts immediately (in the test, `doOnSubscribe`), and assert the client still sees 1, 2, 3 exactly once.
   - **Observe:** without `catchUp`, that test intermittently duplicates the boundary event; with it, the test is deterministic. If it passes on the first try, run it 20 times anyway — this is the race the design exists for.
   - **Decision:** keep the drop-lower-than rule inside the SSE layer (as this toy does) vs requiring the store to hand out an atomic snapshot+position — the trade-off fork below gives you the fuller version.

6. **Keepalive, the boring kind.**
   - **Do:** a `@Scheduled` task every ~15s sends a comment line (`: keep-alive`) to open emitters via `emitter.send(SseEmitter.event().comment("keep-alive"))`.
   - **Run:** `curl -N` the stream and leave it idle; observe a comment line arriving on schedule. Check that the sequence ids do not advance.
   - **Observe:** comments are ignored by clients but keep proxies from killing the idle socket. It must never be a `data:` event — a real event would pollute the client's sequence and could be mistaken for a status change.
   - **Decision:** interval constant and whether the comment includes a timestamp — timestamps in comments are free debug value; keep the interval overridable in config for the tests.

7. **Prove it with a client, not a browser.**
   - **Do:** add a `WebClient`-based test (real random port, real HTTP) with two cases: (a) fresh connect receives the full ordered history; (b) connect with `Last-Event-ID: N` receives exactly the tail `N+1..M`, asserted with `containsExactly`.
   - **Run:** `./gradlew test` (or the project's test task) — and then make the reconnect *during a burst* test from step 5 part of the suite.
   - **Observe:** the assertions pass deterministically. The testing post's one-liner summary applies: the demo shows the stream; the tests show the stream is as trustworthy as the store.
   - **Decision:** test via `RANDOM_PORT` + `WebClient` vs MockMvc — MockMvc cannot hold real sockets open, and a reconnect test cannot fake a dropped TCP connection. Real port is the only honest option; this matches the testing post's integration-level guidance.

## Try this

Two deliberate experiments:

1. **The gap test:** open a client, let it receive 3 events, disconnect (cancel the client — not the server). Append events 4 and 5 while nobody is listening. Reconnect with `Last-Event-ID: 3`. The client must receive exactly 4, 5. If this passes, replay is proven to come from the store, not from any buffer the broadcaster kept — the assertion that distinguishes a correct design from a demo.
2. **The boundary race:** reconnect *while* appending events in a tight loop (or use the `doOnSubscribe` burst test) and observe the id sequence for duplicates. Without the `catchUp` step this produces a rare duplicate of the boundary event — run it enough times to see it fire, then fix, then watch it become deterministic.

## Trade-off fork

**Option A — in-memory store (this toy):** the sequence store and the emitter registry are process-local. Simple, fast, and correct for one patient on one instance; loses everything on restart (clients reconnect, and there is nothing to replay — the snapshot is empty).

**Option B — DB-backed store (the `A10` shape):** events live in a `status_projection`-style table; replay reads committed rows and restart is a non-event. Durable and truthful, at the cost of a schema, a repository, and a real database in the tests.

Pick one and write 3–5 lines justifying it for a 2-hour challenge slice, naming the sacrificed property. The curriculum's hard constraint: the *final* showcase system must be Option B (the correctness post's invariant — "SSE and GET are two readers of one source of truth" — requires a durable store). The toy exists so that when `A10` swaps the store, every SSE contract test from this kata runs unchanged — that swap is the seam the toy buys you. If you pick A, say what B's durability would have cost the demo; if you pick B, say what the toy's simplicity bought that B's schema taxes.

## Hints

- **Hint 1:** if the burst test duplicates the boundary event, you are broadcasting events that were already part of the snapshot. Record the last sequence *sent during the snapshot* and filter live events at or below it — the post's `catchUp` is one line, and it is the line you are missing.
- **Hint 2:** if `Last-Event-ID` replay returns the boundary event itself, your `after(n)` query is using `>=` where the contract needs `>`. And if the reconnect test flakes, make sure the emitter is registered *before* the snapshot read — a live event can land between the two, which is precisely the window `catchUp` closes.

## Checkpoint / success criteria

You may leave when:

- `curl -N` shows a stream that snapshots history, then pushes appends live, with monotonic `id:` lines and a keepalive comment every ~15s.
- A scripted client with `Last-Event-ID` receives exactly the tail, asserted with `containsExactly` — no gaps, no duplicates, no boundary replay.
- The burst test (join mid-broadcast) passes deterministically, run 20 times.
- You can state the rule that motivated the whole exercise in one sentence: *SSE connections read from a store; they never consume from RabbitMQ.*

## Bottleneck & reflection questions

1. The correctness post says the stream can never be ahead of the GET because broadcasters only send committed store rows. Where does the in-memory toy violate that claim, and why does it not matter for the toy's scope?
2. The testing post proves server restart by composition (reconnect test + fresh-connection test). What would the equivalent composition look like for a *store* that dies — and what does that say about the `A10` store's requirements?
3. Failure handling: a client sends `Last-Event-ID: 9999` — a number beyond the store. What should the server do, and what does your answer say about whether you trust client input?
4. Patient experience: what does the patient *feel* differently between a polling baseline and this stream — and what does the correctness post's baseline-vs-enhancement table say must still be true if SSE is deleted?
5. Simplicity: this toy's store dies on restart. Which single change makes restart survivable, and why is that change too expensive for the toy but mandatory for the showcase?

## Handoff

- Next: `../advanced/A10_read_models_projections.md` — swap the in-memory store for a DB-backed projection and re-run every test in this kata unchanged; then `../advanced/A11_sse_hard_edges.md` — add the second patient, authorization, and slow-consumer behavior that this toy deliberately ignored.
- Related showcase exercise: `showcase_projects/pharmacy-fulfillment/exercise_03_production.md` — its SSE milestones (ordered events, `Last-Event-ID` replay, no cross-patient data) are exactly the properties this kata's tests already assert; you will reuse the test client verbatim.
- Interview line to be able to say aloud: *"SSE is a delivery optimization over a replayable store. The id is the store's sequence number, `Last-Event-ID` is a query cut-off, and the correctness is proven by a scripted client — not a browser tab. And no SSE connection ever touches a queue: that would make patient 3's browser a competing consumer of patient 7's updates."*

## Optional stretch

Add a `: keep-alive` schedule that is *configurable per connection* (an inbound header or query param) and prove with a test that a client requesting a 2-second keepalive receives comment lines at ~2s. That is a small preview of `A11`'s per-connection concern, and it gives the walkthrough a live, tunable artifact to show.
