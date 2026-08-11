# Testing Realtime Patient Experiences

The previous post built the SSE layer and made four claims: reconnect replay is lossless, events arrive in order without gaps or duplicates, authorization holds on both the connect and the replay paths, and two patients sharing a server never see each other's statuses. Those claims are the entire value of the realtime feature — and every one of them is invisible in a demo. A demo shows statuses arriving "live." It does not show what happens when the patient's phone drops signal for thirty seconds, when the server restarts mid-broadcast, or when patient 1 and patient 7 are connected at the same time. That is this post's job: turn the demo's silence into assertions.

The rule from series 3 applies unchanged: a design that cannot be tested is a design that has not been finished. For SSE, the untestable parts are exactly the parts that matter — replay cut-offs, ordering, and isolation are all stateful behaviors across time, and the only honest way to prove them is a real client, a real HTTP server, and a real store.

## Postman Shows A Demo; A Client Proves Behavior

The fastest way to *look* at an SSE stream is a tool that can hold a connection open — Postman's EventSource support, `curl -N`, or a browser tab. All three are demo tools, and the interview point is to know precisely what they cannot do:

| Capability | Postman / browser demo | Real SSE integration client |
| --- | --- | --- |
| "Something arrived while connected" | Yes | Yes |
| Assert exact event payloads | Manual eyeballing | Assertions |
| Control `Last-Event-ID` on reconnect | No — not scriptable | Yes, as a header |
| Simulate disconnect and reconnect mid-stream | No | Yes — dispose and re-open |
| Verify order, gaps, and duplicates across 50 events | No — eyes fail at 50 | Sequence assertions |
| Run two patients concurrently and cross-check streams | No | Yes — the isolation proof |
| Run in CI on every commit | No | Yes |

The difference is not tool preference; it is the difference between observing and asserting. Postman proves that *a* stream exists. The integration client proves that *your* stream is correct. Naming that distinction unprompted is exactly the kind of "realtime UX is proven, not demoed" signal the challenge's evaluation criteria are looking for.

The standard test client is Spring's `WebClient`, which speaks the SSE wire format natively and exposes each event as a `ServerSentEvent<String>`:

```kotlin
fun sseClient(baseUrl: String, patientId: UUID = PATIENT_ID, lastEventId: Long? = null): Flux<ServerSentEvent<String>> =
    WebClient.builder()
        .baseUrl(baseUrl)
        .build()
        .get()
        .uri("/prescriptions/{id}/events", PRESCRIPTION_ID)
        .header("X-Patient-Id", patientId.toString())
        .header("Last-Event-ID", lastEventId?.toString())
        .retrieve()
        .bodyToFlux(ServerSentEvent::class.java)
```

Two details make this client test-shaped rather than demo-shaped. First, `X-Patient-Id` is a parameter, because the authorization tests need to vary it per connection. Second, `Last-Event-ID` is passed explicitly, which is the handle every reconnect test turns — a browser would set it automatically, and the test's job is to take control of it and assert the outcome. `ServerSentEvent` exposes the wire fields you care about — `id()`, `data()`, `event()` — and the library parses the `id:` line for you, which is one less place to write your own SSE parser and one less place to get it wrong.

## The Test Server: Real HTTP, Real Projection, Real Broker

The SSE layer is small, but it is not isolated: the endpoint reads the projection, the broadcaster holds open connections, and the projection is fed by the RabbitMQ consumer from series 3. Three integration levels cover it, each with a distinct cost:

1. **Projection-level tests** (PostgreSQL Testcontainer, no broker): insert status rows directly, hit the real SSE endpoint over real HTTP on a random port, assert what the stream delivers. This is where reconnect, ordering, and isolation live, and it is fast and deterministic — no broker to race.
2. **Endpoint-level tests** (full Spring context, real controller, broadcaster, and repository): the message path is short-circuited, so nothing depends on RabbitMQ timing.
3. **One end-to-end test** (PostgreSQL + RabbitMQ Testcontainers, the real listener): publish a real AMQP message, let the projection consumer apply it, assert the status arrives on the stream. This proves the entire claim — RabbitMQ to browser — and there should be exactly *one* of them, because it is the slowest and the noisiest test in the suite.

