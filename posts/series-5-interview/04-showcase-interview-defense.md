# Defending the Pharmacy Challenge in a Product Engineer Interview

This is the capstone of the interview communication series. The three posts before it each built one muscle: a walkthrough structure that starts from patient value (`01-take-home-walkthrough.md`), a four-part tradeoff language that never overclaims (`02-tradeoffs.md`), and a behavioral story about a proprietary database that turns leadership experience into measurable impact (`03-proprietary-database-story.md`). This post puts all three muscles under attack at once.

What follows is a full mock interview: one prescription system, one skeptical Product Engineer interviewer, and the candidate defending the entire stack — Kotlin, PostgreSQL, RabbitMQ, and SSE — with the proprietary-database story as the closing act. Read it as a script to rehearse against, not a transcript to memorize. Every answer follows the same pattern: **claim, mechanism, evidence, honest limitation.** Where the candidate stumbles, the post says so — because an interviewer will find those joints faster than you will.

## The setup: forty-five minutes, four lenses

The mock runs like the real thing. The interviewer has read the README and skimmed the code. They have not run the system. They are checking one hypothesis: *does the person who built this understand the user, the design, and the failure modes well enough to own it in production?* They will interrupt. They will attack the strongest claims first. And they will judge every answer against the challenge's own evaluation areas — user experience, simplicity, system design, and failure handling.

The walkthrough below is staged in the order the real interview tends to follow: opening statement, code review of the Kotlin, attacks on the database invariants, failure-injection on the messaging, a skeptical pass over the realtime layer, a demand for proof, a scope negotiation, and finally the behavioral pivot. Total conversation time, about forty minutes.

## Act 1 — The opening: patient value before infrastructure

**Interviewer:** "You have five minutes. What did you build?"

The opening is the ninety-second statement rehearsed in the walkthrough post, and it must land before any topology talk. In the mock, the candidate opens with Ana — the patient from the series-4 showcase — not with RabbitMQ:

> "The core user is a patient in the waiting room. Ana hands in a prescription for amoxicillin at 09:14, and from that moment she needs to know what is happening: submitted, inventory verified, approved, being packaged, ready. The challenge's own assessment criteria put patient experience first, so the system is built around one synchronous question — what is the status of my prescription? — with everything else simulated. Pharmacists and packagers are automated workers consuming work queues, because internal UIs for them would eat the time budget without improving the assessed experience. Every other decision in the system serves that one question."

**Interviewer:** "Why no pharmacist UI? You built a system for a pharmacy and the pharmacist types into nothing."

This is the first probe and it is a scope test, not a feature request. The answer must anchor to the brief, not to taste:

> "The challenge says a clean, working core beats extra features, and it defines the pharmacist and packager as secondary actors. A pharmacist UI is presentation over a queue of two decisions — approve or reject. I modelled that queue as an endpoint plus a simulated worker, which proves the workflow end to end. Given another week, the same decisions could drive a real screen; given two hours more, a screen would have proven nothing the backend does not already prove. The patient's experience is the assessed one, and it is fully observable without any internal UI."

The interviewer is listening for two things here: that the candidate read the brief, and that the cut was made deliberately — with the unlock condition already named (the queue contract is the seam; a UI is a client of it).

## Act 2 — Kotlin decisions: defending code you wrote in a new language

**Interviewer:** "You wrote this in Kotlin. You told me you come from Java. Walk me through the domain model and tell me what Kotlin actually bought you."

This is the code-review act, and it is the highest-risk segment of the whole interview, because the assessment flagged Kotlin as the candidate's biggest technical gap. The defense has to be about *judgment*, not fluency: the candidate chose Kotlin because the challenge required it and the domain rewards it, and the specific choices were made for reasons that survive scrutiny.

The candidate walks the state machine first, because it is the one piece of code that encodes the whole product:

