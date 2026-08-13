# A13 Chaos Drill Script — Code-Along Elective

## Objective

Turn the failure windows you proved in R06/R07 and the signals you built in A12 into a *repeatable drill*: a script that kills the relay mid-publish, restarts the broker with messages pending, and asserts — from durable state, not vibes — that no work was lost, duplicates are observable and safe, and the system converges. You leave with a `drill.sh`, a short runbook, and N drill runs of evidence showing the at-least-once contract under real kills.

## Time box

~2–3h. Core: steps 1–5. Optional: step 6 (randomized kill timing) and the full-broker-restart variant in "Try this".

## Prerequisites

- `../rabbit/R06_dual_write_failure_demo.md` — you documented the DB-then-publish loss gap there.
- `../rabbit/R07_outbox_relay_mini.md` — the outbox + relay + publisher-confirms path this drill kills.
- `A09_postgres_under_contention.md` (soft) — the relay's claim behavior under restart uses the SKIP LOCKED idiom you proved there.
- `A12_observability_slice.md` — its five signals are the drill's "healthy again" checks.
- Position: **interview polish — after Exercise 3.** The drill is Milestone 9's "full broker restart or network interruption drill" plus the runbook Milestone 6 asks for.

## Blog & curriculum links

- Primary: `posts/series-3-rabbitmq/06-operational-testing.md` — "a design that cannot be tested is a design that has not been finished"; the latch-based crash simulation and the duplicate-publication consequence test.
- Secondary: `posts/series-5-interview/01-take-home-walkthrough.md` (the walkthrough structure this drill becomes evidence for) and `posts/series-3-rabbitmq/02-publisher-confirms-outbox.md` (the relay crash window being killed, for real).
- Coach-assessment gap: production judgment and interview defense — "failure handling and tradeoff explanation are assessed explicitly," and RabbitMQ "operational failure modes."

## Background & motivation

R06 showed you the loss window of a post-commit publish and R07 closed it with an outbox — but both ended with a *test* that simulated the crash. The blog post's own line is the gap: "a mock cannot crash." Neither can a latch, really. This kata kills real processes inside the exact windows the design papers over, and it makes the kill repeatable so the answer to "what happens when the relay dies?" is a script and ten runs of evidence instead of a paragraph.

- **The mid-publish kill is the honest version of the R07 crash test.** R07's unit test proved the relay *would* republish an uncertain row; the drill proves a real `kill -9` produces exactly that republish, and that the consumer's inbox neutralizes the duplicate. Two copies of one event id, one modeled effect — the at-least-once sentence, witnessed.
- **The broker restart is the claim the demo never shows.** Messages on a durable queue, a consumer with unacked deliveries, a broker that goes away mid-window: what survives, what redelivers, what is reclaimed? The drill turns "durable" from a queue flag into an observation.
- **A runbook is a document you only trust after it has run.** The drill's success criteria are exactly the runbook's steps, so the runbook is never fiction: it is the script's trace output with a human decision layer on top.

What this kata deliberately ignores: network partition simulation (`docker network disconnect`), disk-full and OOM exercises, and multi-node failover. One local broker, one app, real kills — everything else is scope for another day, and the runbook says so in one line.

## Learning objectives

- Write a drill script that kills a specific process (relay, consumer, broker container) at a scripted point and asserts recovery from durable state.
- Prove the relay crash window end-to-end: an uncertain outbox row is republished after restart, producing an observable duplicate the inbox neutralizes.
- Prove broker restart semantics: durable queues, persistent messages, and unacked deliveries survive or redeliver as the contract says.
- Make the drill's verdict binary — PASS/FAIL on counts and lag signals — never "looks fine."
- Write a runbook whose steps are the drill's assertions, and whose "healthy again" checks come from A12's five signals.

## Warm-up

Re-read the "Duplicate Publication And The Relay" section of `posts/series-3-rabbitmq/06-operational-testing.md` and the crash-window language in `posts/series-3-rabbitmq/02-publisher-confirms-outbox.md`. Then list, in three lines, the *exact* windows R07 named: where can a message be delivered twice, and where can it be lost? The drill exists to make each line observable.