The server setup is the same class the previous series used, with one addition: a real HTTP port.

```kotlin
@Testcontainers
@SpringBootTest(webEnvironment = RANDOM_PORT)
class PrescriptionStreamIntegrationTest {

    companion object {
        @JvmStatic
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    @LocalServerPort lateinit var port: Int
    @Autowired lateinit var projections: StatusProjectionRepository
    @Autowired lateinit var broadcaster: StatusBroadcaster
}
```

The `RANDOM_PORT` matters: `WebClient` needs real sockets to observe connection behavior, and a reconnect test cannot fake a dropped TCP connection inside a mocked environment. The broadcaster is injected deliberately — the isolation test needs to trigger a broadcast *after* both connections are open, and a projection insert alone does not fire the emitters. That is the full-stack truth each test asserts: a row in the store, a real broadcast, a real socket.

One scope note that earns credit in review: for a 2-5 hour challenge, do not build a dedicated SSE test framework. The five or six tests in this post are the complete suite, they all share one base class, and each is under thirty lines.

## Reconnect Tests: The Crash Windows Become Assertions

The previous post's crash table listed four moments: client loses signal, server restarts, first connect, and reconnect during a burst. Each row of that table is a test, and the tests are short because the design is short.

**The reconnect test is the heart of the realtime claim.** The client receives events 1-3, drops the connection, events 4 and 5 happen while it is gone, then it reconnects with `Last-Event-ID: 3` and must receive exactly 4 and 5 — no gap, no duplicate, no replay of 3:

```kotlin
@Test
fun `reconnect with Last-Event-ID resumes exactly after the gap`() {
    projections.insert(StatusEvent(patientId, prescriptionId, 1, "SUBMITTED"))
    projections.insert(StatusEvent(patientId, prescriptionId, 2, "APPROVED"))
    projections.insert(StatusEvent(patientId, prescriptionId, 3, "PACKAGING"))

    val first = sseClient(baseUrl()).take(3).map { it.id()!!.toLong() }.collectList().block()!!
    assertThat(first).containsExactly(1L, 2L, 3L)

    broadcaster.broadcast(StatusEvent(patientId, prescriptionId, 4, "READY"))
    broadcaster.broadcast(StatusEvent(patientId, prescriptionId, 5, "FULFILLED"))

    val resumed = sseClient(baseUrl(), lastEventId = 3)
        .take(2)
        .map { it.id()!!.toLong() }
        .collectList()
        .block()!!

    assertThat(resumed).containsExactly(4L, 5L)
}
```

Three assertions hide in that test, and all three are deliberate. The first connection observed 1-3 in order. The replayed tail is exactly `[4, 5]` — `containsExactly` is the no-gap, no-duplicate, no-replay-of-3 assertion in one line. And the broadcast happened *while no connection was listening*, proving the replay comes from the projection, not from any in-memory buffer the broadcaster kept. If the implementation had stored events only in the emitters, this test fails immediately — which is precisely the failure mode the design was meant to avoid.

**The fresh-connection test** covers the first-connect row: no `Last-Event-ID` header, and the client must receive the full history 1..n as a snapshot, in order. **The burst test** covers the race the previous post called out by name — subscribe, then write events, and assert exactly-once-in-order delivery even though the join happened mid-burst. That test exercises the `catchUp` logic:

```kotlin
@Test
fun `connection opened during a burst receives every sequence exactly once`() {
    val client = sseClient(baseUrl()).doOnSubscribe {
        broadcaster.broadcast(StatusEvent(patientId, prescriptionId, 1, "APPROVED"))
        broadcaster.broadcast(StatusEvent(patientId, prescriptionId, 2, "PACKAGING"))
    }

    val seen = client.take(3).map { it.id()!!.toLong() }.collectList().block()!!
    assertThat(seen).containsExactly(1L, 2L, 3L)
}
```

