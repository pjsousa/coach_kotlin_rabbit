# RabbitMQ Operational Testing

The last post ended with the sentence this whole series has been working toward: a design that cannot be tested is a design that has not been finished. Every claim made so far is a claim about broker behavior under failure — confirms, redelivery, retry bounces, dead-letter routing — and none of it can be proven by reading code. This post is where the series' claims become evidence: a Testcontainers broker, real listeners, real channels, and tests that deliberately crash workers inside the exact windows the design papers over.

The through-line stays the same as every post: the broker records evidence, the application makes policy, and the test's job is to make both visible. Integration tests for RabbitMQ are not a slower way to run the happy path — they are the only way to run the failure paths at all, because a mock cannot crash.

## What A Mock Can Prove And What It Cannot

Start with the boundary, because it is the interview question hidden inside this post: *which broker behaviors cannot be proven with mocks?* A mock of `RabbitTemplate` proves that your code calls `convertAndSend` with the exchange, routing key, and payload you expect. That is a unit test of your publishing code, and it is genuinely useful for the relay logic — asserting the routing key is `packaging.request`, not `packaging.requests`. What a mock cannot do is simulate the broker's *response* to that publish, because the response is the whole point:

| Claim from this series | Mock can prove | Needs a real broker |
| --- | --- | --- |
| Relay publishes to the right exchange and key | Yes | — |
| Message survives a crash before the ack | No — nothing redelivers | Yes |
| `basicNack(requeue=false)` routes into the retry topology | No — nothing routes | Yes |
| TTL expiry bounces the message back to the work queue | No — there is no timer | Yes |
| `x-death` accumulates the attempt count | No — the broker writes it | Yes |
| Exhausted budget lands in the DLQ with payload and headers | No | Yes |
| Prefetch bounds unacknowledged deliveries | No | Yes |
| Publisher confirms arrive after broker acceptance | No | Yes |

The rule of thumb: mocks prove *your code does the right thing with the API*; integration tests prove *the broker honors the contract the design depends on*. The series' claims are almost all in the second column — which is why a take-home with a `@MockBean RabbitTemplate` and zero Testcontainers tests is a take-home that asserts nothing it actually argues.

One more distinction earns credit in a code review: what can be tested *without* Spring at all. The listener's decision tree from the retries post — classify the exception, count `x-death`, choose ack versus nack versus DLQ-publish — is pure logic over a `Message` object's headers. A plain unit test can feed it a synthetic message with crafted `x-death` headers and assert the decision. That test is fast, deterministic, and mock-free, and it is the correct place for the decision *logic*. The integration test then proves the broker actually *produces* the `x-death` evidence that logic consumes. Unit-test the policy, integration-test the evidence — never conflate the two.

## The Testcontainers Broker

The setup mirrors the PostgreSQL series: the same Testcontainers discipline, the same version-pinning rule, the same cost-benefit story. For RabbitMQ the container is smaller than the database one, starts faster, and needs no migrations — there is no excuse to skip it.

```kotlin
@Testcontainers
@SpringBootTest
class PackagingFailureIntegrationTest {

    companion object {
        @JvmStatic
        @Container
        val rabbit: RabbitMQContainer =
            RabbitMQContainer("rabbitmq:3.13-management")
                .withStartupTimeout(Duration.ofMinutes(2))
    }

    @Autowired lateinit var template: RabbitTemplate
    @Autowired lateinit var admin: RabbitAdmin

    @BeforeEach fun drainQueues() {
        listOf("packaging.requests", "packaging.requests.retry", "packaging.dlq")
            .forEach { queue -> template.receive(queue, 0) }
    }
}
```

Three decisions in that snippet matter more than the imports:

- **The image is pinned and matches Docker Compose.** The same rule as the PostgreSQL post: the test must prove behavior on the broker the challenge ships. A `rabbitmq:latest` test container running next to a Compose file pinned to 3.13 can pass while the real stack fails — the retries post's quorum-queue-TTL version trap is exactly this class of bug.
- **The `-management` tag is deliberate.** Queue-depth and DLQ assertions read the Management HTTP API, and `rabbitmq:3.13` without the tag ships without it.
- **The test uses the production configuration.** The topology beans, the `SimpleRabbitListenerContainerFactory`, the real `@RabbitListener` — all of it must be the actual application context. A test that re-declares its own queues and listeners is testing a second, parallel system that does not exist. If the retry TTL makes a test slow, make the TTL a configuration value and override it with `@TestPropertySource` — do not redeclare the topology.

