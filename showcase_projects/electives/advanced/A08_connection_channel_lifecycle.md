# A08 Connection and Channel Lifecycle — Code-Along Elective

## Objective

You already managed acknowledgements and delivery tags on a channel in R03. This elective drops to the transport layer underneath: the **shared connection**, the **channel-per-thread rule**, and **reconnect** — what actually happens to consumers, publishers, and your code when the broker restarts. One primary objective: demonstrate, with management-UI evidence, that one application can share a single broker connection across many channels, that channels are never shared across threads, and that the app survives a full broker restart without losing or corrupting work.

## Time box

- Core: 2 hours
- Optional: 0.5h for the raw-AMQP version (no Spring) that proves you understand the lifecycle without framework help

## Prerequisites

- R03 (`../rabbit/R03_manual_ack_consumer.md`) — you acked on the channel; now understand why that channel was safe to ack on.
- R01 (`../rabbit/R01_topology_scratchpad.md`) — declarations, durability.
- X01 (`../glue/X01_docker_compose_trio.md`) — Compose trio; you'll be restarting the broker container.
- Showcase position: **during Exercise 3** — this is the failure-mode foundation for `../../pharmacy-fulfillment/exercise_03_production.md` Milestone 3 and the broker-restart test in Milestone 9.

## Blog & curriculum links

- Primary: `posts/series-3-rabbitmq/06-operational-testing.md` (what a real broker can prove)
- Secondary: `posts/series-3-rabbitmq/03-ack-prefetch-concurrency.md` (ack on the right channel) and `posts/series-3-rabbitmq/01-amqp-topology.md` (declaration lifecycle)
- Coach-assessment gap: RabbitMQ "operational failure modes" → demonstrated reconnect and channel hygiene.

## Background & motivation

The AMQP connection model is the least-visible part of the stack and the first thing that bites when the broker sneezes: a **connection** is a TCP session owned by the process; a **channel** is a lightweight multiplexed conversation over that connection, *not thread-safe*, and it becomes **dead the moment the connection dies**. Spring hides most of this — which is exactly why the lifecycle deserves its own kata: when the container factory silently creates a second connection, or a reconnect re-declares queues in the wrong order, or a `@RabbitListener` acks on a stale channel, the symptom is "works on my machine, loses messages in the drill." This elective makes the lifecycle *visible*: you will see the connection count in the management UI, watch channels come and go, and design a raw reconnect path that behaves the way Spring's does — because interviewers ask exactly one question here: *what happens to your consumers and your unacked messages when the broker restarts?*

## Learning objectives

- Explain and demonstrate the connection/channel hierarchy and the channel-not-thread-safe rule with a small failure.
- Configure Spring to share one `ConnectionFactory`-owned connection across consumers and publishers — and prove it in the UI (one connection, N channels).
- Distinguish Spring AMQP auto-recovery (what the framework re-creates) from your code's job (what must be re-declared or re-subscribed).
- Show a broker restart: consumers resubscribe, publishers resume, unacked messages redeliver, topology is re-declared — with evidence at each step.
- Decide when auto-recovery on vs off is the right call and defend the trade-off.

## Warm-up

Open `posts/series-3-rabbitmq/03-ack-prefetch-concurrency.md` and re-read the delivery/ack section, then the connection diagram in `posts/series-3-rabbitmq/01-amqp-topology.md`. In the management UI, on the Connections tab, count the connections your current R03 app holds. Then answer: *if the broker restarts, which of these do I expect to survive — the TCP connection, the channels, the consumers, the unacked messages?* Write your answer; the restart experiment at step 4 will grade it.

## System specification

**Scope in:** one Spring Boot app with a publisher (R02-style) and a consumer (R03-style, MANUAL ack); a `ConnectionFactory` whose `publisherConnectionFactory`/consumer factory usage is explicit; a health/ops endpoint or log lines that surface connection and channel counts; a restart drill that stops and starts the broker container.

**Scope out:** raw-AMQP channel pooling implementations beyond the exercise's explanation (that's the fork), clustering/ha, TLS, quorum-queue leader election (one local node), anything cloud.

**Functional requirements:**
- The app uses exactly **one** broker connection for both consumers and publishers (evidence: management UI).
- Channels are created per logical conversation (per listener, per publish thread) and never shared across threads — proven by a deliberately broken variant in Try this.
- After a full broker restart, consumers resubscribe, topology is re-declared, publishers can publish again, and unacked messages are redelivered — all without app restart.
- The drill is repeatable and leaves evidence (log lines + UI state).

**Constraints:** local Docker Compose, pinned broker image, one Spring Boot app, Kotlin.

## Step-by-step code-along