`doOnSubscribe` fires the broadcasts at the exact moment the client subscribes — not before, not after — so the test genuinely interleaves the snapshot read, the live path, and the catch-up. A connection that joined at the wrong millisecond must still see 1, 2, 3 exactly once.

The server-restart row deserves an honest note on test scope. Restarting the whole application context inside a test is slow and flaky. But from the client's perspective, a server restart is indistinguishable from a fresh connection after the old one died — both end with a reconnect that carries `Last-Event-ID` and a projection that survived. So the restart claim is proven by composition: the reconnect test proves the tail replays from the store, and the fresh-connection test proves the store survives with full history. State that composition in the interview and you have answered "how do you test server restart?" without a 30-second context reload.

## Ordering, Gaps, And Duplicates: Assert The Sequences

The ordering claim has two halves: within a connection the stream is ordered (free, per the previous post), and the server writes in the right order (earned). The earned half is asserted at the sequence level — not "the right statuses arrived" but "the exact sequence list arrived, in order, with no holes":

```kotlin
@Test
fun `stream delivers the exact ordered sequence with no gaps`() {
    (1L..50L).forEach { seq ->
        broadcaster.broadcast(StatusEvent(patientId, prescriptionId, seq, "STATUS-$seq"))
    }

    val seen = sseClient(baseUrl())
        .take(50)
        .map { it.id()!!.toLong() }
        .collectList()
        .block()!!

    assertThat(seen).isEqualTo((1L..50L).toList())
}
```

`isEqualTo((1L..50L).toList())` is the whole discipline: fifty events is beyond what any human verifies by watching, and the assertion catches reordering, gaps, and duplicates in a single comparison. Two negative tests belong next to it:

