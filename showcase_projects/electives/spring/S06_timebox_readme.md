# S06 Time-Box README — Code-Along Elective

## Objective

Write the README that documents the S-track API's two-hour versus five-hour cut line — scope-in, deliberate gaps, decisions log, failure windows, and runnable evidence — so that a reviewer can verify the claims in 60 seconds. The primary objective is to practice the product-scoping defense from the time-box post on a system you actually built, before Exercise 1 forces the same discipline at larger scale. This is the capstone of the S-track: no new code.

## Time box

~1 hour, core track (capstone). Suggested split: warm-up + gap inventory 15 min, cut-line table 15 min, failure windows + decisions log 15 min, evidence + clean-checkout check 15 min.

## Prerequisites

- `S01_hello_prescription_api.md` through `S05_config_and_profiles.md` — you are documenting a real repo, not a hypothetical.
- Showcase position: **before Exercise 1** — the README you write here is the template Ex1's README will follow (that is the "no — documented gap" column's whole point).

## Blog & curriculum links

- Primary: `posts/series-4-product-sse/04-time-box-scoping.md` — "Two Submissions, Not Two Endpoints of One" and "Documenting Limitations: The README Is Scope".
- Secondary: `posts/series-5-interview/01-take-home-walkthrough.md` — "Deliberate Omissions: the most underrated section" and the walkthrough ordering (patient first, infrastructure last).
- Coach-assessment gap attacked: production judgment and interview defense (Track F skills) — the scoping story is where a Lead candidate is either convincing or generic.

## Background & motivation

The challenge text says: *"we would rather see clean, working code than extra features. If you run out of time, submit what you have and note what you would have done next."* The time-box post sharpens that into a rule: the two-hour submission is a *different plan*, not a half-finished five-hour plan — and the README is where that difference becomes visible. Your S-track repo is small enough that every omission is inspectable; writing its README now forces you to name them. This elective deliberately ignores architecture diagrams beyond one sentence, ADR-formalization, and any new feature — the deliverable is prose, tables, and evidence about a system that already exists.

## Learning objectives

1. State the two-hour slice of the S-track API in one paragraph, patient-first.
2. Build a cut-line table (2h vs 5h) with a real "No — documented gap" column, mirroring the post's template.
3. Write failure windows precisely: what can happen, in one sentence each, without proposing fixes.
4. Log every trade-off fork chosen in S01–S05 as a decisions log with two-line justifications.
5. Include runnable evidence: exact commands, curl transcripts, `./gradlew test` summary.
6. Rehearse the series-5/01 walkthrough structure against your own README.

## Warm-up

Read `posts/series-4-product-sse/04-time-box-scoping.md` sections "The Two-Hour Slice: Correct Before Fancy" and "Documenting Limitations: The README Is Scope", then the "Deliberate Omissions" section of `posts/series-5-interview/01-take-home-walkthrough.md` (5 min). Probe: open your S-track repo and list every thing you knowingly did not build — the in-memory map, no idempotency, no auth, no staff surface, no DB, no broker, one instance. That list is the skeleton of your README.

## System specification

- **Scope in:** a `README.md` in the S-track project root with: what-and-why (one paragraph), run instructions (60-second clean-checkout path), API contract table, error contract table, cut-line table, failure windows, decisions log, evidence (curl transcript + test summary), next steps. Optional: walkthrough rehearsal notes.
- **Scope out:** new code, architecture diagrams, ADR files (unless the fork lands there), claims about production capacity, promises that gaps are "coming soon" without a pointer to the elective or Ex1 step that closes them.
- **Constraints:** every claim verifiable by running the commands in the README; every omitted feature named in a table cell, not buried in prose; target ≈150 lines (soft).

## Step-by-step code-along

