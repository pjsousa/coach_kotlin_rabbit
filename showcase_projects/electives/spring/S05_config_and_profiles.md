# S05 Config and Profiles — Code-Along Elective

## Objective

Move the hardcoded values of the S-track slice into `application.yml`, add a `test` profile, make configuration fail fast on bad values, and prove the "no secrets in yml" rule with an environment-variable placeholder. The primary objective is to build the config hygiene habit Ex1 will need the moment Postgres and RabbitMQ URLs become real — without adding any of that machinery yet.

## Time box

~1 hour, core track. Suggested split: read + inventory 10 min, properties class + yml 20 min, test profile 10 min, fail-fast + env placeholder 15 min, fork notes 5 min.

## Prerequisites

- `S01_hello_prescription_api.md` (ideally through `S04_api_tests.md` — the test-profile step uses the suite).
- JDK 17+, no Docker.
- Showcase position: **before Exercise 1** — Ex1's `application.yml` will carry DB and broker settings from env; you are practicing the pattern on harmless values.

## Blog & curriculum links

- Primary (nearest fit): `posts/series-1-kotlin/05-showcase-kotlin-prescription-domain.md` — Spring-Kotlin conventions: `val`-first value model, explicit boundaries, "what a reviewer should hear" — the same discipline applied to configuration.
- Secondary: `posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md` — "A Practical Test Set For The Time Box" (your test profile is part of that set).
- Note: there is no dedicated config post in the series; this elective is the practical Spring track the plan leaves implicit — the blog links above are the closest anchors, and your README (S06) can name the gap.

## Background & motivation

Java teams pattern-match configuration; Kotlin changes the shape, not the problem. The S-track slice works with hardcoded values because it is a lab — but the moment Ex1 adds Postgres and RabbitMQ, "which environment am I talking to" becomes a security and correctness question. This kata makes the switch now, on harmless values (max items per prescription, a submission-key reuse window, a notice-window minutes value), so the *mechanics* — profiles, env placeholders, fail-fast — are muscle memory before real infrastructure exists. It deliberately ignores secret managers, config servers, encryption, and spring-cloud config — the rule here is a single sentence: "yml is committed, so yml has no secrets."

## Learning objectives

1. Move hardcoded values into `application.yml` and read them with a typed `@ConfigurationProperties` class.
2. Use Kotlin `data class` + `var` + defaults so the properties class works with a minimal yml.
3. Create a `test` profile and prove it is active under `./gradlew test` with an assertion.
4. Demonstrate fail-fast: a wrong-typed or out-of-range value stops startup with a readable error (via `@Validated`).
5. Use the `${ENV_VAR:default}` placeholder pattern for values that must come from the environment — and verify `.gitignore` + git diff show no secrets.
6. Name exactly which keys Ex1 will add (DB URL, Rabbit URL, credentials from env) without adding them now.

## Warm-up

Open `posts/series-1-kotlin/05-showcase-kotlin-prescription-domain.md` and read "Do Not Let The UI Define The Architecture" (or, if short on time, the "Final Checklist") — the principle is the same: decide boundaries deliberately and document them (3 min). Probe: grep your S01–S04 project for every magic value (`MAX`, limits, the status vocabulary strings) and write them down — that list is your yml schema.

## System specification

- **Scope in:** `application.yml` with an `app:` properties group; `@ConfigurationProperties` class; `application-test.yml` (or a `test` profile section) overriding one value; one startup log line printing resolved properties; fail-fast on invalid values.
- **Scope out:** Postgres, RabbitMQ, secret management, config server, vault, encryption, multiple environment files beyond `test`/`dev` demos.
- **Constraints:** no secrets in any committed yml; defaults present so the app runs with an empty config; behavior of the endpoints unchanged; single module.

## Step-by-step code-along

1. **Inventory the magic values**
   - **Do:** collect the hardcoded values from S01–S04 — e.g. `maxItems` per prescription (if you added one), a `submissionKeyReuseWindow` in minutes, a `noticeWindowMinutes` the view could use later, the port.
   - **Run:** `git grep -n '8080\|MAX_ITEMS\|patient-1' src/main/kotlin` or an IDE find.
   - **Observe:** you now have the schema. Keep it tiny: three or four keys, all harmless, all with sensible defaults.

2. **Typed properties**
   - **Do:** `@ConfigurationProperties(prefix = "app") data class AppProperties(var maxItems: Int = 10, var submissionKeyReuseWindowMinutes: Long = 5, var noticeWindowMinutes: Long = 30)`; register with `@ConfigurationPropertiesScan` on the main class (or `@EnableConfigurationProperties`). Add `spring-boot-configuration-processor` as a compile-time dep for IDE metadata.
   - **Run:** `./gradlew bootRun`, then curl the happy path.
   - **Observe:** behavior unchanged, but now a single class *is* the config contract. Kotlin idiom call-out: `var` here is deliberate — Spring rebinds properties into the class after construction, so `val` with defaults fights the binder; keep `var` for these mutable binding slots and `val` everywhere else.

3. **application.yml**
   - **Do:** create `src/main/resources/application.yml` with the `app:` block matching the class, and set `server.port: 8080` explicitly. Start the app and watch the log.
   - **Run:** `./gradlew bootRun`, read the startup log.
   - **Observe:** nothing changes behaviorally — the point is the values now live in one reviewed place, and S06 can point at it.