- **A delayed replay cannot draw backwards.** Insert sequences 1, 2, 3, then a late `2` (the case a poisoned relay could produce), then 4. The stream must never emit the late 2 after 3 — the projection consumer's gap detection, or the replay cut-off, rejects it before the broadcaster sees it. Assert the delivered IDs are strictly increasing.
- **Duplicates are dropped, not amplified.** Broadcast sequence 5 twice (the consumer's inbox was supposed to prevent this, but the SSE layer must not be the second failure point). Assert the client sees 5 exactly once.

These tests are cheap, and they are the difference between "we used sequence numbers" and "sequence numbers are enforced." If an interviewer asks what breaks first when the series-3 projection consumer loses its ordering discipline, the answer is these two tests.

## Authorization And Isolation: Prove The Leak Is Impossible

The previous post defined two entry points — the connect path and the replay path — and both carry the ownership check. Both get tests, and both assert the same two things: a `403`, and zero events:

```kotlin
@Test
fun `patient without ownership gets 403 with zero events on connect`() {
    val wrongPatient = sseClient(baseUrl(), patientId = OTHER_PATIENT_ID)

    StepVerifier.create(wrongPatient)
        .expectErrorMatches { it is WebClientResponseException.Forbidden }
        .verify()
}

@Test
fun `patient without ownership gets 403 on the replay path too`() {
    val wrongPatient = sseClient(baseUrl(), patientId = OTHER_PATIENT_ID, lastEventId = 2)

    StepVerifier.create(wrongPatient)
        .expectErrorMatches { it is WebClientResponseException.Forbidden }
        .verify()
}
```

The second test is the one most implementations miss, and it is the more dangerous of the two: the unauthorized replay is a *read* of another patient's history triggered by an ID the attacker should never have. Both tests assert `expectErrorMatches` and nothing else — a `403` response and no events is the only acceptable outcome, and `StepVerifier.verify()` fails if any event was delivered before the error.

The isolation proof is the test the previous post promised: two concurrent live connections, interleaved events, and each client receives exactly its own sequence:

```kotlin
@Test
fun `two concurrent patients receive only their own events`() {
    val patientOne = sseClient(baseUrl(), patientId = PATIENT_ONE)
    val patientTwo = sseClient(baseUrl(), patientId = PATIENT_TWO)

    val oneEvents = patientOne.take(2).map { it.id()!!.toLong() }
    val twoEvents = patientTwo.take(2).map { it.id()!!.toLong() }

    broadcaster.broadcast(StatusEvent(PATIENT_ONE, prescriptionOne, 1, "APPROVED"))
    broadcaster.broadcast(StatusEvent(PATIENT_TWO, prescriptionTwo, 1, "APPROVED"))
    broadcaster.broadcast(StatusEvent(PATIENT_ONE, prescriptionOne, 2, "PACKAGING"))
    broadcaster.broadcast(StatusEvent(PATIENT_TWO, prescriptionTwo, 2, "PACKAGING"))

    StepVerifier.create(oneEvents).expectNext(1L, 2L).verifyComplete()
    StepVerifier.create(twoEvents).expectNext(1L, 2L).verifyComplete()
}
```

The assertion that makes this test meaningful is the interleave: both connections are open before the first broadcast, and events for both patients alternate. A misrouting bug has a real chance to fire — if the broadcaster were keyed wrong or the emitters were shared, patient one would observe patient two's events and the `expectNext(1L, 2L)` on patient one's stream would fail on the very first event. This is the test that converts "isolation is structural, not a filter" from a design sentence into evidence.

## The End-To-End Test: One Proves The Whole Pipeline

Everything so far drove the projection directly, which is correct for speed and determinism — but the challenge's realtime claim is the full path: pharmacist action commits to PostgreSQL, the outbox relay publishes, the consumer applies to the projection, and the SSE connection delivers. Exactly one test should walk that path end to end, with a RabbitMQ Testcontainer in the picture:

```kotlin
@Testcontainers
@SpringBootTest(webEnvironment = RANDOM_PORT)
class PrescriptionRealtimeEndToEndTest {

    companion object {
        @JvmStatic
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
        @JvmStatic
        @Container
        val rabbit: RabbitMQContainer = RabbitMQContainer("rabbitmq:3.13-management")
    }

    @Test
    fun `pharmacist approval reaches the patient stream`() {
        val stream = sseClient(baseUrl()).map { it.id()!!.toLong() }

        // REST: submit, then the pharmacist approves
        val prescriptionId = restClient.post().uri("/prescriptions").body(submission()).retrieve().body<String>()!!
        restClient.patch().uri("/prescriptions/$prescriptionId/approve").retrieve()

        StepVerifier.create(stream)
            .expectNextCount(2)   // SUBMITTED from submit, APPROVED from approval
            .thenCancel()
            .verify()
    }
}
```

What this test proves that the projection-level tests cannot: the projection consumer actually ran, the RabbitMQ topology actually routed the message, and the status the patient sees is the status the pharmacist produced — not a row a test helper wrote. One such test is enough; every repetition of it re-runs the slowest parts of the suite for the same evidence. Everything else stays at projection level, where reconnect, ordering, and isolation can be asserted without racing a broker.

## Flakiness Discipline: What The SSE Tests Need To Stay Honest

Realtime tests have two classic flakiness sources, and the design of the tests above is what keeps them honest:

- **The projection consumer is asynchronous.** After `broadcast` (or after an approval REST call in the end-to-end test), the event may not be in the projection yet — the tests that read the projection before subscribing handle this by subscribing first and letting the stream drive itself. Where a test must wait for the consumer, use Awaitility (`await().atMost(5.seconds)`) and never a fixed sleep. A fixed sleep is a test that is flaky by construction: too short in CI, too long locally.
- **`take(n)` with a live stream can hang.** Every `take(n)` must eventually be satisfied by the test's own broadcasts, so the test controls its own termination. If a broadcast is lost, `collectList().block()` times out instead of hanging forever — set an explicit `block(Duration.ofSeconds(10))` and the failure message becomes part of the evidence.

One more thing the tests should *not* assert, because it is a client concern: the browser's automatic reconnection, `retry:` handling, and `EventSource` re-establishment are browser behaviors, not server behaviors. The server's contract is the replay endpoint; the client's behavior is covered by the browser. Testing the wire contract with `WebClient` and leaving browser reconnection to the browser is the correct division, and it is worth one sentence in the interview.

## Pitfalls Interviewers Probe

- **"Can't Postman verify the SSE?"** — Postman proves a stream exists while it is open. It cannot control `Last-Event-ID` on a scripted reconnect, cannot run two patients concurrently, and cannot assert a 50-event sequence. The correctness properties are stateful across time; only a real client in a real test can assert them.
- **"How do you test the server restart case?"** — By composition: the reconnect test proves the tail replays from the projection with `Last-Event-ID`, and the fresh-connection test proves the projection holds full history. A restarted server is, from the client's view, just a reconnect to a store that survived.
- **"Why does the isolation test interleave the broadcasts?"** — Because a misrouting bug only fires when both connections are live at the moment of the broadcast. Serialized events could pass with a shared emitter list; interleaved events fail it on the first wrong delivery.
- **"What exactly does `containsExactly([1, 2, 3])` prove?"** — In order, no gaps, no duplicates, and no replay of the boundary event. It is one assertion with four properties.
- **"Why is the end-to-end test only run once?"** — It re-proves the pipeline, and each run re-pays the cost of two containers and a broker race. Everything about SSE correctness lives in the projection-level tests; the end-to-end test proves the wire exists.
- **"Do you test the heartbeat?"** — The heartbeat is a keep-alive for proxies, not a correctness mechanism. If it matters, assert that a comment line arrives on schedule with a raw line reader; most challenge scope should not spend a test on it.
- **"How do you avoid the tests timing out forever?"** — Every stream is terminated by the test's own `take(n)`, and every `block()` has an explicit timeout. A lost event fails fast with a clear message instead of hanging the suite.

## Kotlin And Spring Recap

- The client is Spring's `WebClient` (`bodyToFlux(ServerSentEvent::class.java)`), with `X-Patient-Id` and `Last-Event-ID` as per-connection parameters.
- The server is `@SpringBootTest(webEnvironment = RANDOM_PORT)` with the broadcaster injected — projection inserts alone do not fire emitters, so the isolation test needs `broadcaster.broadcast(...)` directly.
- Reconnect coverage is three tests: resume after a gap, fresh snapshot, and join-during-burst (via `doOnSubscribe` to interleave the broadcasts with the subscribe).
- Sequence assertions use `containsExactly` / `isEqualTo(range)` on event IDs; negative tests cover a late delayed replay and a duplicate broadcast.
- Authorization is two tests — connect path and replay path — both asserting `WebClientResponseException.Forbidden` and zero events via `StepVerifier`.
- The isolation test opens two connections, interleaves broadcasts, and asserts each client sees exactly its own sequence.
- Exactly one end-to-end test includes a RabbitMQ Testcontainer and walks REST action → outbox → projection → stream.

## Interview Review Checklist

- What can Postman prove about SSE, and what can it never prove? Where does a real client take over?
- Walk the three integration levels and say which test lives at each.
- What does the reconnect test assert, and why does broadcasting while disconnected prove the projection is the source?
- How is the server-restart row proven without restarting the context?
- Which one-line assertion covers order, gaps, duplicates, and boundary replay?
- Why is the delayed-replay negative test important, and where does the rejection happen?
- How are the two authorization entry points tested, and why is the replay-path test the more dangerous one?
- Why must the isolation test interleave broadcasts while both connections are open?
- What does the single end-to-end test prove that projection-level tests cannot?
- How do you keep these tests non-flaky: termination, timeouts, and async consumer waits?

## Interview Takeaway

The realtime claim reduces to six tests: resume after a gap, fresh snapshot, join-during-burst, ordered-with-no-gaps sequences, two entry-point authorization denials, and interleaved two-patient isolation — plus one end-to-end run through the real broker. Every crash window from the previous post is now an assertion, and every assertion fails loudly if the implementation regresses to "the statuses looked live." That is the sentence to take into the walkthrough: the demo shows the stream, and the tests show that the stream is as trustworthy as the GET it enhances. The next post in the series decides how much of this fits into two hours versus five.
