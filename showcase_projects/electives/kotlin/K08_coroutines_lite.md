# K08 Coroutines Lite — Code-Along Elective (OPTIONAL)

## Objective

Get honest working familiarity with `suspend` functions, structured concurrency (`coroutineScope`, `launch`, `async`), cancellation, and deterministic coroutine testing (`runTest`) — and, just as importantly, learn where coroutines are the *wrong* tool for this challenge's blocking PostgreSQL and RabbitMQ clients.

## Time box

~2 hours. **OPTIONAL** — the only optional elective in Track A. Do it after the Wave-1 Kotlin set (K01, K02, K03, K05), ideally also after K04, K06, and K07, if and only if the Day 5 gap in `artifacts/coach-assessment.md` feels real when you say the words "structured concurrency". There is no dedicated blog post; it anchors to the Kotlin series and to the showcase's coroutine caveat.

## Prerequisites

- K01, K02, K03, K05 (the Wave-1 Kotlin set), plus ideally K04, K06, K07 (Wave 2) — coroutines compose with everything you built.
- `K04_inventory_pure_functions.md` in particular: the parallel-inventory-check experiment below reuses its snapshot math.
- Tooling: add `kotlinx-coroutines-core` and `kotlinx-coroutines-test` dependencies to your single Gradle module.
- Showcase position: before `exercise_01_foundation.md` Milestone 4, but the exercise explicitly says "Do not add coroutines just to appear modern" — this kata is how you *earn* the right to say that sentence with evidence.

## Blog & curriculum links

- Nearest primary: `posts/series-1-kotlin/01-kotlin-for-java-developers.md` — the "Java Habit To Avoid" section's blocking-I/O awareness; properties/interop framing for dispatchers.
- Secondary: `posts/series-1-kotlin/05-showcase-kotlin-prescription-domain.md` — "blocking PostgreSQL and RabbitMQ clients are blocking unless the repository deliberately provides non-blocking clients" and the showcase's coroutine caution.
- Coach-assessment gap: Day 5 ("suspend, dispatchers, structured concurrency, cancellation, blocking JDBC/client calls, coroutine testing") — this elective is the only Track A coverage of that day, and the gap stays if you skip it.

## Background & motivation

Day 5 of the plan expects you to answer "where do coroutine boundaries belong in a service using blocking PostgreSQL and RabbitMQ clients?" K08 exists so that answer is built from an experiment, not recited. You will learn the mechanics — `suspend`, `coroutineScope`, `launch`, `async`, `delay`, cancellation — and then immediately stress the *boundary* question: wrapping a blocking call in `suspend` does not make it non-blocking, and a `runBlocking` inside a Spring worker thread is just a fancier thread. The kata's second half is deliberately deflationary: for this challenge, plain threads + blocking clients + explicit concurrency is a defensible answer, and coroutines only earn their place at the dispatch boundary.

It deliberately ignores: `Flow`, channels, actors, hot/cold streams, Dispatchers tuning, Spring WebFlux, and any non-blocking client setup. Two hours buys mechanics + judgment, not a framework migration.

## Learning objectives

- Write and call `suspend` functions; explain what `suspend` actually changes (compiler-generated state machine, no thread on the wall).
- Use `coroutineScope`, `launch`, and `async` and describe the structured-concurrency guarantees (parent waits for children; failure propagates).
- Cancel a scope and observe cooperative cancellation (`delay`, `isActive`); state when cancellation is *not* observed.
- Test `delay`-heavy logic deterministically with `runTest` and virtual time.
- Decide, with evidence, whether a blocking JDBC/RabbitMQ call should be wrapped in `withContext(Dispatchers.IO)` or left on the platform thread.

## Warm-up

Read the "Kotlin guidance for a Java engineer" paragraph of `showcase_projects/pharmacy-fulfillment/exercise_01_foundation.md` (the coroutine sentence) and the Day 5 entry in `artifacts/coach-assessment.md`. Before adding the dependency, write the question you are trying to answer in one sentence: "Does a coroutine make my blocking PostgreSQL call faster, or just move where it blocks?" Keep that sentence; the kata ends with your answer.

## System specification

**Scope in:** a small in-memory "pharmacy worker" simulation — a `suspend` pharmacist decision that `delay`s, a parallel multi-medication inventory check via `async`, a cancellable packaging loop, and `runTest`-based tests.

**Scope out:** real I/O clients, `Flow`, channels, dispatcher tuning, Spring integration, anything that touches a database or broker.

**Functional requirements (minimal):**

- `suspend fun pharmacistReview(prescriptionId): ReviewOutcome` that `delay`s ~a simulated human decision and returns the K03-style outcome.
- `checkStockFor(prescriptions, inventory): StockReport` that runs per-medication checks *concurrently* with `async` and collapses results.
- A parent `coroutineScope` that fails as a unit when any child fails (prove it).
- A cancellable loop (e.g. "packaging progress") that stops cooperatively on cancellation.
- `runTest` tests with virtual time — no real `Thread.sleep` anywhere in the tests.

