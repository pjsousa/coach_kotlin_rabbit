# X01 Docker Compose Trio — Code-Along Elective

## Objective

Stand up one local stack — Spring Boot app + PostgreSQL + RabbitMQ — behind a single `docker-compose.yml` with real healthchecks, then prove you can lose any one of the three and watch the system's behavior instead of guessing. You build the exact local infrastructure every later elective and showcase exercise will assume exists.

## Time box

~1–1.5h. Core. Wave 1 — this is the first thing to run before any `P*`, `R*`, or showcase exercise. It is labeled "glue" in the program map, but it is listed as a Wave-1 prerequisite because the other exercises are miserable to do against a half-working local stack.

## Prerequisites

- Docker Desktop (or OrbStack) running, `docker compose version` ≥ 2.x.
- JDK 17+ and any Spring Boot 3.x skeleton you can produce (`start.spring.io` or the S01 output) — a controller that logs "hello" and an Actuator `/actuator/health` endpoint is enough; you are not building the product here.
- No prior electives required. Position: immediately before `showcase_projects/pharmacy-fulfillment/exercise_01_foundation.md`, and a prerequisite for `../postgres/P01_schema_and_migrations.md` and `../rabbit/R01_topology_scratchpad.md`.

## Blog & curriculum links

- Primary: `posts/series-3-rabbitmq/06-operational-testing.md` — the version-pin rule ("the test must prove behavior on the broker the challenge ships") starts in Compose, not in the test suite.
- Secondary: `posts/series-2-postgres/05-testing-postgresql.md` — the Compose stack and Testcontainers must agree on image versions; that post's "the authoritative engine" argument is only coherent once the local stack is reproducible.
- Coach-assessment gap attacked: infrastructure friction burning time before the first hands-on exercise, and "no production claims" discipline — local Docker only, no cloud.

## Background & motivation

Every exercise after this one says "run the app against Postgres and RabbitMQ" as if that were one command. This kata makes it true. It deliberately ignores the product entirely — no prescriptions, no queues, no business logic — because the failure modes you practice here (a broker that is not up when the app starts, a database that dies under you, a port collision) are exactly the ones that will otherwise eat 45 minutes of a 2-hour challenge submission.

It also deliberately ignores production-grade infra claims: one Compose file, no Kubernetes, no TLS, no orchestration story. The honest claim is narrower and stronger — *this exact file is what I demoed against, and the same image pins are what my tests ran against.*

## Learning objectives

- Write a compose file with pinned, `-management`-tagged service images and explicit service dependencies.
- Express a real healthcheck per service (`pg_isready`, `rabbitmq-diagnostics`, Actuator `/actuator/health`) and gate app startup on them.
- Thread environment-driven config (`SPRING_DATASOURCE_URL`, `SPRING_RABBITMQ_*`) from Compose into Spring without hardcoding.
- Observe container restarts and dependency death; decide when `restart` policies mask bugs versus paper over race.
- Keep the Compose image pins identical to what Testcontainers uses later (the version-pin rule from the RabbitMQ operational-testing post).

## Warm-up

Read the "Topology Assertions" opening of `posts/series-3-rabbitmq/06-operational-testing.md` (first ~15 lines) and note the version-pin rule. Then run one probe:

```bash
docker run --rm rabbitmq:3.13-management rabbitmq-diagnostics -q ping
```

If that fails, Docker networking/registry is your first problem, not Compose. Time-box: 3 minutes.

## System specification

**Scope in:** one compose file; three services (`app`, `postgres`, `rabbitmq`); healthchecks; env-var wiring; restart behavior observation.
**Scope out:** cloud, Kubernetes, TLS, volumes with real backup strategies, multiple compose files, any product logic, Testcontainers (that is `../postgres/P07_testcontainers_postgres.md` territory).
**Functional requirements (minimal):**
- `docker compose up` starts all three and the app's `/actuator/health` returns `UP` without manual intervention.
- The app can insert a row into Postgres and publish/consume a message on RabbitMQ from the same boot.
- `docker compose ps` shows all three services `healthy`.
- Killing `postgres` (or `rabbitmq`) and restarting it recovers the stack to `healthy` without editing the file.
**Constraints:** local Docker only; images pinned (no `latest`); the broker image carries the `-management` tag so you can open the management UI at `http://localhost:15672`; the database image matches what you will later pin in Testcontainers.

