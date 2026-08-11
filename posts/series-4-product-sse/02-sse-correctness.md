# SSE Correctness: IDs, Replay, Ordering, and Isolation

The first post in this series made a promise: the status GET is the truth, and SSE is a delivery optimization that reduces the polling delay. This post is where that promise is kept or broken. SSE is easy to demo and hard to make correct — the demo shows statuses arriving "live," and the failure modes only show up when a patient's phone loses signal for thirty seconds, when two prescriptions share a status, or when the server restarts mid-broadcast. Each of those moments is a test of the same four properties: event IDs, replay, ordering, and isolation.

The system-design trap was already named in series 3: never make each SSE connection a RabbitMQ consumer. The correctness trap is the one this post is about — building the SSE layer as a *second source of truth* that can drift from, race ahead of, or leak across the persistence layer that the GET reads. The architecture that avoids all three is a projection store, an ordered event stream per prescription, and SSE connections that are stateless clients of your service — replayable from `Last-Event-ID` at any moment.

## SSE Is A Wire Format, Not A Magic Pipe

Before the design, the vocabulary, because interviews test whether you know what you are actually building. SSE is a one-way HTTP response that stays open. The server sends text lines in the `text/event-stream` content type; the client's `EventSource` parses them into events. The wire format has four fields worth knowing, and two of them do real work:

| Field | Meaning | Who needs it |
| --- | --- | --- |
| `data:` | The payload, one line per `data:` field; consecutive `data:` lines join with a newline | Client renders this |
| `id:` | The event's ID, opaque to the client | The entire replay design hangs on it |
| `event:` | Optional event type (default `message`) | Client dispatch |
| `retry:` | Milliseconds the client should wait before reconnecting | Client |
| `: comment` | Ignored line, used for keep-alives | Keeps proxies from timing out idle connections |

Two properties of SSE are the entire point, and both are free:

- **Ordered.** One connection is one TCP stream; events arrive in the order the server writes them. You get ordering for free *within* a connection — the work is making sure the server writes events in the right order in the first place.
- **Reconnect-aware.** When the client receives an `id:` field and later reconnects, the browser sends that ID back in the `Last-Event-ID` header automatically. The server can then replay everything after it. This is the one piece of SSE that exists specifically for crash windows, and it is the piece most demo implementations ignore.

SSE is not bidirectional (no client-to-server messaging — that is what the REST endpoints are for), it is not binary, and it is one-to-one: every connection receives exactly what the server sends on that connection, which is precisely why a misrouted RabbitMQ topology (one shared queue, many SSE consumers) delivers patient 7's update to patient 3's browser. The wire format itself has no isolation; the application owns isolation end to end.

## The Architecture: Projection Store, Not Broker Consumers

The topology post ended with the decisive sentence: SSE connections are clients of your service, never consumers of your queues. Here is the shape that sentence describes:

```text
pharmacy.events (topic exchange)
        |
        v
[pharmacy.notifications] queue  --> status projection (single ordered consumer,
        |                             inbox + sequence numbers, series 3)
        v
  status_projection table (per-prescription, per-patient rows)
        |
        v
  SSE layer: reads projection, broadcasts to open connections
        |
        +--> patient 1's EventSource
        +--> patient 2's EventSource
```

Work flows in one direction: RabbitMQ fans facts to a durable subscriber queue, a single ordered consumer applies them to the projection store inside a transaction with an inbox (series 3), and the SSE layer — a plain in-process service — watches the projection and pushes new rows to the open connections. The broker is behind the wall. When a connection opens or reopens, the SSE layer never asks the broker anything; it queries the projection, which is the same store the status GET reads. That is the invariant that makes SSE a delivery optimization: **SSE and GET are two readers of one source of truth.**