Spring Boot 3.1's `@ServiceConnection` can auto-configure a `RabbitMQContainer` from `testcontainers-rabbitmq`, but the explicit companion-object container above keeps the version-pin rule visible and works on older Spring versions. Either is defensible; the invisible part that must never change is that the container replaces the *broker*, not the *context*.

## Topology Assertions: The Configuration Is The Contract

The first integration tests are not about messages at all. The topology post declared that queues are durable, that the retry queue has no consumers, that the DLQ exists for inspection only. Those are operational facts the broker enforces, and each one has a direct assertion:

```kotlin
@Test
fun `retry queue exists with no consumers`() {
    val queueInfo = admin.getQueueInfo("packaging.requests.retry")!!
    assertThat(queueInfo.messageCount).isZero()
    assertThat(queueInfo.consumerCount).isZero()
}

@Test
fun `rejected work message is routed to the retry queue`() {
    template.convertAndSend(PHARMACY_WORK_EXCHANGE, "packaging.request", validRequestJson())
    await().atMost(Duration.ofSeconds(5)).untilAsserted {
        val retry = admin.getQueueInfo("packaging.requests.retry")!!
        assertThat(retry.messageCount).isEqualTo(1)
    }
}
```

The second test is the pattern to replicate: it proves routing by *behavior*, not by declaration. `RabbitAdmin.getQueueInfo` can tell you a queue exists, but only a message that actually arrives there proves the binding, the dead-letter exchange, and the routing key are all right. That is the assertion that catches the classic bug where the work queue dead-letters to the retry exchange with the wrong routing key — everything declares successfully and nothing ever moves.

Two topology facts deserve dedicated tests because they are silent when wrong. First, the retry queue must stay consumerless in the test just as in production — a test that attaches a listener to `packaging.requests.retry` defeats the TTL and passes for the wrong reason. Second, DLQ assertions run *after* the retry budget is exhausted, and the test must assert the work queue drained as part of the same test, or the topology can leave copies behind that the next test inherits. The `drainQueues` cleanup above exists because queue state is shared state.

## Redelivery And The Commit-Then-Ack Window

The acknowledgement post's central claim was: a crash after the effect commits but before the ack produces a redelivery — a duplicate, never a loss. That is a crash *window*, and the test that proves it must pause the listener inside the window. The mechanism is a latch:

```kotlin
@Test
fun `crash after effect before ack redelivers the message`() {
    val effects = AtomicInteger(0)
    val crashBarrier = CountDownLatch(1)
    val deliveries = AtomicInteger(0)

    startCrashingListener(
        effect = { effects.incrementAndGet() },
        crashBeforeAck = {
            if (deliveries.incrementAndGet() == 1) {
                crashBarrier.countDown()   // effect is done, ack is not sent
                throw SimulatedCrash()     // channel dies, message is requeued
            }
        },
    )

    template.convertAndSend(PHARMACY_WORK_EXCHANGE, "packaging.request", validRequestJson())
    crashBarrier.await()
    await().atMost(Duration.ofSeconds(10)).untilAsserted {
        assertThat(effects.get()).isEqualTo(2)
    }
}
```

Run the assertions against the real listener, not a test double: the point is that the broker requeues the unacknowledged delivery when the channel dies, delivers it again with a fresh delivery tag, and the second invocation sees `redelivered = true` on the properties. Two effects, two deliveries, one message — that is the at-least-once contract made observable.

The richer version is the one that pairs the window with the inbox post's defense: crash after the effect, redeliver, and assert the *second* delivery does nothing because the inbox claim fails. That single test proves the two-post claim the whole series rests on — the commit-then-ack window costs nothing once the inbox is present. It is the test to show in a walkthrough.

```kotlin
@Test
fun `redelivery after commit-then-ack crash is neutralized by the inbox`() {
    val effects = AtomicInteger(0)
    // ...same latch machinery...
    await().atMost(Duration.ofSeconds(10)).untilAsserted {
        assertThat(effects.get()).isEqualTo(1)            // effect happened once
        assertThat(inboxCount("status-projection", eventId)).isEqualTo(1)
    }
}
```