1. **What and why**
   - **Do:** open the README with a one-paragraph statement that leads with the patient (per series-5/01's opening): what the API does for the person in the waiting room, and the single synchronous question it answers.
   - **Run:** read it aloud; delete any sentence that is about your stack rather than the user.
   - **Observe:** if the first paragraph survives that filter, it is the interview opener you will reuse verbatim.

2. **Run instructions**
   - **Do:** write the exact commands: JDK version, `./gradlew bootRun`, the three curls (happy POST, GET, bad payload), `./gradlew test`. Do not assume `curl -j` flags — keep it minimal and honest.
   - **Run:** from a clean checkout (or after `git stash`) follow your own instructions top to bottom with a stopwatch.
   - **Observe:** if it takes more than 60 seconds or requires a question answered, fix the README. This is the reproducibility requirement Ex1 inherits.

3. **API and error contract tables**
   - **Do:** a contract table (endpoint, method, body, statuses) and an error table copied from S03's actual codes (`VALIDATION_FAILED`, `NOT_FOUND`, `INVALID_STATE`, etc.).
   - **Run:** diff the tables against the S04 test names — every status in the tests must appear in the tables.
   - **Observe:** the tables and the test suite should agree line by line; if a test asserts something the table omits, the table lied. Fix the table.

4. **Cut-line table**
   - **Do:** build the 2h-vs-5h table using the post's template but for *this* repo: columns like capability | 2-hour slice | 5-hour version. Fill in "idempotent submission — No — documented gap (in-memory map, no unique key)", "persistence — No — Ex1/P01 moves to Postgres", "staff surface — No — Ex1 pharmacist queue", and so on.
   - **Run:** for each "No" cell, write the *what can happen* consequence in one clause ("restart loses all prescriptions").
   - **Observe:** the post's sentence — the first column says "No — documented gap", not "No" — becomes your table's honest cell text. That is the sentence an interviewer quotes back at you.

5. **Failure windows**
   - **Do:** a short section, one sentence per window: in-memory data loss on restart; no idempotency (duplicate submit creates duplicates); no auth (any caller may read any prescription); single instance (no horizontal story); errors are best-effort 500s without retry.
   - **Run:** read each sentence and check it names a *behavior*, not a fix.
   - **Observe:** this section is the counterweight to the confident tone above it — the combination is what "failure handling" scores look like on paper.

6. **Decisions log**
   - **Do:** list each fork you chose in S01–S05 (DTO at boundary vs domain; constructor vs field injection; sealed outcomes vs exceptions; MockMvc vs WebTestClient; `@ConfigurationProperties` vs `@Value`) with two lines each: what you chose and what you lost.
   - **Run:** check you actually wrote the notes when you made the choice — if a fork has no entry, that is the gap between doing a kata and owning it.
   - **Observe:** this log is your rehearsal script; every entry is a question you already answered once.

7. **Evidence**
   - **Do:** paste a curl transcript (happy path + one 400 + one 409) and the last `./gradlew test` summary line ("BUILD SUCCESSFUL in 6s, 9 tests").
   - **Run:** regenerate the transcript so it is not fabricated.
   - **Observe:** an interviewer who runs your commands should get your numbers. If the transcript is stale, the README is fiction — S-track has no excuse.

## Try this

The 24-hour-reader test: walk away, come back tomorrow, and run the README cold with a stopwatch — no IDE, no memory of the session. Note every friction point (missing flag, assumed tool, wrong curl). Then *delete one failure-window entry* and re-read the README: feel how the document now overclaims without it. The exercise is not about your writing; it is about calibrating how easily an omission hides in confident prose.

## Trade-off fork

**Option A — single README with an embedded decisions log:** everything an interviewer reads lives in one file; the walkthrough maps to one artifact.

**Option B — README + separate `DECISIONS.md`:** the main README stays scannable; decisions get room to breathe and can grow into ADR-style entries (and later into `../advanced/A14_cut_line_architecture.md`).

Pick one and write 3–5 lines justifying it. Name the lost benefits: B splits the reviewer's attention and can leave the decisions file unread (the walkthrough post says the interviewer "read your README and skimmed the code" — one file, one skim); A can balloon into a document nobody finishes. Nudge: A for the S-track (a 1-hour exercise deserves one artifact), with the note that Ex1's README may graduate to B.

## Hints

- **Hint 1:** steal the post's table skeleton verbatim — `posts/series-4-product-sse/04-time-box-scoping.md` is a template by design, and your cells are the ones you already wrote in S01–S05 checkpoints.
- **Hint 2:** if the README's "next steps" section starts listing features without pointing at a specific elective (`../postgres/P01_schema_and_migrations.md`, `../rabbit/R02_fire_and_forget_publisher.md`) or an Ex1 section, it is a wish list, not a plan — a plan names the artifact that delivers the feature.

## Checkpoint / success criteria

- Clean checkout → running in under 60 seconds using only the README.
- Cut-line table has at least three "No — documented gap" cells with concrete consequences.
- Every fork from S01–S05 appears in the decisions log; every S04 test status appears in the contract tables.
- No claim in the README contradicts the code; no production-capacity language; ≈150 lines (soft target).
- You can deliver the what-and-why paragraph aloud in 90 seconds.

## Bottleneck & reflection questions

1. **Patient experience:** does the README's first paragraph lead with the patient, or did the stack sneak into it? What does that ordering tell you about your product instincts?
2. **Simplicity:** writing the cut line made you name what you did not build. Which omission was hardest to admit, and why is that exactly the one to keep in the README?
3. **System design:** which failure window would Ex1 close first, and with which mechanism (`../postgres/P01_schema_and_migrations.md` unique key, `../rabbit/R06_dual_write_failure_demo.md` dual-write story)? Order the three windows by risk.
4. **Failure handling:** is "in-memory data is lost on restart" a gap or a *feature* of a lab? How does the README say it without apologizing — and why does tone matter at interview?
5. Which sentence in your README would you most want an interviewer to quote back at you — and which would you least want quoted?

## Handoff

- **This is the capstone of the S-track.** Next steps are deliberately cross-track: `../kotlin/K05_test_data_builders.md` (if you skipped it, your S04 fixtures depend on it) or `../postgres/P01_schema_and_migrations.md` (the persistence the cut line already names). The cut-line discipline you just wrote is later referenced and extended by `../advanced/A14_cut_line_architecture.md`.
- **Related showcase:** `../../pharmacy-fulfillment/exercise_01_foundation.md` — you are ready for it: the patient contract, the layers, the error contract, the tests, the config habits, and now the README discipline all transfer wholesale. Exercise 1 is this track, plus Postgres, RabbitMQ, and the state machine.
- **Interview line to say aloud:** "This is a complete two-hour slice, and the README says what it is and what it is not: in-memory storage is a documented gap with a named consequence, every decision is in the log, and the next three hours would close the idempotency and persistence windows in this order. The cuts are product decisions, not accidents."

## Optional stretch

Draft the two-hour cut line for Exercise 1 itself — one page in the shape of this README but in Ex1 terms (which RabbitMQ topology is "minimal, honest", which SSE claims are deferred, which failure windows are documented rather than solved). Keep it as `notes/ex1-cutline.md` — when you build Ex1, you will be implementing a plan you already wrote and defended.