```kotlin
@Component
class StatusBroadcaster(
    private val projections: StatusProjectionRepository,
) {
    private val emitters = ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>>()

    fun subscribe(patientId: UUID): SseEmitter {
        val emitter = SseEmitter(0L) // no server timeout; client controls lifetime
        emitter.onCompletion { unsubscribe(patientId, emitter) }
        emitter.onTimeout { unsubscribe(patientId, emitter) }
        emitter.onError { unsubscribe(patientId, emitter) }
        emitters.computeIfAbsent(patientId) { CopyOnWriteArrayList() }.add(emitter)
        return emitter
    }

    fun broadcast(event: StatusEvent) {
        emitters[event.patientId]?.forEach { emitter ->
            runCatching { emitter.send(event.toSseEvent()) }
        }
    }
}
```

Two details in that snippet are correctness, not cosmetics. The emitters map is keyed by `patientId`, so a broadcast for patient 1 physically cannot reach patient 2's connections — the isolation is structural, not a filter applied late. And every emitter registers `onCompletion`/`onTimeout`/`onError` cleanup: an SSE emitter that is never removed is a memory leak that shows up as thousands of zombie connections and a diagnosis nobody enjoys. When the server restarts, every in-memory emitter is gone with it — which is fine, because the next section makes reconnection cheap.

One more boundary, from the REST post: the patient API surface stays two endpoints. SSE is an optional third surface under `/prescriptions/{id}/events`, and the `X-Patient-Id` header stand-in that authenticated the GET authenticates the stream too — with one wire-level caveat covered under authorization below.

## Event IDs And Replay: `Last-Event-ID` Is The Contract

The projection stores one row per status change per prescription, with a sequence number. That sequence is the SSE event ID:

```sql
CREATE TABLE status_projection (
    patient_id       uuid NOT NULL,
    prescription_id  uuid NOT NULL,
    sequence_no      bigint NOT NULL,
    status           text NOT NULL,
    occurred_at      timestamptz NOT NULL,
    PRIMARY KEY (prescription_id, sequence_no)
);
CREATE INDEX status_projection_patient_lookup
    ON status_projection (patient_id, prescription_id, sequence_no);
```

Sequence numbers are per prescription (not global — see the ordering section) and monotonic, and they come from the same envelope the RabbitMQ projection consumed. The SSE controller reads the last known ID from the `Last-Event-ID` header and replays from `sequence_no > lastId`, plus a snapshot:

```kotlin
@RestController
class PrescriptionEventsController(
    private val projections: StatusProjectionRepository,
    private val broadcaster: StatusBroadcaster,
    private val access: PrescriptionAccessPolicy,
) {

    @GetMapping(value = ["/prescriptions/{prescriptionId}/events"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @PathVariable prescriptionId: UUID,
        @RequestHeader(value = "Last-Event-ID", required = false) lastEventId: String?,
        @RequestHeader("X-Patient-Id") patientId: UUID,
    ): SseEmitter {
        if (!access.ownsPrescription(patientId, prescriptionId)) throw ForbiddenException()

        val from = lastEventId?.toLongOrNull() ?: 0L
        val emitter = broadcaster.subscribe(patientId)

        projections.after(prescriptionId, from)     // historical events, in order
            .forEach { emitter.send(it.toSseEvent()) }

        val latest = projections.latest(prescriptionId)
        broadcaster.catchUp(prescriptionId, latest.sequenceNo)  // events that landed during replay
        return emitter
    }
}
```

That is the whole replay story, and every piece of it exists for a specific crash:

| Crash moment | What the client sends on reconnect | What the server replays | Outcome |
| --- | --- | --- | --- |
| Client loses signal for 30s | `Last-Event-ID: 3` | events 4, 5, 6 from the projection | Patient sees the full timeline, no gap |
| Server restarts | `Last-Event-ID: 6` | events 7+ | No events lost — the projection survived |
| Client reconnects for the first time | no header | snapshot: events 1..n | Fresh client sees the whole history |
| Reconnect during a burst | `Last-Event-ID: 5`, then new event 7 arrives during replay | events 6, then 7 — no duplicate, no skip | Order preserved, exactly once per ID |