4. **Test profile**
   - **Do:** `application-test.yml` overriding e.g. `app.notice-window-minutes: 5`. Add a tiny test: `@SpringBootTest @ActiveProfiles("test")` asserting `appProperties.noticeWindowMinutes == 5L` (and run one S04 test under the profile). Use kebab-case keys (`notice-window-minutes`) — relaxed binding maps them to `noticeWindowMinutes`.
   - **Run:** `./gradlew test`.
   - **Observe:** profile-specific overrides now flow into tests deterministically. This is the seam that keeps Ex1's tests from accidentally pointing at a dev Postgres.

5. **Fail-fast**
   - **Do:** add `@Validated` to the properties class and `@field:Min(1)` (or similar) on `maxItems`. Set `app.max-items: 0` in yml and start the app.
   - **Run:** `./gradlew bootRun` — expect failure.
   - **Observe:** startup aborts with a binding validation error naming the property and the constraint — before any request is served. Java comparison for the interview: this is the same fail-fast argument as validating DB config at boot instead of at first query; the *typed properties + validation* mechanism is what Kotlin makes cheap.

6. **No secrets**
   - **Do:** add a value that must come from the environment: `app.staff-token: ${PHARMACY_STAFF_TOKEN:}` (empty default) — this stands in for the staff-auth token Ex1 will need. Confirm `git status`/`git diff` shows no real values, and `.gitignore` covers `.env` files.
   - **Run:** `PHARMACY_STAFF_TOKEN=dev-secret ./gradlew bootRun` and observe the resolved value in your startup log line; then run without it and see the empty default.
   - **Observe:** the pattern is one line and trivially auditable. The rule to say aloud: "the committed config contains structure and defaults; anything secret comes from the environment, and the app fails fast if a required one is missing" — note Ex1 should use a *required* form (no default) for DB passwords.

## Try this

Two failures, one insight. (a) Set `app.max-items: "abc"` (wrong type) and start — read the startup error naming the property and the type. (b) Set `app.max-items: 0` and start — the `@Validated` error fires. Now remove `@Validated` and start again: the app boots with a nonsense value and fails *later*, at request time, in a confusing place. The insight is the interview line: fail-fast at boot turns config bugs into a five-second fix instead of a production incident.

## Trade-off fork

**Option A — `@ConfigurationProperties`** (typed, grouped, bindable, validation-friendly): one class per config group; IDE metadata; testable.

**Option B — `@Value("${app.maxItems:10}")`** (one-liner per field): no class, no ceremony, but strings scattered through code, no type safety, no group concept, no validation.

Pick one and write 3–5 lines justifying it. Name the lost benefits: B is faster for a single value and keeps config adjacent to use; A costs a class and a registration line but makes the config *surface* reviewable — which is the thing Ex1's README must show. Nudge: A — a Product Engineer interview will ask "what does this service need configured?", and a properties class is the honest answer.

## Hints

- **Hint 1:** if binding silently fails (values stay at defaults), check the prefix matches yml exactly, the class is registered (`@ConfigurationPropertiesScan` or `@EnableConfigurationProperties`), and you're using kebab-case in yml. Also: binding happens on bean creation — a `@Bean` that reads properties too early sees defaults.
- **Hint 2:** for fail-fast with `@Validated`, both `jakarta.validation` annotations and `spring-boot-starter-validation` must be on the classpath; `spring-boot-configuration-processor` is for IDE metadata only, not binding.

## Checkpoint / success criteria

- All S-track magic values live in yml with defaults; endpoints behave unchanged.
- A `test` profile exists and an S04-style test asserts a profile-overridden value.
- `app.max-items: "abc"` and `app.max-items: 0` both fail at startup with readable errors.
- `git diff` shows zero secrets; the `${ENV_VAR:default}` placeholder is in place and demonstrated.
- Fork choice written down (S06 decisions log).

## Bottleneck & reflection questions

1. **System design:** which of today's keys would Ex1 actually keep, and which would be replaced by Postgres/Rabbit connectivity config? (Skim `../postgres/P01_schema_and_migrations.md` for the shape.)
2. **Failure handling:** why is fail-fast at boot preferable to a guard in code that logs "misconfigured" at request time? Where is late failure ever the right call?
3. **Simplicity:** a `data class` with four keys is easy — what does this pattern cost when the app grows to ten groups? Is the cost the same in Ex1?
4. **Patient experience:** can a config mistake ever change what a patient sees (e.g. `notice-window-minutes`)? How does that change your view of validation vs defaults?
5. What is the difference between a secret in yml and a secret in an env file that is gitignored — and which would a reviewer care about in Ex1's submission?

## Handoff

- **Next:** capstone `S06_timebox_readme.md` — your decisions log entry for the fork, the fail-fast story, and the env-placeholder rule all land in the README. After the S-track: `../postgres/P01_schema_and_migrations.md` is where these config keys become real (DB URL from env), and `../glue/X01_docker_compose_trio.md` adds Docker.
- **Related showcase:** `../../pharmacy-fulfillment/exercise_01_foundation.md` — it requires reproducible local setup; your profile + env-placeholder pattern is the seed of that reproducibility.
- **Interview line to say aloud:** "Configuration is a typed properties class with defaults and boot-time validation, so a bad value stops startup with the property named; secrets never live in committed config — they come from the environment with a fail-fast required placeholder, and the test profile keeps tests pinned to a known environment."

## Optional stretch

Add a `dev` profile with a visible startup log line that prints the resolved `app.*` values as structured JSON (one line — the full structured-logging discipline is `../glue/X02_structured_logging.md`), then run the app with `SPRING_PROFILES_ACTIVE=dev` and `SPRING_PROFILES_ACTIVE=test` and compare the output.