Note what this test is *not*: it does not assert the broker is exactly-once. It asserts the broker delivers twice and the database makes the second delivery a no-op. Keep the phrasing precise in the test name and in the interview — the test is the evidence for the "at-least-once delivery, at-most-once effect" sentence, not for anything called exactly-once.

## Duplicate Publication And The Relay

The outbox post claimed the relay can republish the same outbox row if it crashes between the broker's confirm and the `published_at` write. A real test cannot crash the relay at a precise instruction boundary inside an integration test — and it does not need to. The relay's crash window is an *application* claim, so the honest split is:

- **Unit-test the relay's window**: a mocked confirm listener, invoked after the publish but before the row update, asserts the relay proceeds to republish the row on restart. That is application logic and belongs in a fast unit test.
- **Integration-test the consequence**: publish two copies of the same event ID to the work queue — exactly what a relay crash produces — and assert the consumer's effect runs once. The second copy is indistinguishable from a duplicate publication, and the test proves the inbox neutralizes it on a real broker:

```kotlin
@Test
fun `duplicate event id from relay republish applies the effect once`() {
    val event = packagingRequestEvent(prescriptionId = "rx-77", eventId = SAME_UUID)
    template.convertAndSend(PHARMACY_WORK_EXCHANGE, "packaging.request", event)
    template.convertAndSend(PHARMACY_WORK_EXCHANGE, "packaging.request", event) // republish

    await().atMost(Duration.ofSeconds(10)).untilAsserted {
        assertThat(packagingRunCount("rx-77")).isEqualTo(1)
    }
}
```

This is the test that proves the event ID is the contract: if a replay regenerated the ID, this test would fail with two packaging runs. Interviewers who probe the idempotency post by asking "what breaks if the replay regenerates IDs" are asking for this test.

## Retry Limits And Dead Letters

The retries post's arithmetic — `MAX_RETRIES = 3` means four total deliveries, and the budget is read from `x-death` — is the most assertable claim in the series, and the test that proves it is the centerpiece of the suite:

```kotlin
@Test
fun `retryable failure bounces the budget then dead-letters`() {
    val deliveries = AtomicInteger(0)
    startListenerThatAlwaysThrowsRetryable { deliveries.incrementAndGet() }

    template.convertAndSend(PHARMACY_WORK_EXCHANGE, "packaging.request", validRequestJson())

    await().atMost(Duration.ofSeconds(15)).untilAsserted {
        assertThat(deliveries.get()).isEqualTo(4)   // original + 3 retries
        assertThat(admin.getQueueInfo("packaging.requests")!!.messageCount).isZero()
        assertThat(admin.getQueueInfo("packaging.dlq")!!.messageCount).isEqualTo(1)
    }
}
```

Then inspect the poison message's evidence — the broker's own accounting:

```kotlin
@Test
fun `poison message carries the full x-death history`() {
    // ...run the budget to exhaustion as above, then consume from the DLQ...
    val dlqMessage = template.receive("packaging.dlq", 500)
    val deaths = dlqMessage.messageProperties.headers["x-death"] as List<Map<String, Any?>>
    val retryEntry = deaths.first { it["queue"] == "packaging.requests.retry" }
    assertThat(retryEntry["count"] as Int).isEqualTo(3)
    assertThat(retryEntry["reason"]).isEqualTo("rejected")
    assertThat(String(dlqMessage.body)).isEqualTo(validRequestJson())
}
```

Three assertions, three claims proven: the retry count lives in `x-death` and not in a counter that dies with the worker; the reason is `rejected`, proving the bounce came from the consumer's nack and not from TTL expiry; and the payload survives the journey intact. Add the companion test for the classification rule: a permanently failing payload — invalid JSON — must reach the DLQ with *zero* retries and no retry-queue entry in `x-death`. That test is what makes `classify(ex)` from the retries post a proven claim instead of a design wish.

Two practical notes keep these tests fast and honest. The retry TTL must be overridable — pin `packaging.retry.ttl-ms=500` via `@TestPropertySource` so a test does not sit through a 30-second parking-lot wait, while production keeps the real value. And the tests must be resilient to timing, not dependent on it: Awaitility polling queue depth is the right tool, and a test that sleeps a fixed 31 seconds to "let the TTL fire" is a test that will flake on a loaded CI machine. The broker's timing is the system under test; polling is the only honest way to observe it.

## DLQ Forensics And Replay

