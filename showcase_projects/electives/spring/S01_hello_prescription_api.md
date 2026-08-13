# S01 Hello Prescription API — Code-Along Elective

## Objective

Stand up a single-module Spring Boot application written in Kotlin that exposes the two patient-facing endpoints — `POST /prescriptions` and `GET /prescriptions/{id}` — with DTOs, bean validation, and an in-memory map as storage. The primary objective is to ship your first end-to-end Kotlin Spring slice and feel exactly where Java muscle memory helps and where it hurts (annotation targets, data classes, `val`).

## Time box

~2 hours, core track. Suggested split: scaffold 15 min, domain values 20 min, request DTO + validation 30 min, controller + view 30 min, curl/observe 25 min, trade-off fork + notes 10 min.

## Prerequisites

- `../kotlin/K01_prescription_value_objects.md` recommended (value objects, `data class`, `require`) but not blocking — S01 will re-touch it.
- JDK 17+ (21 fine), `curl`. No Docker needed: the S-track is Postgres-free and RabbitMQ-free by design.
- Showcase position: **before Exercise 1** (`showcase_projects/pharmacy-fulfillment/exercise_01_foundation.md`). This is the on-ramp; Ex1 is the real thing.

## Blog & curriculum links

- Primary: `posts/series-4-product-sse/01-patient-first-api.md` — read "The Patient-Facing Surface: Two Endpoints" and "Error Contracts: Four Answers".
- Secondary: `posts/series-1-kotlin/01-kotlin-for-java-developers.md` — "`val` Is the Default" and "Properties Are Not Just Public Fields".
- Coach-assessment gap attacked: no Kotlin experience (high-risk gap #1) and the "generate a UI early" scope instinct — this kata proves the backend patient surface first.

## Background & motivation

The blog post's core claim: the entire patient experience reduces to submit + status. This kata makes that true on your machine in Kotlin. It deliberately ignores persistence, RabbitMQ, SSE, a staff surface, idempotency, tests (that is S04), the error contract (S03), and config (S05). The in-memory map is honest throwaway storage — you are learning the HTTP + Kotlin boundary, not a data layer. If you finish and feel "this is small", that is the point: the patient slice *should* be small.

## Learning objectives

1. Scaffold and run a Kotlin Spring Boot project from start.spring.io with Gradle Kotlin DSL.
2. Write Kotlin `data class` DTOs and map them across the controller boundary.
3. Apply Bean Validation with correct Kotlin annotation targets (`@field:...`) and observe the 400 response.
4. Handle the Jackson + primary-constructor + defaults interaction without the Java bean conventions you're used to.
5. Return a deliberate 404 for a missing prescription and name what the current default body is missing (S03 will fix it).
6. Explain `val` + `data class` equality/`copy` in one sentence each — you will be asked.

## Warm-up

Read `posts/series-4-product-sse/01-patient-first-api.md` sections "The Patient-Facing Surface: Two Endpoints" and "Error Contracts: Four Answers" (5 min). Then probe yourself: name the three consumers and the single synchronous question the patient surface answers. If you can say "one status GET that is the correctness baseline", you have the frame this kata tests.

## System specification

- **Scope in:** one module; `POST /prescriptions`; `GET /prescriptions/{id}`; request DTO + `@Valid` validation; a `PrescriptionView` response; storage in a `ConcurrentHashMap`; a short `curl` script as evidence.
- **Scope out:** Postgres, RabbitMQ, SSE, auth, staff endpoints, idempotency, tests (S04), error-contract design (S03), configuration (S05), Docker.
- **Functional requirements (minimal):**
  - `POST /prescriptions` accepts `patientId`, a non-empty list of items (`medicationId`, `quantity > 0`); returns `201` with the new prescription id and initial status.
  - `GET /prescriptions/{id}` returns the view; a missing id returns `404`.
- **Constraints:** single Spring Boot module; in-memory repository only; no secrets; port 8080; no HTTP types in the domain package.

## Step-by-step code-along

1. **Scaffold**
   - **Do:** Go to start.spring.io, pick Kotlin + Gradle-Kotlin, Spring Boot 3.x, dependencies **Spring Web** and **Validation**. Unzip into `s01-hello-prescription-api/`. Open in your IDE.
   - **Run:** `./gradlew bootRun`, then hit `http://localhost:8080` with curl.
   - **Observe:** the embedded Tomcat starts; the default whitelabel error page answers on unknown paths. Note the generated `Application.kt` — `@SpringBootApplication` on an object-less `class`, no `public static void main` ceremony.

2. **Domain values**
   - **Do:** create `Prescription.kt` in a `domain/` package with `enum class PrescriptionStatus { SUBMITTED, AWAITING_APPROVAL }` (keep it tiny — the full state machine is K03's job) plus `data class Prescription(id, patientId, items: List<PrescriptionItem>, status)` and `data class PrescriptionItem(medicationId, quantity: Int)`.
   - **Run:** `./gradlew compileKotlin`.
   - **Observe:** `val` everywhere — nothing mutates in place. `data class` gives you `equals`/`hashCode`/`toString`/`copy` for free. Java habit to unlearn: no getters/setters, no `final class` boilerplate. Prefer `val` because a prescription's identity and status should be visible, not settable.

3. **Request DTO with validation**
   - **Do:** `data class SubmitPrescriptionRequest` with `patientId: String`, `items: List<ItemRequest>`, `clientSubmissionKey: String`. Annotate with `@field:NotBlank`, `@field:NotEmpty`, `@field:Positive` on quantities. Use the `@field:` use-site target.
   - **Run:** compile; then `bootRun`.
   - **Observe:** in Kotlin, annotations on constructor parameters land on the parameter unless you force the target — `@field:NotNull` is the pattern that actually puts the annotation where Bean Validation reads it. Get this wrong and validation silently does nothing: a signature you will remember because it cost you a bug.

4. **Controller — submit**
   - **Do:** `@RestController @RequestMapping("/prescriptions")` with `@PostMapping` + `@ResponseStatus(CREATED)`, signature `fun submit(@Valid @RequestBody request: SubmitPrescriptionRequest): PrescriptionView`. Assign a UUID, store in a `ConcurrentHashMap<String, Prescription>`, map to a view, return.
   - **Run:** `curl -s -i -X POST http://localhost:8080/prescriptions -H 'Content-Type: application/json' -d '{"patientId":"p1","items":[{"medicationId":"amox-10","quantity":1}],"clientSubmissionKey":"k1"}'`.
   - **Observe:** `201` with a JSON body containing `id` and `status`. Then send a payload with `"quantity": -3` — you get `400` with Spring's default `BindException` body. Look at it: it lists fields and messages. S03 will replace it with a contract; for now it exists.

5. **Controller — status**
   - **Do:** `@GetMapping("/{prescriptionId}")`; look up the map, return the view, and for a miss return a 404 — use `ResponseEntity<PrescriptionView>` or throw `ResponseStatusException(NOT_FOUND, ...)`.
   - **Run:** curl the id from step 4, then curl a random UUID.
   - **Observe:** the miss returns `404`. Note the body: empty or a bare string. An interviewer will ask "what does a patient actually receive on a 404?" — the answer today is "not much", and that gap is S03's whole job.

6. **View mapping**
   - **Do:** `data class PrescriptionView(id, status, items: List<ItemView>, submittedAt)`; keep the mapping as a small function — `fun Prescription.toView(): PrescriptionView` — rather than burying it in the controller.
   - **Run:** repeat the happy-path curl pair.
   - **Observe:** same payloads, but now the domain object and the wire shape are decoupled. Java habit to unlearn: this mapping is a pure expression-body function, not a `BeanUtils.copyProperties` call.

## Try this

Send three bad payloads in a row and read each 400 body: (a) `"items": []`, (b) `"quantity": 0`, (c) missing `patientId`. Then send the **same `clientSubmissionKey` twice** and observe you created two distinct prescriptions. That is a real product bug (duplicate submission) and a real interview line: "in-memory, no idempotency — documented gap, fixed in Ex1 with a unique constraint." Do not fix it here.

## Trade-off fork

**Option A — DTO at the boundary:** controller takes `SubmitPrescriptionRequest`, maps to a domain `Prescription`, returns `PrescriptionView`. Clean layers, more mapping code.

**Option B — domain object in the controller:** the controller takes/returns `Prescription` directly and serializes it.

Pick one, implement it, then write 3–5 lines justifying the choice for an interviewer. Name what you lost: B is less code but leaks storage-ish fields onto the wire and couples the contract to the domain shape; A costs a mapping function but lets the wire change without the domain changing. My nudge: A — Ex1 will add fields the patient must not see (internal ids, queue names).

## Hints

- **Hint 1:** Kotlin validation annotations need `@field:` targets (or use-site syntax `@field:NotBlank val ...`). If your 400 never fires, check this first — the build will *not* warn you.
- **Hint 2:** For the 404, `ResponseEntity` is easier to extend later than `@ResponseStatus`; if you instinctively reached for `java.util.Optional`, remember Kotlin prefers the nullable type — `map[id]` already returns `Prescription?`.

## Checkpoint / success criteria

- `./gradlew bootRun` starts on 8080.
- `POST` valid → `201` with id + status; `POST` invalid → `400` with field errors; `GET` known → `200`; `GET` unknown → `404`.
- No DB, no broker, no Docker in the project.
- You can say out loud what each of the three Kotlin vs Java differences (annotation targets, `data class`, `val`) changed in your code.
- Trade-off fork answer written down (it goes into S06's decisions log).

## Bottleneck & reflection questions

1. **Patient experience:** is the view returned by the status GET what a patient in a waiting room needs? What would you cut or add — and why would that change be justified at interview?
2. **Simplicity:** the blog names three surfaces and says you built the smallest one. What did you *not* build, and was that restraint correct?
3. **System design:** where does the mapping between DTO, domain, and view live, and who owns it if the wire format changes?
4. **Failure handling:** today's 400 and 404 bodies are framework defaults. What is wrong with them as a *contract* for a client that retries?
5. What is the weakest part of this slice if a reviewer opens it cold — and what would S02/S03 fix first?

## Handoff

- **Next:** `S02_layered_slice.md` (split into controller → service → repository with DI), then `S03_error_mapping.md`, `S04_api_tests.md`, `S05_config_and_profiles.md`, and capstone `S06_timebox_readme.md`.
- **Related showcase:** `../../pharmacy-fulfillment/exercise_01_foundation.md` — this exact submit/status pair is the core of its patient contract; everything you build here transfers directly.
- **Interview line to say aloud:** "The patient surface is two endpoints — submit and a status GET that is the correctness baseline. Validation is at the boundary, the error contract is deliberately deferred to a later step, and I have documented that in-memory storage means data is lost on restart."

## Optional stretch

Add `clientSubmissionKey` handling that returns the existing prescription's view instead of creating a duplicate — and write one sentence about the race this still has in-memory (two simultaneous first-seen keys). That sentence is worth more than the implementation, because Ex1 requires exactly this decision documented.
