# X02 Structured Logging — Code-Along Elective

## Objective

Make one patient journey — REST submit → RabbitMQ publish → consumer work → ack — visible in structured JSON logs under a single correlation id, with no PII on disk. You build the logging spine that every later failure-window experiment will be read through.

## Time box

~1h. Core. Small surface, high leverage: this is the cheapest hour in the whole program measured in interview return, because the X04 walkthrough needs a way to *show* failure evidence.

## Prerequisites

- X01 (the trio stack) or at least `docker compose up` working for Postgres + RabbitMQ.
- `../rabbit/R02_fire_and_forget_publisher.md` completed — you need a real submit→consumer path to thread a correlation id through. X02 exists *after* R02 because tracing a message that cannot be produced is tracing a ghost.
- Spring Boot 3.x project from X01/S01, with the Rabbit setup from R02.
- Position: between R-track basics and `showcase_projects/pharmacy-fulfillment/exercise_03_production.md`.

## Blog & curriculum links

- Primary: `posts/series-3-rabbitmq/06-operational-testing.md` — the management-API-as-oracle and DLQ-depth observability ideas depend on logs you can actually query; the post's "the test doubles as documentation of the operational interface" is only true if the operator can find the message.
- Secondary: `posts/series-5-interview/02-tradeoffs.md` — the four-part tradeoff language (assumption/alternative/choice/sacrifice) is exactly what this kata's fork section forces you to practice on a real decision.
- Coach-assessment gap attacked: "no PII in logs" is a healthcare-bar concern that appears in `../advanced/A15_security_baselines.md`; this elective plants the seam.

## Background & motivation

Every RabbitMQ crash window in the curriculum — relay crash, consumer crash before ack, redelivery — is invisible without a way to follow one message through several hops. A stack trace tells you *what*; only a correlation id tells you *which patient it was for* without printing the patient's data. This kata deliberately ignores tracing infrastructure (no OpenTelemetry, no distributed tracing vendor): a correlation id in JSON logs with MDC is the minimal tool that covers the challenge's scope, and it is the thing a reviewer can actually run and grep.

It also deliberately ignores log *volume* and retention — one process, one log file, done. If you cannot find a single message in a single file, no metrics platform will save you later.

## Learning objectives