The replay claim from the retries post — consuming from the DLQ and republishing is a *new publish*, and the inbox is what makes its duplicates harmless — is one integration test:

```kotlin
@Test
fun `replay of a dead-lettered message is processed once`() {
    // 1. Run a retryable failure to exhaustion; the message is now in packaging.dlq.
    // 2. Replay: consume from the DLQ and republish the original payload.
    val original = template.receive("packaging.dlq", 500)
    template.convertAndSend(PHARMACY_WORK_EXCHANGE, "packaging.request", original.body)

    await().atMost(Duration.ofSeconds(15)).untilAsserted {
        assertThat(packagingRunCount("rx-88")).isEqualTo(1)  // replay ran once, duplicate-free
        assertThat(admin.getQueueInfo("packaging.dlq")!!.messageCount).isZero()
    }
}
```

For the challenge, this test doubles as the replay utility's proof: the replay helper is exercised, and the idempotency layer is exercised a second time from a different direction. What it must not assert is that replay is safe without the inbox — the whole point is that replay is only safe *because* of the inbox. If the walkthrough includes "replay by hand via the Management UI," the test suite has already proven the system tolerates it.

## Observability: The Management API As The Test Oracle

The DLQ-depth alert from the retries post — an alerting rule on the dead-letter queue, because work-queue depth looks fine while poison messages pile up invisibly — is itself an assertable claim, and the Management API is the oracle:

```kotlin
@Test
fun `dlq depth is observable while the work queue stays empty`() {
    // ...run the budget to exhaustion...
    await().atMost(Duration.ofSeconds(15)).untilAsserted {
        val dlq = managementClient.getQueue("/api/queues/%2F/packaging.dlq")
        assertThat(dlq.messages).isEqualTo(1)
        assertThat(managementClient.getQueue("/api/queues/%2F/packaging.requests").messages).isZero()
    }
}
```

Asserting *both* sides is the operational claim itself: the work queue is quiet and healthy while the DLQ quietly grows — which is exactly why DLQ depth, not work-queue depth, is the alert. The same API is what a real operator (or the candidate in a walkthrough) would query, so the test doubles as documentation of the operational interface. Keep this to one test; the Management API is an oracle for assertions, not a second system to test.

## The Two-Hour Scope: Which Tests Earn Their Keep

A 2-5 hour take-home cannot contain this entire post's suite, and pretending otherwise is bad product judgment. The scope decision is itself interview material. Ranked by the value of the claim they prove, for this challenge:

| Priority | Test | Proves | Effort |
| --- | --- | --- | --- |
| 1 | End-to-end happy path through the real broker | Topology, listener wiring, ack flow | Small |
| 2 | Retryable failure → budget exhausted → DLQ | Retry topology, `x-death`, DLQ routing | Medium |
| 3 | Duplicate event ID → effect once | Inbox, the at-least-once pairing | Medium |
| 4 | Permanent failure → DLQ with zero retries | `classify()` | Small |
| 5 | Commit-then-ack crash → redelivery → inbox no-op | The series' core crash-window claim | Medium |
| 6 | Replay, topology forensics, Management-API depth | Operational polish | Stretch |

The first five fit a two-hour slice if the tests share one container and one test class; the sixth is the five-hour version. Cut from the bottom, and declare the cut claims in the README as known-unproven rather than silently claiming them. An interviewer who asks "which broker behavior did you not prove?" should get an honest list, not a defensive one.

Two more scope rules. First, never test the broker itself — nobody needs a test that RabbitMQ can route a message; the tests assert *the application's* topology and listeners against the broker. Second, never fake the broker: no in-memory RabbitMQ substitute honors redelivery and `x-death`, and a fake channel in the unit-test layer only serves the decision-logic tests from the top of this post.

## Pitfalls Interviewers Probe

