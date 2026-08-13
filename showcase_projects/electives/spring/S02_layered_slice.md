# S02 Layered Slice — Code-Along Elective

## Objective

Refactor the S01 controller into a three-layer slice — controller → service → in-memory repository — wired by constructor injection, so each layer has one responsibility and the service can be constructed and tested without Spring. The primary objective is to feel how Kotlin's constructor model changes dependency injection (no `@Autowired`, no setters) and to build the exact skeleton Ex1 will grow.

## Time box

~2 hours, core track. Suggested split: repository 30 min, service 30 min, controller rewiring 25 min, interface decision + wiring experiment 25 min, curls + fork notes 10 min.

## Prerequisites

- `S01_hello_prescription_api.md` — you are refactoring its code, not starting over.
- JDK 17+, no Docker (still no Postgres/RabbitMQ in the S-track).
- Showcase position: **before Exercise 1**. This is the layering baseline the showcase builds on.

## Blog & curriculum links

- Primary: `posts/series-4-product-sse/01-patient-first-api.md` — "The Three Surfaces, Not One" (why the patient/staff/worker separation is not ceremony; your service layer is where that discipline starts).
- Secondary: `posts/series-1-kotlin/01-kotlin-for-java-developers.md` — "Practical Translation Rules" (which Java habits to keep and which to drop when structuring Kotlin classes).
- Coach-assessment gap attacked: Kotlin syntax/idiom friction (gap #1); also rehearses the layered-system-design answers the interviewer will probe ("where does the business rule live?").

## Background & motivation

S01 proved you can ship HTTP in Kotlin; this kata proves you can structure it. The layered slice is deliberately boring: three classes, one dependency direction, in-memory storage. It deliberately ignores databases (P01), the error contract (S03 — the service's return types change there), interfaces-by-default (a Java habit this kata attacks), and transactions. If you are tempted to add an interface per layer "just in case", this kata is where you decide whether that habit earns its lines — and you will write down that decision for S06.

## Learning objectives

1. Split controller/service/repository without over-abstracting.
2. Use constructor injection with `val` primary-constructor params — and see why Spring needs no `@Autowired` for a single constructor.
3. Decide deliberately between a concrete class and an interface (and name when each pays).
4. Move id generation and storage ownership into the repository (`ConcurrentHashMap`, `UUID`).
5. Keep service signatures honest for now — nullable returns and clear "what S03 will replace" notes.
6. Prove the service layer is constructible in a plain unit test with no Spring context.

## Warm-up

Re-read the "Three Surfaces" section of `posts/series-4-product-sse/01-patient-first-api.md` (3 min). Then probe your existing Java muscle memory: draw the dependency graph of your last service on paper — count the interfaces that have exactly one implementation, and write one sentence for each about what the interface actually buys. That list is what you are re-examining in Kotlin.

## System specification

- **Scope in:** same two endpoints, same behavior; `PrescriptionRepository` (in-memory map); `PrescriptionService` (decisions); controller stays thin; constructor injection throughout.
- **Scope out:** Postgres, RabbitMQ, SSE, caching, transactions, the error contract (S03), tests (S04).
- **Constraints:** single module; one dependency direction (controller → service → repository, never upward); repository owns storage and id generation concerns; service owns business decisions; controller owns HTTP concerns (status, body). No HTTP imports in service or repository.

## Step-by-step code-along

1. **Introduce the repository**
   - **Do:** `class PrescriptionRepository { private val store = ConcurrentHashMap<String, Prescription>(); fun save(p: Prescription); fun findById(id: String): Prescription?; fun all(): Collection<Prescription> }` — move the map and the `UUID.randomUUID().toString()` id assignment here. In Kotlin, `map[id]` returns `Prescription?` — the nullable type is your contract for absence.
   - **Run:** `./gradlew compileKotlin`, then `bootRun` + one happy-path curl.
   - **Observe:** behavior is unchanged — you only moved where things live. That is the definition of a safe refactor.

2. **Introduce the service**
   - **Do:** `class PrescriptionService(private val repository: PrescriptionRepository)` with `fun submit(request: SubmitPrescriptionRequest): Prescription` (builds the domain object, delegates persistence) and `fun byId(id: String): Prescription?`.
   - **Decision:** who generates the id — service or repository? Nudge: repository owns storage identity; the service should not care *how* ids are minted. But note the alternative is defensible; write your choice down.
   - **Run:** `bootRun`, run the S01 curl set.
   - **Observe:** the controller now does nothing but HTTP shape: receive DTO, call service, map view, set status. If your controller still contains an if/else business rule, you haven't finished this step.

3. **Rewire the controller**
   - **Do:** replace the controller's inline logic with `class PrescriptionController(private val service: PrescriptionService, private val repository: PrescriptionRepository?)` — no, drop the repository; the controller needs only the service.
   - **Run:** compile, boot, curl.
   - **Observe:** no `@Autowired` anywhere. Kotlin + Spring: a single primary constructor means the container injects by type automatically. Java habit to unlearn: `@Autowired` on fields/setters, and `lateinit` for constructor deps — you do not need either here. All dependencies are `val`, so they can never be null *by construction*.

4. **Interface or concrete class?**
   - **Decision (the fork's warm-up):** does `PrescriptionRepository` need an interface? Implement the simple version first (concrete class), then add an interface only if a step below forces one. Write one line: "I added/didn't add an interface because…".
   - **Run:** boot + curls.
   - **Observe:** Spring wires concrete classes fine; the app is identical. The thing an interface buys you here is *test seam* and *decorator* — not DI itself.

5. **Add a second implementation and watch the wiring break**
   - **Do:** (see Try this below — this is the experiment) create `SlowLoggingRepository` implementing your repository interface, and register it as a bean.
   - **Run:** `bootRun`.
   - **Observe:** Spring now has two candidates and startup fails with a `NoUniqueBeanDefinitionException` — the error message names the beans. Decide the fix (see hints) and write one line about when qualifiers are a code smell.

6. **Prove the service is plain Kotlin**
   - **Do:** in `src/test/kotlin` (you can defer to S04, but try it now): `class PrescriptionServiceTest { @Test fun `submit stores and lookup finds`() { val service = PrescriptionService(PrescriptionRepository()); ... } }` — no Spring annotations at all.
   - **Run:** `./gradlew test`.
   - **Observe:** the test constructs the graph by hand in two lines. This is the payoff of constructor injection: your service is testable without the container, which is exactly the claim you will make in interviews.

## Try this

Create the `SlowLoggingRepository` from step 5 (wrap your map repository, print a line per call) and add it as a `@Bean` next to the component-scanned repository. Start the app and read the failure. Then remove the extra bean and instead inject `SlowLoggingRepository` *explicitly* in one place — observe how a single explicit choice resolves the ambiguity. The lesson: ambiguity appears the moment you have two implementations; a qualifier is a decision, not decoration.

## Trade-off fork

**Option A — constructor injection** (Spring + Kotlin idiomatic): dependencies are `val` constructor params; the graph is visible and testable without Spring; the container needs no reflection tricks beyond the primary constructor.

**Option B — field injection** (familiar from Java Spring): fewer constructor changes, but dependencies are hidden mutable state; a field can be null; tests need Spring or reflection to populate it; harder to see the graph.

Pick one and write 3–5 lines justifying it for an interviewer. Name the lost benefits: B is less churn when adding a dependency, and A means every new dependency changes the constructor (which Kotlin callers will notice — that is a feature, not a bug). Nudge: A — in Kotlin, `val` + constructor makes the dependency graph impossible to corrupt.

## Hints

- **Hint 1:** If `bootRun` fails with "Parameter 0 of constructor … required a bean", you referenced a class that is not a Spring bean (missing `@Service`/`@Repository`, or you injected a concrete class that lives outside component scan). Read the full error — Spring tells you exactly which bean is missing.
- **Hint 2:** For the two-implementation experiment: `@Primary` on one bean, or `@Qualifier("slowLogging")` on the injection point, or register both as named beans and inject by name. `@Qualifier` on a constructor parameter in Kotlin goes *on the parameter*: `fun foo(@Qualifier("slowLogging") repo: Repository)`.

## Checkpoint / success criteria

- Three layers exist; dependency direction is strictly downward; controller has no business rules.
- No `@Autowired`, no `lateinit` dependencies; the service is constructible in a plain JUnit test.
- All S01 curls pass unchanged.
- One-line justification written for the interface decision and the injection fork (feeds S06's decisions log).

## Bottleneck & reflection questions

1. **Simplicity:** how many interfaces does this slice have, and is each one earning its lines? What would a reviewer on a 2-hour time budget think?
2. **System design:** where would a business rule like "quantity must be positive" live in this slice, and where does it live today (annotation vs service)? Is that the right split?
3. **Failure handling:** the repository can throw today (e.g. a bad cast you don't have yet). What does the client see? Is that acceptable for S03 to inherit?
4. **Patient experience:** did any of this refactor change what the patient receives? If not, is that evidence of good layering or of the layer being ceremony?
5. Which of these layers will grow when Ex1 adds Postgres (P01) and RabbitMQ (R02), and which will stay the same size?

## Handoff

- **Next:** `S03_error_mapping.md` — the service's nullable/naive returns get replaced by sealed outcomes; your layering is what makes that change local. Then `S04_api_tests.md`, `S05_config_and_profiles.md`, capstone `S06_timebox_readme.md`.
- **Related showcase:** `../../pharmacy-fulfillment/exercise_01_foundation.md` — Ex1 uses this same three-layer slice with a Postgres-backed repository; the "simulated staff" actors sit above the same service boundary.
- **Interview line to say aloud:** "I structure the slice as controller, service, and repository with constructor injection — the service is a plain Kotlin class I can construct and test without Spring, and I add interfaces only when they buy a test seam or a decorator, not for DI ceremony."

## Optional stretch

Add a `LoggingRepositoryDecorator` (same interface, delegates to the map repository, logs every call) and make the *controller* depend on the interface while the *service* stays concrete — then write one paragraph on where the interface boundary should sit in Ex1 once Postgres arrives (P01's decision, but you can pre-gamble).