- Emit single-line JSON logs with a logback encoder (e.g. `logstash-logback-encoder` or Spring Boot's built-in logging pattern with a JSON converter) and confirm timestamps, level, logger, and message fields.
- Generate or accept a correlation id at the REST edge (`X-Correlation-Id` header, fallback to generated UUID) and put it in the MDC.
- Thread the id through a RabbitMQ publish as a message header and restore it on the consumer side, so producer and consumer logs share one id.
- Prove the id survives the round trip with a grep-level test, and prove PII (patient name, medication names) never appears in a log line.
- Know the Java-trap: MDC is thread-local; where worker threads or coroutines are involved, restoration must be deliberate (`../advanced/A12_observability_slice.md` extends this).

## Warm-up

Grep the current project for every `log` / `println` call and count the places a message is logged without context. Then run the R02 submit once and eyeball the log output — answer in one sentence: *if the consumer failed tonight, how would you find tonight's message in this log?* If the answer is "read it all," the kata is justified. 3 minutes.

## System specification

**Scope in:** one app, one JSON log format, one correlation id per patient journey, RabbitMQ header threading, a no-PII check, and a grep-able demonstration of one journey.
**Scope out:** distributed tracing (OTel), log shipping, sampling, retention, log aggregation, metrics. Those are named future work, not today's problem.
**Functional requirements (minimal):**
- Every log line is one JSON object; the `correlation_id` field is present on every line for a given journey (submit, publish, consume, ack).
- The id is either the inbound `X-Correlation-Id` or a server-generated UUID — never an empty field.
- Consumer-side logs for a message carry the producer's id, restored from the message header.
- Patient name and medication names appear nowhere in the logs (verify by grep after the journey).
**Constraints:** single local process or one Compose stack (X01); no PII by construction — the id is the only patient handle in the log; the id is a UUID (or opaque token), never a patient-identifiable sequence.

## Step-by-step code-along

1. **Switch to JSON logs.**
   - **Do:** add a logging encoder dependency (`logstash-logback-encoder`) and a `logback-spring.xml` (or `application.yml` pattern) that produces one JSON line per event. Keep a console appender only.
   - **Run:** `docker compose up -d --build app` (or `./gradlew bootRun`) and trigger one R02 submit; inspect the app log.
   - **Observe:** each event is a single parseable JSON line; timestamps are ISO-8601 with timezone. If any line contains a stack trace with newlines, that is expected for errors — the *event* line still must be one JSON object.
   - **Decision:** field naming — pick a small explicit set (`timestamp`, `level`, `logger`, `message`, `correlation_id`) now; renaming fields later is a search-and-replace you are doing today for free.

2. **Create the correlation id at the edge.**
   - **Do:** write a `OncePerRequestFilter` (or `HandlerInterceptor`) that reads `X-Correlation-Id` from the request, or generates `UUID.randomUUID()` when absent, stores it in the MDC, and echoes it in the response header.
   - **Run:** `curl -i -X POST /prescriptions` with and without the header; then grep the log for the id.
   - **Observe:** the header round-trips and every log line inside that request carries the id. Note the Kotlin idiom: prefer `val correlationId = request.getHeader(HEADER) ?: UUID.randomUUID().toString()` — the Elvis operator is the whole null-handling story in one line.
   - **Decision:** choose the header name (`X-Correlation-Id` vs `X-Request-Id` vs `X-Trace-Id`) and stick to it — the consumer contract depends on the name.

3. **Put the id on the wire.**
   - **Do:** in the R02 publisher, copy the MDC value into the message properties when sending — either a `MessagePostProcessor` or `messageProperties.setHeader("correlation_id", correlationId)` on the message you build.
   - **Run:** submit one prescription, then in the management UI (X01) inspect the message headers on the queue.
   - **Observe:** the header travels with the message body — the id now exists outside the request thread, which is the entire point. Do not log the message body; log the id.
   - **Decision:** header name must match the MDC key. A mismatch here is the classic silent bug — everything works, nothing correlates.

4. **Restore the id on the consumer.**
   - **Do:** in the R02 consumer listener, add a small interceptor or the first line of the listener that reads the header and calls `MDC.put("correlation_id", header)` — and remove it in a `finally` (or use `MDC.putCloseable`, the try-with-resources equivalent that Java veterans should adopt eagerly).
   - **Run:** trigger the journey again and `grep correlation_id app.log`.
   - **Observe:** the consumer's "received", "effect applied", and "acked" lines carry the same id as the producer's lines. Now a single `grep` reconstructs the whole journey.
   - **Decision:** `putCloseable` (restores previous value on close) vs manual `remove()` — one is exception-safe, one is a leak on a thrown error. Prefer `putCloseable`.

5. **Log the interesting moments, not everything.**
   - **Do:** add structured fields (not message text) at the four moments: submit accepted, publish confirmed, consumer received, ack sent. Keep message text terse; put data in fields.
   - **Run:** run one journey; `docker compose logs app | grep <id>` and count lines.
   - **Observe:** 4–8 lines, each a fact, none a wall of text. This is the shape an interviewer can read in 30 seconds.
   - **Decision:** what goes in `message` vs fields — patient-identifiable data goes in neither (see step 6), but e.g. `prescription_id` in a field is fine and is your query handle.

6. **Prove the no-PII claim.**
   - **Do:** submit a prescription for a patient with a distinctive name (e.g. "ZELDA TESTPATIENT"), then run:
     ```bash
     docker compose logs app | grep -i zelda
     ```
   - **Run:** same grep for the medication name you submitted.
   - **Observe:** zero matches. If it matches, you logged the body somewhere — find it and remove it. This grep is your regression test; consider adding it as an actual test in `../spring/S04_api_tests.md` style.
   - **Decision:** log only identifiers that are opaque to patients — `prescription_id` yes, medication names and patient names no.

## Try this

Fire two journeys concurrently — two terminals, or one script that posts twice with two different `X-Correlation-Id` headers — then:

```bash
docker compose logs app | grep <first-id>
docker compose logs app | grep <second-id>
```

Observe that each grep is a clean, interleaving-free timeline even though the two journeys ran simultaneously. Then do the failure experiment: send a message whose body fails to deserialize on the consumer (a corrupted payload), and confirm the *error* log line still carries the correlation id. A support ticket that contains "which message failed" must be answerable with one grep — and now it is.

## Trade-off fork

**Option A — MDC + header threading (this kata):** the correlation id lives in the thread-local MDC and in the message header; loggers reference it implicitly. Minimal code, zero changes to existing log statements, works with any logging library.

**Option B — explicit context threading:** no MDC; a `CorrelationContext` (or a per-message record) is passed through every method signature and attached to each log call manually. Testable, immutable, invisible-magic-free — but every call site changes, and any method that forgets the parameter silently logs a blank id.

Pick one and write 3–5 lines justifying it, naming the sacrificed property. The interviewer-probe shape is the four-part tradeoff statement from the tradeoffs post: assumption (single-process scope, small team), alternative (B), choice (A), sacrifice (implicit magic — a future reactive/coroutine boundary will break MDC, and A must then revisit). Note honestly: `../advanced/A12_observability_slice.md` revisits this exact seam when a projection consumer introduces an async hop.

## Hints

- **Hint 1:** if consumer logs show the id missing, the header name and the MDC key disagree, or the restore happened after the first log call. Put the restore in the *first* statement of the listener, not in a helper the listener calls.
- **Hint 2:** if JSON logs are suddenly missing fields you expect, check that `logback-spring.xml` is on the classpath and that `logging.config` is not being overridden by `application.yml`. And remember: `MDC.putCloseable` must be closed on *every* path — a `runCatching { ... }` around the listener body with the close in `finally` covers it in Kotlin's preferred style.

## Checkpoint / success criteria

You may leave when:

- One submit produces a JSON-logged journey where all lines (submit → publish → consume → ack) share one `correlation_id`, greppable in one command.
- A corrupted-payload failure line still carries the id.
- `grep -i <patient-name>` and `grep -i <medication-name>` return nothing.
- You can explain, in two sentences, why the id belongs on the message rather than being regenerated at the consumer.

## Bottleneck & reflection questions

1. What breaks the correlation chain at the moment a consumer's work moves to a thread pool or a coroutine — and what is the minimal fix? (This is the exact joint `../advanced/A12_observability_slice.md` later stresses.)
2. The tradeoffs post says a design without a named sacrifice is unfinished. What does MDC cost this system, concretely?
3. Failure handling: the DLQ-depth observability story from the RabbitMQ operational-testing post needs an operator to find *which* messages are dead. How does this logging spine make DLQ forensics possible — and what field is missing if you cannot tell retries from first deliveries?
4. Patient experience: a patient calls about a missing status update. Which two log lines (from step 5) let you answer "was the event produced at all" in under a minute, and what does that imply about where you log?
5. Simplicity: JSON logging was once a "premium" setup. What is the simplest format that still lets you grep a journey — and at what scale does your answer stop being honest?

## Handoff

- Next: `../rabbit/R04_poison_to_dlq.md` (read the DLQ path through your new logs) and `../rabbit/R05_idempotent_consumer.md` (duplicates should now be visible as two consumer lines with one effect line). The reflection question 3 already points at `../advanced/A12_observability_slice.md`.
- Related showcase exercise: `showcase_projects/pharmacy-fulfillment/exercise_03_production.md` — its failure-window evidence is written in the language this kata creates; the exercise's production stage expects observable logs as part of the local reliability story.
- Interview line to be able to say aloud: *"Every patient journey is a correlation id; logs carry the id and never the patient. A support ticket maps to a grep, and a grep maps to a message header, so the failure evidence is queryable without touching PII."*

## Optional stretch

Add a tiny assertion-style test (not a lint plugin): run one journey against the real stack, capture the log output, and assert (a) every line is valid JSON, (b) all lines of the journey share one id, (c) the PII greps return nothing. That turns this elective's manual checks into a runnable regression — the same shape the operational-testing post demands for broker evidence.
