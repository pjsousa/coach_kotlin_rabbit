# A11 SSE Hard Edges — Code-Along Elective

## Objective

Take the X03 SSE toy and harden exactly the four edges the blog post names: isolation (a broadcast that physically cannot reach another patient), the slow consumer (a connection that reads slower than you write), heartbeat (idle proxies killing your stream), and authorization (ownership enforced before replay *and* inside the replay query). Each edge gets a deliberate failure experiment and an evidence artifact. You leave with the four properties proven on real HTTP, and the "never a RabbitMQ consumer" claim defended with a topology you can draw.

## Time box

~2.5–3h. Core: steps 1–5. Optional: step 6 (slow-consumer backpressure) and the multi-patient interleaving test in "Try this".

## Prerequisites

- `../glue/X03_sse_toy.md` — the SSE toy with one patient stream and event IDs. This kata hardens it; it does not rebuild it.
- `../postgres/P05_status_history_append.md` or `A10_read_models_projections.md` — you need the durable per-prescription sequence that the `id:` field must come from. If you did A10, use its projection table directly.
- Position: **during Exercise 3** (prepares `showcase_projects/pharmacy-fulfillment/exercise_03_production.md` Milestones 7–8).

## Blog & curriculum links

- Primary: `posts/series-4-product-sse/02-sse-correctness.md` — the broadcaster keyed by `patientId`, the replay/`catchUp` protocol, comment-line heartbeats, and the EventSource-header caveat.
- Secondary: `posts/series-4-product-sse/03-testing-realtime.md` (the WebClient SSE client and the reconnect-as-assertion pattern) and `posts/series-4-product-sse/05-showcase-patient-notification.md` (the end-to-end notification story this hardens).
- Coach-assessment gap: SSE/realtime — "reconnects, `Last-Event-ID`, event ordering, authorization/filtering, replay, and avoiding RabbitMQ competing-consumer mistakes."

## Background & motivation

X03 proved one patient can get a stream with event IDs. That proof is the demo. The edges are where realtime systems actually fail, and they are all versions of the same sentence from the blog post: *the SSE layer is a delivery optimization, and the correctness is in the application, not the wire.*

- **Isolation is structural, not filtered.** You already saw the competing-consumer mistake named twice (series 3 and the blog): each SSE connection consuming a RabbitMQ queue delivers patient 7's event to patient 3. The hard edge here is that isolation must survive *you* — the broadcaster map keyed by `patientId` means the send path cannot even address the wrong connection.
- **The slow consumer is the hidden failure.** Every SSE demo uses a fast client. A real phone on 3G, or a proxy that buffers, reads slower than your worker writes. The blog post's `runCatching { emitter.send(...) }` is the polite version; the honest version is a buffer that fills and a decision about what happens at the limit.
- **Replay is a query, and a query needs a guard.** `Last-Event-ID` is just a number the client sends. The number is meaningless unless the caller owns the prescription — before the replay query runs, and again inside it.
- **Heartbeat is a wire-format decision.** A comment line (`: keep-alive`) keeps proxies from timing you out without advancing sequence numbers. None of the tutorial code does it; every production SSE does.