The key phrase in that last row is "no duplicate, no skip." The `Last-Event-ID` protocol is at-least-once-ish from the server's point of view and the client must tolerate the server replaying the boundary event — in practice the server makes replay *at-most-once per ID* by using the stored sequence as the ground truth and the client treating repeated IDs as already-seen. What the SSE stream never does is remember anything itself. The stream is ephemeral; the projection is durable. That sentence is the correct answer to "what happens if you lose the SSE connection to the database?" — nothing, because the stream holds no state that matters.

Three traps hide in this simple loop, and all three are interview bait:

- **Do not generate event IDs on the server and forget them.** If the `id:` is a fresh UUID per send, the client's `Last-Event-ID` is meaningless, replay cannot find the cut-off point, and a 30-second outage produces a permanent gap. The ID must be the projection's sequence number — durable, ordering-relevant, and replayable.
- **Do not replay before authorization.** Replay is a query for events *after* an ID. If that query does not re-verify that the caller owns the prescription, a patient who learns another patient's prescription ID can replay their entire history. The check happens on connect, and it happens again inside the replay query, because the replay query is the same class of read as the GET.
- **Do not race the snapshot against live events.** If events 5 and 6 are inserted between "read the snapshot" and "start broadcasting," the client gets 1..4, then 5 and 6 from the live path — fine — or worse, 1..6 from the snapshot and then 5 and 6 again from the live path if the catch-up read missed the boundary. The defensible fix is the `catchUp` step in the code: after replaying the snapshot, record the latest applied sequence and drop live events at or below it. The connection is then guaranteed to deliver every sequence exactly once, in order, no matter where it joins.

## Ordering: Per-Prescription Sequence Numbers, Never A Global Clock

SSE delivers in order over a single connection, which is free. The hard half is that the *server* must write in order, and the source of that order is the projection. Three ordering rules cover the design:

1. **Sequence numbers are per prescription.** A global counter would serialize every patient in the system, create a single contention point, and make replay queries span unrelated rows. Per-prescription monotonic sequences give every stream its own order while allowing all patients to advance in parallel. Prescription 42's events are numbered 1,2,3…; prescription 91's are 1,2,3…; they never interleave because the projection applies each prescription's stream with a single ordered consumer (series 3).
2. **The connection is append-only.** Once the SSE layer sends sequence 4, it must never send 3, and it must never send 6 until 5 has been persisted and broadcast. The projection consumer guarantees the store is ordered; the broadcaster sends rows in `sequence_no` order and never reorders on its own. If an out-of-order or gapped row is ever observed (a relay republish that skipped the inbox, a poisoned batch), the layer does what series 3 taught: stop, log loudly, and dead-letter — never apply, never broadcast a status that jumps backward or forward.
3. **"Latest wins" is forbidden for status.** A last-write-wins cache that stores only the current status can *answer* a GET, but it cannot *drive* an SSE stream: a patient who watches `SUBMITTED → APPROVED → PACKAGING → READY` needs the steps, not just the last one, and a delayed replay of `APPROVED` after `READY` would draw a backwards arrow on their screen. The projection is append-only history, which is also why it doubles as the timeline in the API response of the first post — SSE and the GET's `timeline` array are the same rows.

Where does the stream stand relative to the database? The broadcaster sends rows after their transaction commits, so **SSE is never ahead of the GET** — at worst it lags the GET by the projection consumer's processing time (milliseconds in the challenge, and the same lag a poll would have). That one-way ordering relationship is worth stating in the interview: the stream can only be as fresh as the projection, the projection is updated transactionally from the broker, and the GET can always be trusted to be at least as fresh as the stream. If an interviewer asks "can SSE ever show a status the GET doesn't have?", the answer is no — and the mechanism is that the broadcaster has no input except committed projection rows.

## Authorization And Isolation: Prove The Leak Is Impossible

SSE introduces a new isolation surface that REST never had: a *push* channel where the server initiates data flow to a recipient it chose. Two distinct failures are possible, and each needs its own defense:

**Misrouting** — the wrong recipient's connection receives the event. Structural defense: the broadcaster map is keyed by `patientId`, so the send path can only address connections registered for that patient. This is why the competing-consumer topology was rejected in series 3: a shared queue with one connection per consumer does not route by patient at all. The defense here is not filtering; it is keying.

