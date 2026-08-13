# A02 Backpressure and Prefetch — Code-Along Elective

## Objective

You already saw manual ack and the prefetch knob in R03. This elective makes you stop trusting the knob and start **measuring** it: saturate a slow packaging-style worker, watch unacknowledged messages pin at the prefetch ceiling, and then tune the two levers that actually bound in-flight work — `prefetchCount` and `concurrentConsumers` — until lag, unacked count, and throughput tell a consistent story. One primary objective: choose a prefetch/concurrency pair from measured local evidence and defend it, not from a tutorial default.

## Time box

- Core: 2–2.5 hours
- Optional: 0.5h for a load-driver script that produces the saturation plot as data

## Prerequisites

- R03 (`../rabbit/R03_manual_ack_consumer.md`) — manual ack, delivery tags, prefetch mechanics. You already acknowledged after work; now prove how much work is *in flight*.
- R01 (`../rabbit/R01_topology_scratchpad.md`) — durable queue declarations.
- X01 (`../glue/X01_docker_compose_trio.md`) — Compose trio.
- Showcase position: **during Exercise 3** — this is evidence for Milestone 3 ("measured in-flight work") in `../../pharmacy-fulfillment/exercise_03_production.md`.

## Blog & curriculum links

- Primary: `posts/series-3-rabbitmq/03-ack-prefetch-concurrency.md`
- Secondary: `posts/series-3-rabbitmq/06-operational-testing.md` (prefetch claims need a real broker — see the "mock can prove" table)
- Coach-assessment gap: RabbitMQ concurrency reasoning "strong transferable... tutorial-level" → measured tuning.

## Background & motivation

The three facts R03 drilled in are: prefetch is **per consumer**, concurrency is **consumers per queue**, and the product of the two bounds *uncertain* work — work that is unacknowledged and will be redelivered if the consumer dies. Tutorials then hand you `prefetchCount = 10, concurrentConsumers = 4` and move on. This kata exists because that product is the single most dangerous number in the whole system: set it too high and one slow worker holds a warehouse of jobs hostage after a crash; set it too low and you cap throughput below what the database can absorb. You can't know which regime you're in by reading code — you have to saturate the pipeline and read the broker's own numbers. This elective deliberately ignores idempotency, ordering, and DLQ (A01/A04/A05's jobs) so that **capacity** is the only thing on the table.

## Learning objectives

- Explain and demonstrate the difference between prefetch per consumer and total in-flight work across a queue.
- Saturate a slow worker and read the saturation from the management UI (unacked count pinned at prefetch × consumers) and from a metrics endpoint.
- Tune `prefetchCount` and `concurrentConsumers` against a fixed local workload and produce before/after throughput + lag evidence.
- Show the crash cost of high prefetch: kill a busy consumer and count redeliveries.
- Choose a pair for Ex3's packaging queue and write the 3-line justification.

## Warm-up

Open `posts/series-3-rabbitmq/03-ack-prefetch-concurrency.md` and re-read the "Delivery Is Not Processing" section. Then, in the management UI, look at any queue with an active consumer and locate the `Unacked` column. Now answer on paper: *if prefetch is 10 and there are 4 consumers, how many messages can be unacked at most — and what happens to that many messages if all 4 consumers crash simultaneously?* That number is the blast radius you're about to measure.

## System specification

**Scope in:** one durable work queue (`fulfillment.work`), one consumer app with configurable `prefetchCount` and `concurrentConsumers`, a load driver (a script or loop publishing N messages with a fixed per-message work delay), and a small metrics/log output showing queue depth, unacked, redelivered, and throughput over time.

**Scope out:** broker-side rate limiting, retry/DLQ topology (A01), consumer-side dedupe (A04), ordering guarantees (A05), external load generators.

**Functional requirements:**
- Saturation must be observable: with a work delay longer than publish rate, unacked pins at prefetch × consumers and stays there while `ready` grows.
- Tuning must be evidence-driven: the chosen pair is justified by at least two runs with different settings.
- A consumer crash must redeliver its unacked messages (count them).

**Constraints:** local Docker Compose, pinned broker image, one Spring Boot app, MANUAL ack, no cloud.

## Step-by-step code-along

1. **Do:** Create a load driver that publishes a configurable count of messages to `fulfillment.work` (e.g. 2000). Each message payload carries `jobId` and `workMillis`. Publish as fast as possible in one burst so the queue's `ready` spikes.
   **Run:** `docker compose up -d`; publish 2000 jobs with the consumer **stopped**. **Observe:** management UI — `ready` at 2000, `unacked` at 0. Capture the screenshot/log line. **Decision:** whether the driver is a script hitting a REST publish endpoint or a raw `RabbitTemplate` publisher loop — pick one, note the cost of each (HTTP adds pool contention; raw publisher adds setup).

2. **Do:** Implement a worker listener (ackMode MANUAL) that sleeps `workMillis` (50ms for lab speed) then acks. Start with `prefetchCount = 10, concurrentConsumers = 1`.
   **Run:** drain the 2000. Record elapsed time and peak unacked from the UI while draining. **Observe:** throughput ≈ 20 jobs/sec, unacked peeks near 10 then falls. The unacked peak equals prefetch — not concurrency — because there is one consumer.

3. **Do:** Raise concurrency to 4 while keeping prefetch at 10.
   **Run:** same 2000 jobs, same timing. **Observe:** unacked now peeks near **40** = prefetch × consumers. The `redelivered` counter stays 0 because nothing crashed — yet. Write down the pair and the peak unacked. **Decision:** this is the number that matters for crash blast radius; say out loud what 40 unacked jobs cost if the app dies mid-run.