## Step-by-step code-along

1. **Create the trio skeleton.**
   - **Do:** write `docker-compose.yml` with two services: `postgres` (pinned image, e.g. `postgres:16-alpine`) and `rabbitmq` (`rabbitmq:3.13-management`), each with an explicit port mapping (`5432`, `5672` + `15672`) and a named volume for the database.
   - **Run:** `docker compose up -d` then `docker compose ps`.
   - **Observe:** both containers start and stay running; you can open the RabbitMQ management UI.
   - **Decision:** pick the exact image tags now and write them down. Later, `../postgres/P07_testcontainers_postgres.md` and the R-track integration tests must reuse them.

2. **Give each dependency a healthcheck.**
   - **Do:** add `healthcheck` blocks. Postgres: `pg_isready -U <user> -d <db>` (remember `-U` — default image user is `postgres`). RabbitMQ: `rabbitmq-diagnostics -q ping`.
   - **Run:** `docker compose ps` and watch the `STATUS` column move from `starting` to `healthy`.
   - **Observe:** a container whose check fails shows `unhealthy` instead of a lie. That distinction is the whole point of this step.
   - **Decision:** check interval/timeout/retries defaults are fine for a lab; note where you'd tune them for a slow CI machine.

3. **Add the app service and gate it on health.**
   - **Do:** add an `app` service that builds your Spring Boot jar (or mounts a pre-built `build/libs/*.jar`) and declares:
     ```yaml
     depends_on:
       postgres:
         condition: service_healthy
       rabbitmq:
         condition: service_healthy
     ```
   - **Run:** `docker compose up --build -d`; then `docker compose ps`.
   - **Observe:** the app does not even start its container until both dependencies report healthy. Check the app log order: `CREATE TABLE` / connection pool messages must come after Postgres reports ready.
   - **Decision:** if `depends_on: condition: service_healthy` annoys you (it is verbose), note your alternative — you will argue it in the trade-off fork below.