```kotlin
sealed interface Prescription {
    val id: PrescriptionId
    val patientId: PatientId
    val status: PrescriptionStatus
    val version: Long
}

data class Submitted(
    override val id: PrescriptionId,
    override val patientId: PatientId,
    val lines: List<MedicationLine>,
    override val status: PrescriptionStatus = SUBMITTED,
    override val version: Long = 1,
) : Prescription

data class Approved(
    override val id: PrescriptionId,
    override val patientId: PatientId,
    val lines: List<MedicationLine>,
    override val status: PrescriptionStatus = APPROVED,
    override val version: Long = 2,
    val approvedBy: PharmacistId,
) : Prescription

fun Prescription.approve(by: PharmacistId): Result<Approved> =
    when (this) {
        is Submitted -> Result.success(Approved(id, patientId, lines, approvedBy = by))
        is Approved, is Rejected, is Fulfilled ->
            Result.failure(IllegalTransitionError(status, APPROVED))
    }
```

> "Three things Kotlin bought me. First, the state machine is a sealed hierarchy, so an illegal transition is not a convention — it is a type error and an exhaustive `when`. There is no string status that a bug can smuggle past. Second, nullability is a contract: `findPrescriptionById` returns `Prescription?`, so 'not found' is visible in the signature instead of lurking as an implicit null. Third, the domain is immutable value objects, which matters most where the workflow is concurrent: `Approved` cannot be mutated by a packaging worker that received a stale reference."

**Interviewer:** "Isn't that ceremony? A Java enum plus a switch would do the same thing."

This is where the candidate must use the tradeoff language from post 2 instead of defending Kotlin like a fan. The four-part move:

> "Assumption: the workflow has a small, closed set of states and the cost of an illegal transition is a patient-visible lie — a prescription shown as fulfilled that was never packaged. Alternative: a Java-style enum with a central transition guard, which is one object instead of five. Chosen design: the sealed hierarchy, because it distributes the legal transitions across the states themselves and makes the compiler the reviewer. Sacrificed property: the design is more types and more files than an enum, and extending it — say, adding `CANCELLED` — touches the model and every exhaustive `when`, which is a deliberate price: every caller is forced to decide what cancellation means."

The interviewer hears: assumption, alternative, choice, sacrifice — and a candidate who knows what the fancier design costs. One honest sentence closes the segment, because the interviewer will smell defensiveness around the language gap otherwise:

> "And to be direct about my level: Kotlin is the part of this submission I was least fluent in at the start, which is why the model is deliberately small. I would rather defend twenty lines of idiomatic Kotlin than two hundred lines that are Java wearing Kotlin punctuation."

## Act 3 — PostgreSQL invariants: the concurrency attacks

**Interviewer:** "Two pharmacists open the same prescription and both hit approve. What happens?"

The candidate has rehearsed this exact attack. The answer is the conditional update, delivered as a mechanism, not as a SQL recitation:

> "The transition is one atomic statement. `UPDATE prescriptions SET status = :to WHERE id = :id AND status = :from` returns the affected row count; the service commits only if it is exactly one. The second pharmacist's update matches zero rows, because the first one moved the status, and the API returns `409 Conflict` with the current state. The state machine in code and the conditional update in the database are the same invariant enforced at two boundaries — the second pharmacist loses the race and is told the truth about it."

```sql
UPDATE prescriptions
   SET status = :to, updated_at = now()
 WHERE id = :id AND status = :from
```

**Interviewer:** "Now the harder one. Two submissions arrive for the last unit of the same medication, at the same moment. One of them has to be rejected. Show me you will not oversell it."

This is the inventory-reservation attack from series 2, and it is where candidates who describe "the database handles it" get separated from candidates who can write the statement:

> "Reservation is an atomic decrement inside the same transaction that records the prescription. `UPDATE medication_inventory SET reserved = reserved + 1 WHERE medication_id = :id AND stock - reserved >= 1` — again decided by affected rows. Under PostgreSQL's `READ COMMITTED`, the two statements serialize: the second update re-evaluates the predicate against the first transaction's committed row, sees zero availability, matches zero rows, and that prescription is rejected for insufficient stock. There is no read-then-write window, and there is no separate `SELECT FOR UPDATE` needed, because the single statement is itself the lock."

**Interviewer:** "Why not `SELECT FOR UPDATE`? You hear people say it everywhere."

The candidate resists the temptation to agree, and instead states when the row lock is the right tool:

> "For a single-row invariant, the conditional update is less code and holds the lock for one statement instead of a whole transaction. I reach for `SELECT FOR UPDATE` when the check involves multiple rows that must stay consistent under one lock — for example, if a future line-item packing flow needed to verify stock across several inventory rows at once. My default is the narrowest primitive that holds the invariant. The tradeoff: the conditional update assumes the decision fits in one row and one predicate, and the moment a workflow needs a multi-row check, the single-statement trick stops being enough."