## System specification

**Scope in**

- `drill.sh` (bash, `docker compose` + the Management API + `psql` + `kill`): a sequence of scenario runs, each with a stated kill point, a recovery step, and a PASS/FAIL assertion on durable state.
- The R07 stack (app + Postgres + RabbitMQ) plus the A12 signal queries, already on disk.
- `runbook.md` in the kata folder: incident steps matching the drill's scenarios.
- Evidence folder: one subfolder per scenario run (`evidence/run-001/...`).

**Scope out**

- No new application code beyond a tiny "kill hook" (an endpoint or pidfile) if your compose service lacks one — prefer `docker compose kill <service>` or `kill <pid>` from a pidfile.
- No randomized fault injection frameworks (Chaos Monkey, etc.) — a scripted `shuf`-timed kill in step 6 is the local ceiling.
- No load generators beyond the small batch the drill seeds.

**Functional requirements (minimal)**

1. `./drill.sh run --scenario relay-mid-publish` completes in under 3 minutes with a PASS or FAIL verdict.
2. Each scenario asserts from the database and broker: event counts, effect counts, DLQ depth, and the A12 signals at zero/steady state at the end.
3. The runbook's recovery steps, executed by hand, reproduce the drill's PASS.
4. Never claims exactly-once: the PASS text says "duplicate observed and neutralized," not "no duplicates."

**Constraints**

- Local Docker only, pinned versions (same `rabbitmq:3.13-management`-style pin your tests use — the version-pin rule from the blog post).
- The drill must leave the stack in the same state it found it (drain queues, reset counters) so it is re-runnable.

## Step-by-step code-along

### Step 1: The harness — pidfiles, timestamps, verdict

**Do:** Make the app and relay write a pidfile (or use `docker inspect` to find the container pid — on macOS, `docker top <container>` gives you the host pid for `kill`). Write `drill.sh` skeleton: `run_scenario()`, a global `RESULT=PASS` accumulator, and a `fail(msg)` that flips it and prints a timestamped line. Every scenario ends with a summary block: scenario name, kill point, elapsed, verdict.

**Run:** `./drill.sh run --scenario noop` (a scenario that does nothing but print the summary).

**Observe:** A clean PASS with timestamps. From here, every scenario is a function with a kill and an assertion.