**Unauthorized replay** — the recipient asks for a stream or a replay for a prescription they do not own. Defense: the `ownsPrescription` check on connect, the same check inside the replay query, and a `403` before a single byte is sent. Note the endpoint reads `prescriptionId` from the path and `patientId` from the header — the ownership check is what connects them, and skipping it means any patient who guesses an ID gets someone else's timeline.

The auth stand-in carries one real-world caveat worth knowing cold: **the browser `EventSource` API cannot set custom headers.** In the challenge, staff and test clients are fine — they use HTTP clients with full control. But if the demo shows a browser tab, the `X-Patient-Id` header cannot travel with an `EventSource`, and the patient identity must arrive another way: a cookie (with CSRF considerations), or a short-lived token in the query string (`GET /prescriptions/{id}/events?token=...`), which is exactly why real SSE systems use tokens in the URL. The interview-grade answer: identity arrives on the connection however it arrives, but the authorization decision is made server-side, before replay, on every single connection — and the replay query enforces it again.

Proving no leakage is a test, not a promise (the next post in the series covers it in depth, but the shape belongs here). The integration test opens two concurrent SSE connections for two different patients, interleaves events for both, and asserts that each client received exactly its own sequence — plus a negative test: patient 1 connecting to patient 2's prescription ID gets `403` and zero events. The assertion that makes the test meaningful is that the events were broadcast *while both connections were open*, so a misrouting bug has an actual chance to fire.

## Compare The Baseline And The Enhancement On Purpose

The first post said SSE is a cuttable enhancement. Here is the comparison that defends that claim, because "cuttable" is a product decision, not a coding opinion:

| Property | Polling `GET /prescriptions/{id}` | SSE stream |
| --- | --- | --- |
| Latency | One poll interval (challenge: 2-5s) | Milliseconds after commit |
| Load per patient | Poll traffic on every check | One open connection, idle otherwise |
| Server state | None — stateless, trivially scale-out | In-memory emitters; restart drops connections (replay recovers) |
| Correctness fallback | The GET *is* the truth | The stream *reads* the truth; a failed stream falls back to GET |
| Ordering | Timeline from projection, ordered by sequence | Same rows, pushed in sequence order |
| Reconnect | Nothing to do | `Last-Event-ID` replay — the one new moving part |
| Failure handling | Any failure = next poll succeeds | Connection drop + server restart + proxy timeout — all handled by replay |
| Cost to build | Already built | Controller, broadcaster, replay, tests |

The honest summary: SSE buys latency and a better patient feel, and it costs one new moving part (reconnect replay) that must be proven with tests. Nothing else changes. If the 2-hour slice ships polling-only, the patient experience is *correct* — just not instant. If the 5-hour slice adds SSE, it must pass the same assertions the GET passes: ordered, complete, and never leaking across patients. That is the property an evaluator actually checks: not that statuses arrive live, but that the realtime channel is as trustworthy as the baseline it enhances.

A note on heartbeat and proxies, because the demo always dies there: a connection that receives no bytes for a minute is killed by intermediate proxies and by some load balancers. The server sends a comment line (`: keep-alive`) every 15-25 seconds — a comment, not a data event, so the client's event handlers never fire and the sequence numbers never advance. And behind a proxy that buffers responses, set `X-Accel-Buffering: no` (nginx) or equivalent so events flush immediately instead of accumulating into a 4KB buffer that turns your realtime stream into a very slow poll.

## Pitfalls Interviewers Probe