What this kata deliberately ignores: the RabbitMQ feed into the projection (A10 / `../rabbit/R07_outbox_relay_mini.md` own that), real authentication (A15 owns the identity story), and the GET-vs-SSE comparison (already proven in the blog's table). Here the projection is a table you insert into and the SSE layer is the whole app.

## Learning objectives

- Key a broadcaster by `patientId` so misrouting is structurally impossible, and prove it with two concurrent connections and interleaved broadcasts.
- Make replay a guarded query: ownership check before the query and inside the query, `Last-Event-ID` parsed as a server-side cut-off.
- Observe and handle a slow consumer: bounded buffer, dropped-write or error semantics, and cleanup on completion/timeout/error.
- Add comment-line heartbeats and verify they keep a connection alive without emitting events.
- State — with a drawn topology — why the projection consumer is the only RabbitMQ subscriber and why an SSE connection is a client of the service, never a consumer.

## Warm-up

Re-read the "Authorization And Isolation" section and the Kotlin recap of `posts/series-4-product-sse/02-sse-correctness.md`. Then open your X03 code and answer in one sentence each: (1) where does the `id:` field come from, (2) what does your connection registry look like, (3) what happens when the server restarts mid-broadcast. If any answer is "it doesn't," that gap is today's agenda.

## System specification

**Scope in**

- The X03 project, extended with: a `StatusBroadcaster` keyed by `patientId` with mandatory `onCompletion`/`onTimeout`/`onError` cleanup; a replay endpoint `GET /prescriptions/{id}/events` honoring `Last-Event-ID`; an ownership check on connect and in the replay query; comment-line heartbeats; a slow-consumer experiment harness.
- A real HTTP/SSE test client (WebClient `ServerSentEvent`, as the blog post uses) — Postman and `curl -N` are demos, not proof.
- Evidence folder: isolation test output, replay log, heartbeat trace, slow-consumer run.

**Scope out**

- **No RabbitMQ competing consumers for SSE — not even one.** The feed into the projection is a single subscriber (A10's applier or a manual insert in tests). This is a hard curriculum constraint, not a taste call.
- No real auth framework (that is A15); `X-Patient-Id` header stand-in is fine, with the EventSource caveat documented.
- No full frontend, no proxy simulation beyond the slow-read client.

**Functional requirements (minimal)**

1. Two patients connected concurrently receive only their own events, including when broadcasts interleave.
2. Reconnect with `Last-Event-ID` replays exactly the missing tail; reconnect during a burst delivers no duplicate and no skip.
3. A connection for a prescription the caller does not own gets `403` and zero bytes.
4. An idle connection receives comment heartbeats at a fixed interval.
5. A slow consumer cannot block the broadcaster or crash the process; the failure mode is defined and observable.

**Constraints**

- Local Docker Postgres (for the projection) + the single Spring Boot app from X03. No managed anything.
- `SseEmitter(0L)` (client owns the connection lifetime), broadcasts only after the projection insert commits — the "SSE is never ahead of the GET" rule from the blog.

## Step-by-step code-along

### Step 1: Registry and cleanup — the structural isolation

**Do:** Implement `StatusBroadcaster` exactly in the blog's shape: `ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>>`, `subscribe(patientId)`, `broadcast(event)`, and cleanup in all three callbacks. Instrument it: a counter of live connections per patient, logged on subscribe and on each cleanup path.

**Run:** Connect one WebClient stream, open a second, close the first via `dispose()`; then check the log.

**Observe:** Subscribe and unsubscribe lines are balanced — the registry empties when a connection closes. If you see a zombie (registry still holds the emitter after dispose), the cleanup path is broken and you just caught the memory leak the blog post warns about.

**Decision:** `CopyOnWriteArrayList` per patient vs a `ConcurrentHashMap` keyed by emitter identity? Nudge: per-patient lists make the broadcast loop trivially safe and the isolation test readable; the write cost is irrelevant at this scale — note that tradeoff in one line for the interview.

### Step 2: The guarded replay

**Do:** Extend the endpoint: parse `Last-Event-ID` (null → full snapshot), check ownership *before* the query, run the replay query `sequence_no > :from` scoped by `prescription_id AND patient_id` in the `WHERE`, then the `catchUp` step: record the latest applied sequence and drop live broadcasts at or below it. Log every replay: `replay from=<from> rows=<n>`.

**Run:** The three reconnect cases from the blog's crash table: fresh connect, reconnect after a gap (insert events while disconnected), reconnect during a burst (insert events while the replay is running).

**Observe:** Fresh connect sees the whole timeline; the gap reconnect sees exactly the missing tail; the burst reconnect sees no duplicate and no skip. The log's `replay from=` lines are the evidence — save them.

**Decision:** Where does ownership live — one check at the controller entry, or also in the repository method? Nudge: the blog post says both, because the replay query is "the same class of read as the GET" — one line in the repository is the difference between "checked on connect" and "checked on every replay."

### Step 3: Authorization at the edge — the negative test

**Do:** Write the two negative tests from the blog: patient 1 requesting patient 2's stream gets `403` with zero events; and patient 1 *replaying* patient 2's stream (valid `Last-Event-ID`, wrong owner) gets `403` with zero events.

**Run:** The tests, plus one manual `curl -N` with the wrong header for the log.

**Observe:** `403` before a single byte — assert `contentLength`/headers show nothing streamed. Save the test output. This is the exact scenario `posts/series-4-product-sse/03-testing-realtime.md` says most teams skip: the replay path is the second authorization surface.

**Decision:** `403` vs `404` for a prescription the caller does not own? Nudge: for a health system, a `404` reveals less about existence; but the blog and exercise use `403` — pick one, and document the disclosure reasoning in a comment, because that reasoning is interview material.

### Step 4: Heartbeats

**Do:** Add a `ScheduledExecutorService` (or Spring `@Scheduled`) that sends `": keep-alive"` comment lines every 15–25s to all live emitters — a comment, not a data event. Log one line per tick with the live-connection count.

**Run:** Open a stream, wait through two tick intervals, and capture the raw response with a client that shows comment lines (WebClient shows them; `curl -N` prints them).

**Observe:** The connection stays open across the tick interval, the client's event handlers never fire (no `data:` lines from the heartbeat), and sequence numbers did not advance. Save the raw trace.

**Decision:** `SseEmitter(0L)` (no server timeout, client owns lifetime) vs an explicit server timeout with the heartbeat as the only keep-alive? Nudge: the blog post uses `0L` and the client owns the connection; the heartbeat then exists for *proxies*, not the server — make sure your written rationale says which.

### Step 5: The slow consumer — define the edge

**Do:** Write a client that reads events but sleeps 200ms per event (simulating a slow phone/proxy), then broadcast a burst of 50 events. Watch what the emitter buffer does: if you are using `SseEmitter.send` with a full buffer, Spring throws `IllegalStateException: The async response timed out or failed` — decide your policy: catch-and-drop, catch-and-close, or a bounded in-memory queue with a drop-oldest rule.

**Run:** The burst against the slow client; then the same burst against a fast client.

**Observe:** The fast client receives all 50 in order. The slow client hits your defined failure mode — logged, counted, and (per your policy) either the connection closes (client reconnects via `Last-Event-ID` and gets the tail — the design absorbs this) or events are dropped with a counter. The key observation: the broadcaster thread never blocks and the fast client is unaffected — a slow consumer is isolated, which is what "one slow phone must not stall the pharmacy" means operationally.

**Decision:** Drop-and-close vs bounded-queue-and-drop-oldest vs unbounded buffering? Nudge: unbounded buffering is a memory leak with a fancier name; closing the connection pushes the problem to the client's automatic reconnect, which is the *designed* recovery path. Write 3 lines on why.

### Step 6: The two-patient interleaving proof (optional)

**Do:** The isolation test from `posts/series-4-product-sse/03-testing-realtime.md`: open patient 1 and patient 2 streams concurrently, broadcast events for both in an interleaved sequence (1-A, 2-B, 1-C, 2-D...), and assert each stream saw exactly its own ordered sequence.

**Run:** The test, 50 events per patient.

**Observe:** Each stream's received ids are exactly its own monotonic sequence; the other patient's ids appear nowhere. Save the diffed outputs. This is the test the blog post calls "the assertion that makes the test meaningful": the broadcasts happened while both connections were open, so a misrouting bug had a real chance to fire.

## Try this

**The slow-consumer saturation run.** Broadcast 200 events while a slow client (100ms/event) and a fast client are both connected. Capture: fast client's complete ordered list, slow client's outcome per your policy, the broadcaster's thread time, and the log's connection-cleanup lines. Then repeat with the slow client *disconnected* mid-burst and a fresh client reconnecting with `Last-Event-ID` — and prove the reconnect delivers the missing tail with no duplicates. That run is the whole SSE story in one screen: a slow consumer is contained, and the reconnect contract absorbs every edge you did not code.

**The wrong-patient replay attempt.** Send a valid `Last-Event-ID` for patient 2's stream using patient 1's header *while events are being inserted*. Assert `403` and zero bytes. If this passes only because your controller check ran before the query, that is the evidence that the check belongs where it is — and a hint about what breaks if someone later adds a second read path.

## Trade-off fork

Pick **one**, write 3–5 lines justifying it, and name the lost benefit.

- **A: per-connection in-memory queue in the broadcaster vs B: replay-from-projection as the only recovery.** A per-connection queue smooths small bursts and never forces a reconnect — it is unbounded memory unless you cap it, and capping is the slow-consumer policy in disguise. Replay-only keeps the SSE layer stateless — every buffer problem becomes a reconnect, which is correct and *loud* but drops the smoothness your queue bought.
- **A: heartbeat comment lines vs B: no heartbeat, rely on `SseEmitter(0L)` and clients.** The comment costs one scheduled thread and keeps proxies alive — it advances nothing, which is the point. No heartbeat is simpler — and behind any proxy with a 60s idle timeout, every quiet patient is disconnected on a timer and reconnects forever.
- **A: in-memory broadcast buffer with drop-oldest vs B: close-on-full, let `Last-Event-ID` rebuild.** Drop-oldest hides the slow consumer from the client until the missing tail is too old to matter. Close-on-full makes the slow consumer *visible* — every stall becomes a reconnect with a replay request in the logs, at the cost of a burst of reconnect traffic you must measure.

## Hints

**Hint 1:** If your two-patient test leaks events across streams, the almost-certain cause is a broadcaster that iterates *all* emitters instead of indexing by patient, or a registry keyed by prescription instead of patient. The test is designed so that a filter applied late ("if event.patientId == emitter.patientId") still passes — the structural keying is the thing being proven, so make sure your broadcast path contains no filter at all.

**Hint 2:** If the reconnect-during-burst test shows a duplicate (event N sent twice) instead of a skip, the `catchUp` boundary is wrong: you must record the highest sequence *already sent during replay* and suppress live broadcasts at or below it — comparing against the pre-replay snapshot misses events that landed mid-replay. If the slow-consumer run shows the *fast* client stalling, your broadcaster is blocking on a full buffer in the same thread as the broadcast loop — that is the isolation failure this kata exists to expose; the fix is per-emitter send isolation, not a bigger buffer.

## Checkpoint / success criteria

You may leave when:

- The two-patient interleaving test passes with zero cross-patient ids (step 6 or the "Try this" variant).
- The negative authz tests (connect and replay) return `403` with zero bytes, output saved.
- The heartbeat trace shows comment lines with no `data:` and no sequence advance.
- The slow-consumer run has a defined, logged, counted outcome, and a fast consumer on the same server is unaffected.
- You can draw the topology from memory and state the constraint without prompting: **one projection subscriber feeds the store; SSE connections are service clients, never RabbitMQ consumers.**

## Bottleneck & reflection questions

1. The slow-consumer policy you chose converts the problem into reconnects — which the `Last-Event-ID` protocol absorbs. What patient-visible behavior does a *fast reconnect loop* (slow phone, aggressive proxy timeout) produce, and which signal in your logs would reveal it?
2. Your heartbeat is a comment line. What happens to your isolation guarantee if a future engineer "helpfully" changes the heartbeat to a real `data:` event with a synthetic sequence?
3. The broadcaster is in-memory and dies with the server. Why is that safe — and which of your four properties would break if the *projection* were also in-memory?
4. The ownership check lives in the controller and the repository. Which one protects you from a future endpoint that reuses the repository read path without the controller check?
5. Exercise 3 Milestone 7 says SSE "must not remove or lose business work from RabbitMQ." Where in this kata's design is that guaranteed, and what test would prove it on your X03/A10 stack?

## Handoff

- **Next elective:** `A15_security_baselines.md` (the identity story behind `X-Patient-Id`, DB least-privilege, no-PII-in-logs — the healthcare bar for the header stand-in), or `A12_observability_slice.md` (tracing the same connection lifecycle in logs).
- **Related showcase exercise:** `../../pharmacy-fulfillment/exercise_03_production.md`, Milestones 7–8 — connection lifecycle, replay, ordering, authorization, isolation — with this kata's tests as the Milestone 9 SSE evidence.
- **Interview line:** "The SSE layer is a service client, never a RabbitMQ consumer — one ordered subscriber feeds the projection, the broadcaster is keyed by patient so misrouting is structurally impossible, replay is a guarded query with ownership enforced twice, heartbeats are comment lines, and a slow consumer is contained by a defined policy because the reconnect contract absorbs it."

## Optional stretch

One harder twist: simulate a *proxy* in front of the SSE endpoint — a small reverse proxy that buffers responses (or just a deliberately slow localhost router via `tc`-style throttling if your OS allows) — and prove the heartbeat + `X-Accel-Buffering: no`-style headers keep events flushing instead of accumulating into a 4KB buffer. Measure flush latency before and after, and write the three-sentence production note the blog post implies.