**Constraints:** single module; coroutine test library is allowed; `Thread.sleep` appears only in a deliberately-written "bad test" you then delete; no Spring.

## Step-by-step code-along

**Step 1 — The dependency and a first suspend**

**Do:** add `kotlinx-coroutines-core` (main) and `kotlinx-coroutines-test` (test). Write the pharmacist sim:

```kotlin
suspend fun pharmacistReview(prescriptionId: String): ReviewOutcome {
    delay(1500)  // simulated human thinking; virtual time in tests
    return ReviewOutcome.Approved(prescriptionId)
}
```

with a sealed `ReviewOutcome` (Approved/Rejected/NotFound shape from K02's discipline).

**Run:** a `runTest { }` test asserting `pharmacistReview("p-1") == ReviewOutcome.Approved("p-1")`. If it is your first `runTest`, note that the test completes *instantly* — 1500ms happened in virtual time.

**Observe:** `suspend` changed the signature only; the function still runs on the calling thread. Java veterans: there is no `Future` and no `CompletableFuture` in the signature — the compiler generates the continuation machinery.

**Kotlin idiom for Java veterans:** `delay` is *not* `Thread.sleep` — it suspends the coroutine and frees the thread. That is the entire point of the mental-model shift; say it out loud before continuing.

**Step 2 — Parallel work with async**

**Do:** using K04's snapshot math, implement a concurrent multi-medication check:

```kotlin
suspend fun checkStockFor(
    prescriptions: List<Prescription>,
    inventory: Map<String, Int>,
): StockReport = coroutineScope {
    val results = prescriptions.map { p ->
        async { reserve(p.items, inventory) }
    }
    // await all, classify into fully-reserved vs shortages
    ...
}
```

**Run:** `runTest` with 3 prescriptions and a counter inside the reserve step; assert total virtual elapsed time is ~max(reserve durations), not the sum — i.e. the checks ran concurrently.

**Observe:** `async` + `awaitAll` is the coroutine form of `CompletableFuture.allOf`, and `coroutineScope` adds the guarantee that *all* children finish before this scope returns — no dangling work.

**Decision (if any):** `async { }` for each item vs a `semaphore`-limited pool. For 3–5 meds, unbounded async is fine; note where a real pharmacy's catalog size would change your mind (that note is interview material).

**Step 3 — Structured failure**

**Do:** make one child of the `coroutineScope` throw (e.g. an unknown-medication `IllegalArgumentException`). Do *not* wrap it in `try/catch` inside the scope.

**Run:** a test asserting the whole `coroutineScope` fails with that exception and the other children were cancelled — capture the sibling-cancellation with `finally { cancelledCount++ }` blocks in each child.

**Observe:** structured concurrency means the scope *is* the failure boundary: one child's exception cancels siblings and propagates. That is a guarantee `ExecutorService.invokeAll` does not give you for free — and it is the behavior you must explain in the Day 5 interview prompt.

**Decision (if any):** let failure propagate vs `supervisorScope` (children fail independently). The pharmacist queue wants supervisor semantics; the inventory check wants fail-fast. Pick per use case and justify in one line each.

**Step 4 — Cancellation**

**Do:** write a packaging loop:

```kotlin
suspend fun packageLoop(): Unit = coroutineScope {
    launch {
        while (isActive) {
            // one packaging step
            delay(100)
        }
    }
}
```

and a test that starts the loop, `delay`s in virtual time, calls `cancel()` on the job, and asserts the loop stopped and a `finally` block ran.

**Run:** the cancellation test. Then break it deliberately: replace `while (isActive)` with `while (true)` — cancellation now hangs or fails differently.

**Observe:** cancellation is *cooperative*: `delay` and `isActive` are the checkpoints. A CPU-bound loop that never suspends or checks `isActive` ignores cancellation entirely — same failure mode as a Java thread calling `interrupt()` and never checking the flag.

**Step 5 — The boundary question (the deflation)**

**Do:** write a "fake JDBC" function — a plain blocking function that does `Thread.sleep(200)` (simulating a connection call) and returns a row count. Then two callers:

```kotlin
suspend fun badSuspend(): Int { return blockingJdbcQuery() }                    // blocks the dispatcher thread
suspend fun goodSuspend(): Int = withContext(Dispatchers.IO) { blockingJdbcQuery() } // moves the blocking
```

**Run:** a test measuring (virtually or with a thread-count probe) that `badSuspend` holds its thread while blocking. If the test with `Thread.sleep`-based timing feels flaky, that flakiness *is* the lesson — record it.

**Observe:** `suspend fun` does not un-block a blocking call; `withContext(Dispatchers.IO)` relocates it. And since your real stack uses blocking JDBC/RabbitMQ clients on Spring worker threads, "coroutines make it async" is a lie — the honest sentence is "coroutines move where blocking happens, at the cost of dispatch overhead." That sentence is your Day 5 answer.

**Decision (if any):** wrap every blocking call in `withContext(Dispatchers.IO)` vs leave the service on plain threads. For the challenge: plain Spring threads + blocking clients is the blog-supported default; note the one condition (a long-running fan-out workload) that would flip the decision.

## Try this

Deliberate experiment — **the non-cancelling producer**:

1. Write a child coroutine that computes a large result in a tight loop *without* `delay` or `isActive` (e.g. summing 100 million integers — keep it small enough to finish).
2. Cancel its job after a virtual `delay(10)`.
3. Expected observation: the coroutine completes anyway — cancellation never lands, because there was no suspension point. The job's result is delivered; `isActive` was never consulted.
4. Now add `ensureActive()` (or check `isActive`) in the loop and rerun: cancellation now interrupts promptly.
5. Record the one sentence: cooperative cancellation only works where the code suspends or checks — a fact that matters for the packaging worker you will simulate in `exercise_01_foundation.md`.

## Trade-off fork

**Option A — coroutines at the service boundary (what this kata teaches, used selectively).**
Pros: readable concurrent composition, structured failure, deterministic tests via `runTest` — and it answers the Day 5 prompt with evidence. Cons: dispatch overhead per call; suspension points must be designed; blocking clients need `withContext(Dispatchers.IO)` discipline or they silently block a dispatcher thread; a small team must all share the mental model.

**Option B — plain threads + blocking clients (the showcase's stated default).**
Pros: simplest explanation ("Spring worker thread does the DB call"), zero dispatch overhead, no structured-concurrency footguns, matches `exercise_01_foundation.md` guidance verbatim. Cons: concurrency is thread-count-bound, parallel composition is manual (`ExecutorService`), tests need real time, and the Day 5 interview question has a weaker story.

Choose one as the *default for Exercise 1* and write 3–5 lines justifying it for a 2–5 hour submission, naming the lost benefit — then name the exact condition under which you would switch to the other (that condition is the whole interview answer).

## Hints

**Hint 1 (mild):** if `runTest` virtual time feels magical, that is the point: the test controls the clock, so a 1.5s pharmacist decision is a 1ms assertion. Prefer `advanceTimeBy` over real sleeps everywhere, including when you probe cancellation timing.

**Hint 2 (stronger):** for the parallel-inventory check, remember `awaitAll` needs a list of `Deferred` — `prescriptions.map { async { ... } }.awaitAll()` is the idiomatic shape. If you find yourself writing `result.await()` inside a loop, `awaitAll` was the intended function.

## Checkpoint / success criteria

You may leave when:

- `runTest`-based tests cover: suspend call, virtual-time delay, parallel inventory check with concurrent-time evidence, structured failure with sibling cancellation, cooperative cancellation (and the `while(true)` counter-example), and the `withContext(Dispatchers.IO)` boundary.
- No `Thread.sleep` remains in any *intended* test; the flaky-timing lesson is recorded.
- You can deliver the one-sentence deflation: suspend ≠ async-I/O; blocking clients relocate, not disappear.
- You have a written default policy (fork) plus the switch condition.

## Bottleneck & reflection questions

- Your `async` inventory check races per-medication reservations over a pure snapshot — is the parallelism *actually* safe here, and what breaks if the same pure function runs over a shared mutable map? (K04's purity is what makes this trivial — say it.)
- The packaging loop cancels cooperatively. In `exercise_01_foundation.md` Milestone 5's *real* packaging consumer, what is the analogous check point between business work and broker acknowledgement?
- "Coroutines move where blocking happens." Where, precisely, does the blocking happen in the real stack — and does Spring's worker-thread pool change your answer?
- `runTest` makes time deterministic. Which claims about the *real* system can virtual time never prove? (Say: broker latency, DB contention — the P/R tiers.)
- If an interviewer asks "would you use coroutines in this service?", which of today's experiments is your evidence, and which sentence from the showcase guidance is your boundary?

## Handoff

- Next elective: `../spring/S01_hello_prescription_api.md` — Wave 1 completes there. K08 is done; the remaining Track A files were already covered.
- Related showcase exercise: `showcase_projects/pharmacy-fulfillment/exercise_01_foundation.md` — the coroutine guidance paragraph is this kata's conclusion; Milestone 4's blocking-client stance should now be a claim you can defend with evidence.
- Interview line you should be able to say aloud: "I know coroutines: `suspend` composes sequential async logic, `coroutineScope` makes failure structured, and cancellation is cooperative — but for this service, where PostgreSQL and RabbitMQ clients are blocking, I keep the baseline on plain worker threads and only introduce `withContext(Dispatchers.IO)` at a boundary that provably needs it, because wrapping a blocking call in `suspend` moves the block, it does not remove it."

## Optional stretch

Implement a mini fan-out: 100 in-flight `async` checks against one shared immutable snapshot, capped with a `Semaphore(10)`, inside a `runTest`. Measure virtual elapsed time against the uncapped version, and write three lines on what the cap models in a real packaging worker (prefetch, R03) — the first cross-track link between Track A and Track D is yours to make.