- **"Can't I just make each SSE connection consume the RabbitMQ queue?"** — No. Competing consumers deliver each message to exactly one connection, so patient 3 receives patient 7's update and patient 7 misses it. The broker has no patient routing; the projection + per-patient keyed emitters do.
- **"Why does the `id:` field matter so much?"** — It is the only durable link between the ephemeral connection and the durable projection. Without a stable, replayable ID, a reconnect cannot resume; it restarts or gaps.
- **"What do you send on a fresh connection?"** — The full timeline from the projection (snapshot), then live events from the next sequence. A fresh client must see history; a client with `Last-Event-ID` must see only the missing tail.
- **"What happens when the server restarts and all emitters are gone?"** — The clients reconnect, send `Last-Event-ID`, and get the tail replayed. That is the entire point of storing sequence numbers in the projection rather than in the emitters.
- **"Can the stream be ahead of the database?"** — No. Broadcasts only send committed projection rows, so the GET can never be behind the stream in truth — at worst the stream is behind the GET.
- **"Why per-prescription sequence numbers instead of one global counter?"** — Ordering is per stream. A global counter serializes all patients and makes replay queries span unrelated data; per-prescription sequences preserve order with full parallelism.
- **"The client sends `Last-Event-ID: 99`. What do you do with it?"** — Parse it, verify the caller owns the prescription, then replay `sequence_no > 99` from the projection. Never trust the number as an index into client-visible data; it is a cut-off point for a server-side query.
- **"How do you prove no cross-patient leakage?"** — A test with two concurrent live connections and interleaved events, asserting each client receives exactly its own sequence, plus a negative authorization test on the replay path. Isolation is a keyed map plus an ownership check, and both have test coverage.
- **"Why a comment line for heartbeats?"** — A comment is ignored by clients but keeps the socket alive through proxies; a real event would pollute the client's sequence and could be mistaken for a status change.
- **"EventSource can't set headers — how does your patient authenticate?"** — Identity arrives via cookie or short-lived token in the query string; the server-side ownership check does not change. That is a known SSE constraint, and naming it unprompted reads as production experience.

## Kotlin And Spring Recap

- The SSE layer is a `SseEmitter` registry keyed by `patientId` (`ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>>`), with `onCompletion`/`onTimeout`/`onError` cleanup mandatory.
- The endpoint is `@GetMapping(produces = [MediaType.TEXT_EVENT_STREAM_VALUE])`; the `Last-Event-ID` header arrives via `@RequestHeader("Last-Event-ID", required = false)`.
- Replay is `projections.after(prescriptionId, from)` read from the append-only projection table in `sequence_no` order, plus a `catchUp` step that drops live events at or below the last applied sequence.
- Event IDs are the projection's per-prescription sequence numbers — durable, ordered, and never generated per send.
- Heartbeats are comment lines (`: keep-alive`) on a schedule; `SseEmitter(0L)` disables the server-side timeout so the client owns the connection lifetime.
- Broadcasts run `runCatching { emitter.send(...) }` — a dead connection throws on send, and cleanup is a single path in `unsubscribe`.

## Interview Review Checklist

- Why must an SSE connection never be a RabbitMQ consumer, and what topology replaces it?
- Where is the source of truth, and why can SSE never be ahead of it?
- What is the `id:` field for, and why does `Last-Event-ID` make reconnects lossless?
- Walk the crash table: signal loss, server restart, first connect, reconnect during a burst — what replays in each case?
- How do per-prescription sequence numbers provide ordering without a global clock?
- Why is "latest wins" unacceptable for a status stream, and what does the append-only projection give you instead?
- Where does the ownership check live, and why is it enforced in both the connect and the replay paths?
- How do you prove no cross-patient leakage in a test?
- What does the stream-versus-GET comparison table say about a polling-only 2-hour slice?
- Why can't `EventSource` carry your `X-Patient-Id` header, and what is the defensible workaround?

## Interview Takeaway

SSE correctness is four decisions, and all four are boring on purpose. Event IDs come from the durable projection, not from the broadcaster. Replay reads the same store the GET reads, with `Last-Event-ID` as the cut-off point and the ownership check enforced on every replay. Ordering is per-prescription sequence numbers pushed over an append-only connection that can never outrun the database. Isolation is a broadcaster keyed by patient plus an authorization check on both entry points, proven by a concurrent two-patient test. Get those four right and SSE is exactly what the first post promised: a delivery optimization that makes a correct, already-testable baseline feel instant — and the next post in this series turns each of these claims into an integration test you can run in CI.