4. **Do:** Add a metric — either a management-API poll every 2s (queue `messages_unacknowledged`) or a `/metrics` endpoint in the app. Record it to a file with timestamps.
   **Run:** repeat step 3 while the metric runs. **Observe:** you now have lag-vs-time evidence, not a screenshot. The curve is the deliverable.

5. **Do:** The crash experiment. With 2000 jobs and the step-3 settings, start the drain and kill the app (SIGKILL) at ~50% drain. Restart with the same settings.
   **Run:** drain to completion. **Observe:** management UI `redelivered` counter jumps by roughly the unacked count at kill time (up to ~40); every redelivered job has its `redeliver` flag true. The database-side effect was **not** lost — the job was simply not acked. That is at-least-once in action.

6. **Do:** Tune. Run the same 2000-job, 50ms workload with at least two alternative settings (e.g. prefetch 100/concurrency 1 and prefetch 10/concurrency 8) and record throughput and peak unacked for each.
   **Run:** all three configurations, same workload. **Observe:** the table — throughput, peak unacked, elapsed. **Decision:** pick the pair for Ex3's packaging queue and write 3 lines: throughput target, crash blast radius you accept, and why you rejected the others.

7. **Do:** Write one integration test (Testcontainers, per `posts/series-3-rabbitmq/06-operational-testing.md`) that asserts the unacked ceiling: with prefetch 10 and 4 consumers, publish 200 jobs with slow work and assert unacked never exceeds 40 during the drain.
   **Run:** `./gradlew test`. **Observe:** the assertion passes against the real broker — a mock cannot prove this (see the mock-vs-broker table in post 06).

## Try this

**The hostage scenario.** Set prefetch to 200, concurrency to 1. Publish 500 jobs where the *first* job sleeps 60 seconds and the rest take 10ms. Watch the queue: 499 jobs wait behind one slow job because one consumer's prefetch is a hoard. Now set concurrency to 4 with prefetch 5 and repeat: the slow job only blocks 4. This is the interview-grade observation: *prefetch bounds the blast radius of one slow (or one dead) consumer; concurrency bounds the parallelism; high prefetch turns one straggler into a queue-wide stall.*

## Trade-off fork

Pick one pair of levers, implement it, justify in 3–5 lines:

- **Option A — Low prefetch, higher concurrency** (e.g. 5 × 8): small per-consumer hoard, more consumers for parallelism; more channel/consumer overhead and more context switches; redelivery blast radius stays small.
- **Option B — Higher prefetch, lower concurrency** (e.g. 50 × 2): fewer consumers, each pulling a bigger window; higher throughput per consumer, but one slow consumer controls a bigger slice of the queue and crash redelivery is a large burst.

There is no official winner — the right answer is the one justified by your measured table and your stated blast-radius tolerance. Name the lost benefits (A loses per-consumer efficiency; B loses graceful degradation under stragglers).

## Hints

- **Hint 1:** Spring's `SimpleRabbitListenerContainerFactory` exposes both knobs; `@RabbitListener` with `ackMode = "MANUAL"` and `concurrency = "4"` on the annotation controls the container level. The management API endpoint `/api/queues/%2F/{queue}` returns `messages_unacknowledged` — curl it on a timer if you don't want the app metric.
- **Hint 2:** If unacked never reaches prefetch × consumers in your runs, your publish rate is too low — your worker finishes faster than the driver feeds. Increase message count or workMillis until the ceiling *binds*. A ceiling that never binds is evidence of nothing.

## Checkpoint / success criteria

Done when:

- A metrics file shows a saturation run where unacked pins at prefetch × consumers while `ready` grows.
- A crash/restart run shows `redelivered` jumping by ≈ the unacked peak, and every redelivered job completes.
- A comparison table of ≥3 settings (throughput, peak unacked, elapsed) exists for one fixed workload.
- A justification paragraph names the chosen pair, the blast radius it implies, and what was rejected.
- The Testcontainers unacked-ceiling test passes against the real broker.

## Bottleneck & reflection questions

1. Prefetch × consumers bounds *uncertain work*. In Ex3, which consumer roles deserve the biggest window — packaging work or the status projection — and why? (Hint: `../../pharmacy-fulfillment/exercise_03_production.md` Milestone 3 says they differ.)
2. You killed the app mid-drain and everything was redelivered. What changed at the *database* level, and what does that imply about how the worker must treat a job with `redeliver = true`?
3. If the Postgres pool (Hikari) has 10 connections and packaging workers run with concurrency 20, which resource is the real bottleneck — and which knob is now meaningless?
4. A slow *database query* inside the worker increases processing time. How does that interact with prefetch, and what metric would tell you it's the DB, not the broker?
5. In a 2-hour submission, which part of this elective would you cut and still defend your prefetch choice in an interview?

## Handoff

- Next: A05 (`A05_ordering_keys.md`) — capacity and ordering fight over the same queues. Or A08 (`A08_connection_channel_lifecycle.md`) — the transport under all these consumers.
- Related showcase work: `../../pharmacy-fulfillment/exercise_03_production.md` **Milestone 3** — your tuning table is its "measured in-flight work" evidence.
- Interview line: *"I bound in-flight work with prefetch per consumer times consumer count, and I tune it from a saturation run: slow jobs pin unacked at that ceiling, and the ceiling is also my crash blast radius — high prefetch turns one straggler into a queue-wide stall, so I picked a low prefetch with enough consumers to hit the target throughput."*

## Optional stretch

Build the load driver as a proper Kotlin script that publishes batches while a background poller records `ready`, `unacked`, and `redelivered` every second into a CSV. Produce one line per configuration run. You now have the beginning of the load section of Ex3's final measurement report (`../../pharmacy-fulfillment/exercise_03_production.md` Milestone 10).