The interviewer's tells in this act: they want to hear *READ COMMITTED* spoken correctly, affected rows as the decision, and a candidate who knows the boundary between a conditional update and an explicit lock. All three landed.

## Act 4 — RabbitMQ failure handling: the crash-window tour

**Interviewer:** "The packaging worker receives a command, writes 'PACKAGING done' to PostgreSQL, and crashes before acknowledging. What happens — and what happens to Ana's status?"

This is the single most convincing moment available in the whole defense, because the assessment's failure-handling criterion is exactly what this question measures. The candidate walks the window deliberately, claim by claim:

> "Two things happen, and they happen in the right order. First, the business effect is already durable: the worker committed the state change and the outbox event in one transaction, and inserted its inbox row in the same commit. Second, the acknowledgement never arrived, so RabbitMQ redelivers the message to a surviving worker. The new worker runs the same transaction; the inbox's unique constraint on the event ID makes the duplicate a no-op; the effect is applied once. Then — and only then — the ack is sent. The invariant, in one sentence: no business effect without a durable record, and no acknowledgement before the effect is durable. Ana's status was never wrong — it moved to the correct state once, and a duplicate attempt to move it again was rejected by the database."

**Interviewer:** "And the relay? It publishes the outbox row, RabbitMQ confirms it, and the relay crashes before marking the row published."

The candidate keeps the four-part discipline, and this time the answer is about duplicate *publication*:

> "On restart the relay republishes the row, because its own bookkeeping says unpublished. That message reaches the queue twice — the broker accepted both. The consumers absorb it the same way the worker just did: same stable event ID, same inbox constraint, one business effect. This is why I never claim exactly-once. At-least-once delivery, duplicates possible, duplicates handled by idempotent consumers — that is the honest sentence, and it covers every crash window in the system: relay crashes, consumer crashes, broker restarts."

**Interviewer:** "So what can you actually still lose?"

The question that separates candidates who understand their own system from candidates who memorized vocabulary. The answer has to make the distinction between losing a *notification* and losing a *decision*:

> "The durable decision is never lost — it is committed to PostgreSQL before any message exists. What can be lost is an event notification: a crash between the commit and the outbox insert leaves a state change with no event published, so Ana's SSE stream might never show that particular transition. The synchronous status GET is the authority and it shows the truth; the missing event is a hole in the realtime channel, not in the record. And a message that fails permanently — say, a command that can never succeed — is bounded: retries with a TTL through a delayed retry queue, then dead-lettering to a poison queue I can inspect and replay with the retry count intact. I treat the retry topology as part of the design, not as an afterthought."

The interviewer hears the assessment-flagged vocabulary — publisher confirms, outbox, inbox, manual ack, bounded retry, DLQ — each attached to a crash window and a recovery outcome. No phrase floated unattached.

## Act 5 — SSE limitations: the realtime layer under skepticism

**Interviewer:** "The realtime part. How do you know Ana only ever sees Ana's events, and what happens when her phone drops the connection?"

The candidate opens with the correction that the assessment itself flagged, because owning it is the strongest possible version of the truth:

> "I should be upfront: my first sketch made each SSE connection a RabbitMQ consumer. That was wrong — N patient connections would have been N competing consumers on the work infrastructure, each needing its own acknowledgement lifecycle, and the queue would have become a patient-facing distribution system. What I built instead is a derived view: RabbitMQ broadcasts status events once into the system, the status history is the log, and each SSE connection is served by the API reading that history, filtered by the authenticated patient's ID. One source, per-connection filtering. A patient sees their own events because the query is scoped by patient ID — isolation is a database filter, not a security property bolted onto the stream."

```sql
SELECT sequence_no, status, occurred_at
  FROM status_history
 WHERE prescription_id = :id
   AND sequence_no > :lastEventId
 ORDER BY sequence_no
```

> "Reconnects are the same query: events carry their history sequence number as the SSE `id:`, and the client sends `Last-Event-ID` on reconnect, so the stream resumes from exactly the next event — nothing missed, nothing replayed. Ordering is guaranteed per prescription by the monotonic sequence in the history, and it survives redelivery chaos in the queue, because the stream reads the log, not the arrival order."