- **"Why is a `@MockBean RabbitTemplate` not an integration test?"** — A mocked template proves the publish call; it proves nothing about delivery, redelivery, routing, TTL, or `x-death`, because the broker never acts. The series' claims are all in the broker's response column.
- **"What is the version-pin rule, and what breaks without it?"** — The test container's image must match the Compose-pinned broker version. Quorum-queue TTL support, for example, only exists from RabbitMQ 3.12 — a test on `latest` can pass a topology that fails on the shipped broker.
- **"How do you test a crash window without crashing the JVM?"** — A latch pauses the listener between the effect and the ack; throwing from the listener (or stopping the container) closes the channel, and the broker requeues the unacknowledged delivery. The window is simulated, the broker's response is real.
- **"What does an `x-death` assertion add over counting deliveries?"** — Delivery count says *how many times*; `x-death` says *why* (`rejected` versus `expired`), *from which queue*, and that the count survived the broker's own bookkeeping. It is the evidence a production operator would inspect.
- **"Why override the retry TTL in tests?"** — Timers are the flakiest part of broker tests. Making the TTL configurable and pinning it short keeps the suite in seconds; the production value stays in configuration.
- **"Can a unit test with synthetic `x-death` headers replace the DLQ test?"** — No. The unit test proves the decision logic reads headers correctly; the integration test proves the broker writes the headers the logic reads. One tests the policy, the other tests the evidence.
- **"How does the duplicate-publication test work without crashing the relay?"** — The crash window is unit-tested in the relay; the integration test publishes the duplicate the window would produce — two copies of the same event ID — and asserts the effect runs once. The consequence is proven where the crash cannot be simulated.
- **"What is the single most important test in the suite?"** — The retry-budget-then-DLQ test: it exercises the whole failure topology — reject, parking lot, TTL bounce, budget check, dead-letter routing — and asserts the broker's own `x-death` accounting.
- **"How do you keep broker tests from flaking?"** — Pin the image, override timers, drain queues between tests, poll with Awaitility instead of sleeping, and share one container across the suite.
- **"Which behavior did you choose not to test, and why?"** — The honest answer names the priority table and the cut row — replay, or Management-API depth — and states the claim it would have proven.

## Kotlin And Spring Recap

- Mocks prove the application's calls; a Testcontainers broker proves the broker's response. Unit-test the decision policy, integration-test the evidence.
- `RabbitMQContainer("rabbitmq:<version>-management")` pinned to the Compose image, shared via a companion object; the test boots the production context, not a re-declared topology.
- Topology tests assert behavior: messages arrive at the expected queue via the dead-letter chain, and the retry queue stays consumerless.
- Redelivery is proven with a latch: pause between effect and ack, kill the channel, assert the broker delivers again and the inbox makes the second delivery a no-op.
- The relay crash window is unit-tested for the publish-then-mark order; the integration test publishes the duplicate and asserts the effect runs once.
- Retry budgets are proven end to end: four deliveries for `MAX_RETRIES = 3`, `x-death` `count = 3` and `reason = rejected` on the DLQ copy, payload intact.
- Override the retry TTL via `@TestPropertySource`; poll with Awaitility; drain queues between tests; never sleep fixed durations.
- The Management API is the oracle for the DLQ-depth observability claim, and one test proves both the DLQ growth and the quiet work queue.

## Interview Review Checklist

- Which broker behaviors can a mock never prove, and why does the series depend on them?
- What is the version-pin rule for the test container, and which RabbitMQ feature made it a live issue?
- Why must the integration test run the production topology beans instead of re-declaring them?
- How does a latch-based test simulate a crash between the effect and the acknowledgement?
- What exactly does the commit-then-ack redelivery test prove, and what does it deliberately not claim?
- How is the relay's crash window tested when the relay itself cannot be crashed mid-window?
- What three assertions prove the retry budget, and what does `reason = rejected` add over a delivery count?
- Why is the retry TTL overridable in tests, and why do fixed sleeps flake?
- How does the replay test prove the DLQ claim and the inbox claim at the same time?
- Which tests belong in a two-hour slice, which are the five-hour version, and what did you choose to leave unproven?

## Interview Takeaway

The whole series argued a design; this post is where it stopped arguing. A mock can show your code calls the API, but only a real broker can show that a crashed worker's message comes back, that a rejected message waits its TTL and bounces, that `x-death` records the budget, and that a duplicate event ID changes nothing. The suite is deliberately small and deliberately shared — one container, five priority tests, timers overridden, queues drained — because a test suite that cannot finish in seconds is a test suite that will not be run, and a broker test that is not run proves exactly as much as the README sentence that claims it. The next post is the series' showcase: one end-to-end prescription flow that puts the outbox relay, manual acknowledgement, retries, dead letters, inbox, and ordering back into a single walkthrough — with every crash window and the operational evidence from this post ready to defend it.