1. **Do:** Make the lifecycle observable. Expose a `ConnectionFactory` bean explicitly (don't rely on autoconfiguration defaults you can't see), and log: connection count, channel count per connection, and consumer count — either from `ConnectionFactory` stats or the management API (`/api/connections`, `/api/channels`). Add the management API user/password to the app config so the app can poll it if you go that route.
   **Run:** start the app with one consumer and publish a few messages. **Observe:** the Connections tab shows **one** connection (from the app) with several channels — one per `@RabbitListener` container, one per publish (or a reused publisher channel). Log lines match the UI. **Decision:** poll the management API vs use `ConnectionFactory` listeners (`addConnectionListener`) — pick the one that will also serve Ex3's Milestone 6 metrics (nudge: listeners keep it in-process and don't need HTTP auth).

2. **Do:** Force the single-connection question. Your app may already be opening two connections if Spring created a *publisher* connection factory separately (e.g. `setPublisherConnectionFactory` or two `ConnectionFactory` beans). Make the config explicit: one shared `CachingConnectionFactory`, used by both the `RabbitTemplate` and the listener container factory.
   **Run:** restart the app; check the Connections tab. **Observe:** one app connection. If you see two, find the second bean and delete it — then write the note about *why* you might legitimately want two (publisher vs consumer isolation is a real operational choice, but it must be a choice, not an accident). **Decision:** shared vs split publisher/consumer connection — document which you picked and the one-sentence cost of the other.

3. **Do:** The channel-not-thread-safe proof. Add a test-only path that publishes from two threads using one explicitly obtained channel (`connection.createChannel()` stored in a field), no synchronization. Run it under load. Expect intermittent exceptions (`AMQPChannelClosedException`, frame-order corruption) or wrong acknowledgements.
   **Run:** the same workload through the *correct* path: one channel per publish via `RabbitTemplate` (which checks out a channel per operation). **Observe:** the broken variant fails nondeterministically; the correct one never does. Record one failing trace. **Decision:** channel per thread vs a bounded channel pool — you just demonstrated why the *pool* (Spring's `CachingConnectionFactory` caches channels) exists; write the sentence that explains what pooling changes and what it doesn't (it reuses channels across calls but still never shares one across threads concurrently).

4. **Do:** The restart drill. With the app running and a consumer mid-work, restart the broker container: `docker compose restart rabbit`.
   **Run:** watch the app logs and the UI during and after restart. **Observe:** in order — connection failure logged, unacked messages disappear from the old channel's `unacked` count, the broker comes back, the app reconnects (new connection in the UI), queues are re-declared (verify in the UI they still exist — durable declarations from R01 survive), consumers resubscribe, and the previously unacked messages are **redelivered** (redelivered counter rises) and processed. Nothing was lost — that's at-least-once in the transport. **Decision:** Spring AMQP's auto-recovery (default on) does the reconnect and consumer resubscription. What did *your code* have to do? (Nothing — that's the point to note: the framework recovers connections, but the *business* recovery is your ack-after-commit + inbox, from R03/A04.)