**Interviewer:** "So why not skip SSE entirely? What does it actually buy you?"

This is the limitation question, and the honest answer is the whole point of the series-4 framing:

> "SSE buys latency: Ana sees 'ready' the moment it exists instead of whenever she next polls. It buys nothing in correctness — the status GET is the baseline and the truth, and if SSE were deleted the product would still be correct, just not instant. Its limitations are real and I accept them: each connection holds a history query and a server-side filter, so a single instance fans out to a modest number of concurrent patients before I would need to shard history reads horizontally; there is no backpressure the way a queue provides it; and there is no cross-connection recovery if the process restarts beyond `Last-Event-ID`. Realtime is an enhancement with a named scale boundary — not a guarantee."

The interviewer's probe about two patients on the same stream is pre-empted by the SQL: the filter is in the query. The probe about "what if SSE never existed" is pre-empted by the baseline sentence. Both were in the rehearsal script, and both landed.

## Act 6 — Test evidence: prove, don't describe

**Interviewer:** "Everything you just told me was a claim. How do I know any of it is true?"

The candidate switches from narration to evidence, grouping tests by the claim they prove — the exact grouping from the walkthrough post:

> "Each claim maps to a test that runs against the real system, not a mock. The state machine's legal and illegal transitions are exhaustive unit tests. The inventory race — two concurrent submissions for the last unit — is an integration test against a real PostgreSQL in Testcontainers, because an in-memory fake cannot prove row locking or affected-row behavior. The duplicate-absorption claims are broker tests: redelivery after crash-before-ack, retry TTL, dead-letter arrival, and duplicate publication through the relay, all against a real RabbitMQ. The end-to-end test drives Ana's whole journey — submit, verify, approve, package, fulfil — and asserts the status history. The SSE tests reconnect with `Last-Event-ID` and assert sequence continuity, plus a two-patient isolation test that fails if either stream leaks. The failure tests are the priority: they are the ones that would catch a production regression."

**Interviewer:** "You keep saying 'real' — why did you reject an in-memory fake for the database in this project? You clearly know the argument from both sides."

This is the junction the proprietary-database story was built for — the candidate connects the two experiences without blurring them:

> "Because I learned that lesson on a previous project. On the proprietary database I built a fast in-memory approximation plus deterministic seed and prune tooling so the team could iterate locally and in CI — and I kept validating against the real database through its REST interface and later ODBC, because the approximation could never prove isolation or locking. Same split here: Testcontainers gives a real PostgreSQL and a real RabbitMQ in CI for the behavior that matters, and deterministic seed data gives reproducible workflow scenarios everywhere else. The in-memory fake is for iteration speed; the real system is for truth — and the two regimes must never be confused."

The interviewer nods at this because it is a *pattern*, not a project anecdote: the candidate's past produced a current engineering practice, and the practice is visible in the submission.

## Act 7 — Scope tradeoffs: the negotiation

**Interviewer:** "You had five hours. I'm telling you you have two. What dies?"

The candidate applies the scoping judgment from series 4, cut by cut, each with the seam that would unlock it later:

> "Two hours buys the honest core: the REST surface for Ana, the state machine with conditional transitions, inventory reservation, the status GET as the source of truth, simulated workers — but on HTTP polling or a very thin queue, no outbox, no DLQ, no SSE. That is a correct product; it is just not a resilient or instant one. The five-hour version is what you see: outbox relay, publisher confirms, manual acknowledgements, bounded retries and dead-lettering, idempotent consumers, SSE with reconnect replay, and the failure tests. What I would still cut at five hours: real authentication — the seam is already designed, since SSE isolation is server-side filtering and auth is a drop-in boundary; a pharmacist UI — the queue contract is the seam; and any exactly-once claim — RabbitMQ does not offer it and the inbox is the honest answer. Order of cuts: product value first, reliability second, operations third — the same order as my opening statement."

The interviewer hears the four-part structure three times in one answer: assumption (two-hour budget), alternatives (what dies first), choice (what survives), sacrifice (what the two-hour version lacks — and what it would cost the patient).

## Finale — The behavioral pivot: the proprietary-database story

**Interviewer:** "The challenge is the same judgment pattern as your real job. Tell me about a time you unblocked a team when you could not control the dependency."