4. **Wire configuration through the environment, not code.**
   - **Do:** set `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/<db>`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_RABBITMQ_HOST=rabbitmq`, and your health endpoint's management exposure (`management.endpoints.web.exposure.include=health`) as `environment:` entries on the app service. Use `environment:` with an `.env` file for the password so the secret is not in the YAML.
   - **Run:** add a tiny Spring `ApplicationRunner` that does one JDBC `SELECT 1` and one `RabbitTemplate.convertAndSend` to a scratch queue, then `docker compose logs -f app`.
   - **Observe:** both calls succeed at startup with the service names as hostnames — that is Docker DNS doing its job, and it is why the app has no `localhost` hardcoding.
   - **Decision:** service names (`postgres`, `rabbitmq`) are the contract; keep them short and stable because every future elective's config references them.

5. **Give the app its own healthcheck.**
   - **Do:** add a healthcheck to `app` that curls `/actuator/health` inside the container — the container image needs `curl` present, so check your base image or use a `HEALTHCHECK` that invokes the JVM's actuator via `wget`. Use `start_period` generously (JVM boot is slow) so the JVM has time to come up before health checks start counting failures.
   - **Run:** `docker compose ps`; all three rows must read `healthy`.
   - **Observe:** the difference between "container running" and "application actually serving" — this is the exact distinction the operational-testing post draws between the broker accepting a publish and a consumer processing it.

6. **Kill something.**
   - **Do:** `docker compose stop postgres`, watch the app log, then `docker compose start postgres`.
   - **Run:** repeat for `rabbitmq`.
   - **Observe:** does the app reconnect (connection pool / `CachingConnectionFactory` retry) or crash? Write down what you saw; there is no right answer yet, only evidence.
   - **Decision:** add `restart: on-failure` or not — and say why, in one sentence. This decision shows up again in `../advanced/A08_connection_channel_lifecycle.md`.

## Try this

Start the stack, then `docker compose stop rabbitmq`, and within two seconds send a publish from the app (a manual `curl`-triggered endpoint or a scheduled job). Re-start RabbitMQ. Two observations to force:

1. What does the publisher do while the broker is down — fail fast, block, or retry?
2. Does the queue still exist after the broker restart, or was it auto-deleted/non-durable? (Check the management UI before and after.)

Either behavior is defensible at this scale; the interview-grade output is that you *know which one your stack has*. This directly pre-runs the "broker restart" crash-window claims from the R-track.

## Trade-off fork

**Option A — `depends_on: condition: service_healthy`:** the app's container does not start until both dependencies are verified healthy. Deterministic, self-documenting, and it matches the "gate on evidence" instinct the whole curriculum teaches.

**Option B — no ordering gating; the app retries:** dependencies start in any order and the app's connection pools (HikariCP, Spring AMQP recovery) retry until the brokers appear. Less YAML, and it mirrors how a real process behaves when dependencies flap at runtime — you already saw it in step 6.

Pick one and write 3–5 lines justifying it, including what you gave up. A defensible middle answer: gate the *first* boot (Option A) and rely on recovery for *runtime* failures (Option B's retry logic), which is precisely how the two mechanisms differ — startup order is a deployment concern, reconnect is a runtime concern. Whatever you choose, do not pretend the other side costs nothing: Option A burns a container slot while a slow database boots; Option B means the app can log a wall of connection errors before stabilizing.

## Hints

- **Hint 1:** `pg_isready` needs `-U` matching the `POSTGRES_USER`, or it reports ready against the wrong role. For RabbitMQ, `rabbitmq-diagnostics -q ping` exits non-zero while the broker is still starting — that is exactly what you want a healthcheck to do.
- **Hint 2:** if the app healthcheck keeps reporting `unhealthy` while `docker compose logs app` shows a healthy app, you are looking at `start_period` / timeout math, or `curl` is missing in the image. Verify the command works with `docker compose exec app <your-check-command>` — the check you debug by hand is the check that will pass in Compose.

## Checkpoint / success criteria

You may leave when:

- `docker compose up -d` → `docker compose ps` shows `app`, `postgres`, `rabbitmq` all `healthy` on a fresh `docker compose down -v`.
- The app's startup log shows a successful DB call and a successful publish/consume without edits.
- You can restart either dependency and the stack returns to `healthy` unaided.
- You can say aloud which image tags your stack and your future Testcontainers must share.

## Bottleneck & reflection questions

1. What does "healthy" mean differently for Postgres versus the Spring app, and why is conflating "container up" with "ready to serve" a bug in an interview story?
2. Where does this Compose file stop being enough — what is the first thing that breaks at 10 workers or a second app instance? (You are not building it; just name the boundary.)
3. If the app crashes between a DB commit and a RabbitMQ publish (the dual-write window from `../rabbit/R06_dual_write_failure_demo.md`), what in this stack would help you see that from logs or the management UI?
4. How does the version-pin rule in the RabbitMQ operational-testing post constrain the images you chose today?
5. Patient experience angle: the challenge's demo is only credible if it runs on a machine you can reproduce; what would it cost the interview if your demo depended on a setup step you cannot show?

## Handoff

- Next: `../postgres/P01_schema_and_migrations.md` (your Postgres is now real and ready) and `../rabbit/R01_topology_scratchpad.md` (management UI is up). If you want the app-side baseline first, `../spring/S01_hello_prescription_api.md`.
- Related showcase exercise: `showcase_projects/pharmacy-fulfillment/exercise_01_foundation.md` — this stack is the environment it expects; the exercise's "Starting Point" section assumes the compose context exists.
- Interview line to be able to say aloud: *"Local infrastructure is not a side quest — my compose file, my demo, and my tests share the same pinned images and the same health gates, so the environment I prove behavior in is the environment I show in."*

## Optional stretch

Add a fourth service that polls the health of the others (or use `docker compose events`), and have it emit a single JSON status line every 5 seconds. That is a preview of `../advanced/A12_observability_slice.md` — and it gives you a one-line "stack health" artifact to show in the X04 walkthrough without touching product code.