5. **Do:** The re-declaration question. Delete a queue manually from the UI *while the app is running* (don't touch the consumer yet). Wait for the app to re-declare topology (declarations run on reconnect and on container start). Check the queue is back with the same bindings.
   **Run:** repeat while the consumer is actively receiving. **Observe:** Spring's `RabbitAdmin` re-declares on connection recovery; bindings restored. **Decision:** what if a *new* binding was added to the code but the app never restarted? (The answer — re-declaration happens on recovery events, not on code deploy — is a real ops bug class; write it down as an Ex3 runbook note.)

6. **Do:** The publisher side. Publish a burst while the broker is down; the app should queue the publish attempt (or fail fast per your template's `setMandatory`/confirm config from R02/A03). Confirm the outbox relay (A03) is the durable backstop and that direct `RabbitTemplate` publishes during the outage fail loudly, not silently.
   **Run:** broker down → publish → broker up. **Observe:** direct publishes throw/log during the outage; outbox rows stay pending and drain after reconnect (A03 evidence). **Decision:** this is the R07/A03 "why outbox" argument at the transport layer — write the 2-line summary in the drill notes.

7. **Do:** Wire into Ex3 (`../../pharmacy-fulfillment/exercise_03_production.md` Milestone 3 and 9): the app's connection config is now explicit and documented; the restart drill becomes the Milestone 9 broker-restart test (Testcontainers or Compose-based, `posts/series-3-rabbitmq/06-operational-testing.md` style).
   **Run:** Ex3's existing tests + a scripted version of the drill (stop container, start container, assert redelivery). **Observe:** the drill passes repeatably; the evidence file records connection/channel counts before and after.

## Try this

**The auto-recovery-off night.** Set `spring.rabbitmq.listener.simple.auto-startup` and the connection factory's `setAutomaticRecoveryEnabled(false)` (raw factory) — or just set `spring.rabbitmq.listener.simple.missing-queues-fatal=false` and watch the difference — and repeat the restart drill. Observe: consumers do **not** resubscribe, publishes fail after reconnect, and the app silently stops processing until restart. Then turn recovery back on and re-run. The observation to say aloud: *auto-recovery restores the transport; it does not restore business correctness — that's the inbox's job — but without it, a broker blip is a permanent outage.*

## Trade-off fork

Pick one pair, implement it, justify in 3–5 lines:

- **One shared connection vs split publisher/consumer connections:** one connection is simple, uses fewer sockets, and matches this kata's scope — but a consumer-heavy channel storm can contend with publishing, and a single connection's failure hits everything. Split connections isolate publisher and consumer lifecycles (a publish-only connection can die without killing consumers) at the cost of a second connection to monitor and the failure-mode complexity that comes with it. Name what you lost by picking one.
- **Auto-recovery on vs off:** on (default) gives you reconnect, re-declaration, and consumer resubscription for free — but hides the failure from your observability, and its retry behavior can mask the *reason* the connection dropped. Off makes every reconnect a loud, handled event in your code — but you now own reconnect, re-declaration, and resubscription, and a bug there is a permanent outage. There is no official winner; the interview answer is the trade-off, stated with the patient in mind.

## Hints

- **Hint 1:** The management API is your evidence source: `/api/connections` lists connections and their channels (`channels` count per connection), `/api/queues` shows `messages_unacknowledged` and `messages_redelivered`/`messages_redelivered_details`. Curl them on a timer for the drill log.
- **Hint 2:** If the restart drill "loses" messages, check the *durable* flags first: a non-durable queue and non-persistent messages are gone on broker restart by design (R01's table). The drill proves the *delivery* path, not durability — durability is declared in the topology, and the inbox (A04) is what makes redelivery safe. Also: `CachingConnectionFactory` caches channels; after a reconnect, stale cached channels are invalidated — if you see `AlreadyClosedException` after restart, that's the stale-cache signal to look for a manually held channel.

## Checkpoint / success criteria

Done when:

- Management UI shows exactly one app connection with N channels, and log lines agree.
- The broken shared-channel experiment produced at least one recorded failure; the correct path ran clean.
- Restart drill: reconnect, re-declaration, resubscription, and redelivery all evidenced (UI + logs), zero lost work.
- Manual queue deletion is recovered by re-declaration; bindings restored.
- The split-connection and auto-recovery choices are each justified in 3–5 lines in the notes.
- Ex3 broker-restart test passes (or the drill is documented as the manual equivalent).

## Bottleneck & reflection questions

1. Auto-recovery restored your consumers. What did it *not* restore — and why is that the same question the interviewer asks about at-least-once delivery? (Hint: recovery is transport, correctness is your transaction.)
2. Your app re-declares queues on reconnect. What if the broker came back with a *different* topology (e.g. an operator deleted a binding)? Where does the app's declaration-and-binding list live, and how do you keep it truthful?
3. A channel is not thread-safe, yet Spring's template shares a cached channel across calls. What exactly is Spring doing to make that safe, and where does that safety stop being true? (This is the interview-level version of the rule.)
4. The drill showed redelivery after broker restart. Which consumer-side mechanism (R03 ack-after-commit, A04 inbox, A05 sequence) turns that redelivery from a risk into a non-event?
5. In Ex3's architecture record (Milestone 10), how would you describe the connection model in one paragraph that a non-messaging engineer could follow — and why does that readability matter for the Product Engineer role?

## Handoff

- Next: A09 (`A09_postgres_under_contention.md`) — the transport is reliable; the database under load is the next bottleneck. Or A13 (`A13_chaos_drill_script.md`) — this drill is its first script.
- Related showcase work: `../../pharmacy-fulfillment/exercise_03_production.md` **Milestones 3 and 9** — your drill and connection evidence feed the broker-restart test and the crash matrix.
- Interview line: *"One process shares one broker connection; channels are lightweight conversations that are never shared across threads, and Spring's channel caching is what makes the template safe. On broker restart, auto-recovery reconnects, re-declares the topology, and resubscribes consumers, while unacked messages are redelivered — so recovery restores the transport, and my ack-after-commit plus inbox makes the redelivery safe."*

## Optional stretch

Implement the raw-AMQP version without Spring: a single `ConnectionFactory`, one connection, one publisher channel, one consumer channel, manual acks, and a hand-rolled reconnect loop that recreates channels and re-subscribes after connection loss. Get it through the same restart drill. Then write the paragraph comparing your 100-line loop to Spring's — that paragraph is interview gold.