This is the prepared STAR answer, delivered in its ninety-second form — clean, with the qualifiers intact:

> "We were required to build on a proprietary database with no local or containerized option, weak documentation, and access delays that would have blocked the whole team's start. My task was to keep a team of seven or eight developers productive in parallel, and to reduce the risk of discovering data-layer problems late. I started by defining database-agnostic contracts for the data layer, so nothing above it depended on proprietary behavior; then I built an in-memory engine with deterministic seed and prune tooling, giving every developer the same reproducible local, UI, and CI testing experience. To keep us honest, the service was also validated against the real database through its REST interface, and later through ODBC — integration truth was deferred, never absent. All eight developers were productive from the first sprint. The system reached production serving approximately 50 users at around 100,000 event writes per hour, with load testing planned for approximately 500,000. What I took from it: fast local feedback and authoritative integration validation are different things and you need both — and external dependency risk must be made visible early, not absorbed quietly."

**Interviewer:** "What would have happened without your approach? And what would you do differently if you had it again?"

The two probes — impact and reversibility — come back to back, and the candidate has prepared both:

> "Impact: the team would have started only when database access arrived, and wrong assumptions would have been discovered at integration time, when they cost the most. Instead, eight people worked from the first sprint and surprises were caught in a controlled validation phase. And with the same assignment again, I would bias even harder toward early real-database validation — the in-memory engine earned its keep for iteration, but the real system was the source of truth, and the earlier the first contact with it, the cheaper every later surprise. The lesson transfers: I schedule real-system validation early on every project now, and I treat the fast approximation as the accelerator, never the authority."

**Interviewer:** "And what does that have to do with this challenge?"

The pivot sentence — rehearsed, one breath, and it closes the loop:

> "Everything — the testing split is exactly what I am using here. Deterministic seed data and fast local tests for iteration; real PostgreSQL and real RabbitMQ integration tests as the authority on actual behavior. The proprietary database taught me that the in-memory approximation was for speed and the real system was for truth, and this challenge is that lesson with better tooling."

## The debrief: what the mock actually proved

Run the full arc through the pattern and the structure is visible:

| Act | Interviewer's attack | The defense that landed | The honest limitation stated |
| --- | --- | --- | --- |
| Opening | "Why no pharmacist UI?" | Patient-first scope anchored to the brief | Internal UIs deferred behind the queue contract |
| Kotlin | "Isn't that ceremony?" | Sealed state machine = the API contract | More types; every `when` must handle each state |
| PostgreSQL | "Two orders, one unit left" | Conditional update + affected rows under `READ COMMITTED` | Multi-row invariants need explicit locks |
| RabbitMQ | "Worker crashes after commit" | Ack-after-durable-effect + inbox for duplicates | At-least-once; notifications can be lost |
| SSE | "Reconnect? Isolation?" | Derived view from history, `Last-Event-ID`, server-side filter | Single-instance fan-out boundary |
| Tests | "Prove it" | Testcontainers evidence per claim | In-memory fakes cannot prove real behavior |
| Scope | "You have two hours" | Core stays correct; reliability and SSE cut with unlock seams | Two-hour version is slower and less resilient |
| Behavior | "Unblock a team" | STAR with disciplined numbers | 500,000/hour was planned, not measured |

Nothing in that table claims perfection. The candidate admitted the SSE-as-consumer mistake, admitted the Kotlin fluency gap, and attached qualifiers to every number. That is the intellectual honesty the kick-off prompt demands — and it is the trait interviewers weight most, because a candidate who states limitations precisely can be trusted with guarantees.

## Interview takeaway

A defense of the pharmacy challenge is not a recital of the four series that preceded this one. It is the same story told at every altitude: Ana's journey, the state machine that protects it, the database statements that keep it safe under concurrency, the crash windows that must never lose a durable decision, a realtime channel that is an enhancement with named limits, tests that prove each claim, cuts that are deliberate, and a behavioral story that shows the same judgment shaped real outcomes. Run the mock out loud, attack yourself with the interviewer's questions, and rehearse the two sentences that carry the most weight — the at-least-once sentence and the pivot sentence to the proprietary-database story. When you can defend this system the way you defended that database, you are not preparing for the interview anymore; you are ready for it.