**Decision:** Kill via `docker compose kill` (container-level, also kills the JVM's parent — cleaner) vs `kill <host-pid>` of the relay (survives container restarts, finer-grained)? Nudge: `docker compose kill` matches what an operator's incident actually looks like (service down); the pid kill is the *unit* version. Use compose kill for the drill, and note the difference in the runbook.

### Step 2: Scenario 1 — kill mid-publish (the R07 window, for real)

**Do:** Seed 10 events through the app so 10 outbox rows are pending. Start the relay, and in the script, poll the outbox age / published count until ~5 are published, then `docker compose kill relay`. Wait 2s, `docker compose up -d relay` (restart), wait for convergence, then assert:

```sql
SELECT count(*) FROM outbox_events WHERE published_at IS NOT NULL;  -- 10
SELECT count(*) FROM consumer_inbox WHERE consumer='packaging';      -- 10 effects
```

and broker-side: work queue depth 0, DLQ depth 0.

**Run:** The scenario, twice.

**Observe:** PASS both times — and the interesting one is the log line: the restarted relay republished the rows whose confirm never became a mark. Grep for duplicate event ids delivered to the consumer (`grep -c '<same-event-id>'` in consumer log ≥ 2), and confirm the inbox made the second delivery a no-op (one effect row per event id). Save `evidence/run-00N/scenario-1.log` with that grep.

**Decision:** Assert "no effect ran twice" (inbox count = event count) vs assert "at least one duplicate delivery happened" (the window fired)? Nudge: assert *both* — the first is the safety claim, the second proves the kill actually hit the window and you are not passing a drill that never exercised the failure.

### Step 3: Scenario 2 — consumer killed between effect and ack

**Do:** Kill the consumer while it holds unacked deliveries. Easiest honest way: pause the app with `kill -STOP` mid-processing (or a latch-style sleep in the consumer for a fixed window), `docker compose kill app` after you observe `messages_unacknowledged > 0` via the Management API, restart, and assert: the unacked messages were reclaimed and redelivered; inbox dedup means the modeled effects did not double; work queue drains to 0.

**Run:** The scenario; capture the Management API snapshot showing `unacked > 0` *before* the kill, and the redelivery count after.

**Observe:** The broker's `redelivered` flag story from the blog post, in the log: same event id, two deliveries, one effect. The drill's PASS line should read: `effects=10 inbox=10 deliveries>=11` — note the deliberate asymmetry: deliveries *exceeding* effects is the at-least-once contract, and the verdict text must say so.

**Decision:** Kill the whole app (consumer + relay together) vs a targeted consumer shutdown? Nudge: the whole-app kill is the realistic incident and also tests that the relay restarts cleanly; target the consumer separately only if the first is too coarse to observe unacked state.

### Step 4: Scenario 3 — broker restart with messages in flight

**Do:** Publish 20 events, pause the consumers, and with messages sitting on the durable work queue (and some unacked), `docker compose restart rabbitmq`. Wait for the broker to report `alive` (the healthcheck), then bring consumers back.

**Run:** The scenario; after convergence, assert queue depth 0, DLQ 0, effects = 20, projection lag 0.

**Observe:** Durable queues + persistent messages survive the restart (the queue depth before restart is what you recorded — it must be present after, then drain). Unacked deliveries redeliver. One thing that will *not* survive is the relay's in-flight channel: watch the app log for reconnection, and confirm the relay resumes from the outbox table (which is exactly why the outbox is the durable handoff — the broker restart is absorbed as "confirm uncertainty," which the row already tolerates).

**Decision:** `docker compose restart` vs `docker compose stop` + `start` (which tests the down-then-up ordering a real incident has)? Nudge: use stop + start for the scenario and keep restart for the "quick bounce" variant — the runbook should tell an operator which one they performed.

### Step 5: The runbook — the drill's assertions as human steps

**Do:** Write `runbook.md`: for each of the three scenarios, an Incident section with (1) what you see (symptom), (2) what the drill proved happens (mechanism), (3) the recovery steps in order (restart order, drain/republish commands, A12 signals to check), (4) the "healthy again" checklist — the five signals at steady state, plus row counts. The runbook's steps must be copy-paste from the drill script's assertion section.

**Run:** Execute the runbook *by hand* for scenario 1, in a fresh shell, without running the script.

**Observe:** The hand-run reproduces the script's PASS — this is the definition of a runbook that is not fiction. If you had to improvise a single command, the runbook is incomplete; add it.

**Decision:** Runbook-first or drill-first? Nudge: the script you already have is the ground truth; write the runbook from the script, not from memory, so the two can never disagree.

### Step 6: Randomized timing (optional)

**Do:** A `--random` mode: instead of killing at the scripted observation point, sleep a random 0–5s and kill the relay mid-loop for 10 iterations, recording per-iteration outcome.

**Run:** `./drill.sh run --random --iterations 10`.

**Observe:** The distribution of outcomes: every iteration PASS on durable counts, some iterations with 0 duplicate deliveries (the kill missed the window) and some with ≥1 (the window was hit). That spread is the honest "crash window" statistics — you can now say *"across 10 kills, the window fired 6 times and never lost an event"* with evidence.

## Try this

**The dual kill — broker and relay inside the same window.** Restart the broker while the relay is mid-publish. The relay's confirm is lost *and* the broker forgets the channel; on restart the relay republishes rows whose confirm outcome it never learned. Assert: every event is delivered at least once, no effect runs twice (inbox), and the queue drains. This is the combined scenario the blog post's latch tests cannot compose — two real failures in one window — and it is the single most valuable run in the evidence folder for the interview.

**Second experiment — the consumer-outlasts-broker probe.** Stop the broker with the consumer running. Observe the consumer's channel error handling (does it keep retrying, does it mark the listener dead?), then restart the broker and confirm the consumer recovers without an app restart. Whatever your Spring listener factory does by default is your answer; the runbook's "consumer won't reconnect" entry should match it.

## Trade-off fork

Pick **one**, write 3–5 lines justifying it, and name the lost benefit.

- **A: scripted kills on a schedule vs B: manual drill with a human watching.** A script is repeatable, fast, and leaves evidence — it also trains nobody, because a human watching a run learns the *symptoms* your assertions check for. A manual drill builds the muscle and finds the gaps the script's assertions don't cover — it is slow, non-repeatable, and produces no comparable evidence between runs.
- **A: stop-at-first-failure vs B: run-all-scenarios-and-report.** Stop-at-failure isolates the broken scenario for debugging — it also leaves the stack dirty and hides a *second* failure in a later scenario. Run-all gives the full picture in one pass — and a failure mid-run can cascade into later scenarios, producing evidence you cannot attribute.

## Hints

**Hint 1:** If a scenario shows a *lost* event (effect count < event count), the almost-certain causes are: the message was published with `deliveryMode` non-persistent or to a non-durable queue (check the Management UI `durable` flag), or the relay marked `published_at` before the confirm (the R07 bug this drill exists to catch). If the relay restart does *not* republish, check that the claim query selects rows with `published_at IS NULL` — an eager mark during the kill is the silent loss path.

**Hint 2:** If the broker-restart scenario flakes, the usual cause is asserting too early: the Management API reports `alive` before quorum queues finish recovery. Poll until the queue's `messages_ready` equals the value you recorded pre-kill *and* the consumers are attached (`consumer_count` > 0) before starting the assertions — and use the same polling pattern the blog post's Awaitility discipline prescribes, not fixed sleeps.

## Checkpoint / success criteria

You may leave when:

- Scenario 1 PASSes twice with both assertions present: effects once per event id, and ≥1 observed duplicate delivery proving the window fired.
- Scenario 3 (broker restart) shows recorded queue depth surviving the restart and draining to zero with lag 0.
- The runbook, executed by hand, reproduces the script's PASS without improvising a command.
- `--random --iterations 10` produced 10 PASS verdicts with a logged distribution of window hits.
- You can say aloud, pointing at `evidence/run-00N/`: "I killed the relay mid-publish and the broker mid-restart; deliveries were duplicated and neutralized, effects ran once, and nothing was lost — that is at-least-once delivery with idempotent effect, and I have the logs."

## Bottleneck & reflection questions

1. The dual-kill scenario produced duplicate deliveries that the inbox absorbed. Which of your *other* failure windows — retry exhaustion, DLQ replay, operator republish — also ends in the same inbox path, and what does that tell you about where the dedup guarantee actually lives?
2. Your drill asserts on row counts and queue depth. What patient-visible symptom would pass all of those while still being wrong — and which signal from A12 would catch it?
3. The random-timing run showed iterations where the window never fired. What does a "PASS with zero duplicates" run prove, and why is that not evidence that the window closed?
4. The runbook restores service "in the order the drill proved." Where does that ordering argument fail if the outage is the *app database*, not the broker?
5. Milestone 9 asks which failures you simulate with latches and which with a real restart. Which of today's three scenarios could only be proven with a real kill, and what claim would a latch-only version have over-claimed?

## Handoff

- **Next elective:** `A14_cut_line_architecture.md` — the drill's scenarios are exactly the "failure behavior" evidence a cut-line document must name before a feature is cut; then `A15_security_baselines.md`.
- **Related showcase exercise:** `../../pharmacy-fulfillment/exercise_03_production.md`, Milestones 9–10 — this drill is its "full broker restart or network interruption drill" and its crash-matrix rows, converted from tests into rehearsed recovery.
- **Interview line:** "The drill is scripted and binary — kill mid-publish, restart the broker, assert from durable state that effects ran once and nothing was lost. Across ten randomized kills the window fired six times and never lost an event, and the runbook is the script's assertions written as human steps, proven by executing it by hand."

## Optional stretch

One harder twist: add a `--replay` scenario — after scenario 1, manually republish one DLQ message with its original event id (the operator path from the retries post) and assert the inbox still neutralizes it. Measure the drill's total runtime, and write the one-paragraph "residual risk" section the runbook should carry: what this local drill cannot prove about a production broker.
