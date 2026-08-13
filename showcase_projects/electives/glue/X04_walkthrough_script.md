# X04 Walkthrough Script — Code-Along Elective

## Objective

Turn ONE completed elective into a rehearsed 10-minute oral demo: a fixed timing script (2 min journey, 3 min architecture, 3 min failure mode, 2 min tradeoffs), physical cue cards, and a prepared answer for the five follow-ups an interviewer will actually ask. You are not building software here — you are building the interview muscle the whole program has been feeding.

## Time box

~1h. Core for interview polish; last in the glue sequence. Nothing to implement — you need a completed elective (any of X01, X02, X03, or an `R*`/`P*` kata) to walk through, which is why this is the final glue elective.

## Prerequisites

- At least one completed elective with evidence you can show: X01 (`docker compose ps` all healthy), X02 (one greppable correlation-id journey), or X03 (the SSE test output). An `R*` or `P*` elective with a passing integration test also works.
- X02's JSON logs (the failure-mode section needs them) and X01's stack (the live demo needs it) are strongly recommended even if your chosen elective is X03.
- Position: last. This sits immediately before `showcase_projects/pharmacy-fulfillment/exercise_03_production.md`'s interview-polish phase and pairs with the `A12`–`A15` run.

## Blog & curriculum links

- Primary: `posts/series-5-interview/01-take-home-walkthrough.md` — the walkthrough structure this script compresses to 10 minutes; read its section table and the two rehearsal rules.
- Secondary: `posts/series-5-interview/04-showcase-interview-defense.md` — the mock defense; the follow-ups list below is drawn from its interviewer's attack patterns (claim, mechanism, evidence, honest limitation).
- Companion: `posts/series-5-interview/02-tradeoffs.md` — the four-part tradeoff statement (assumption, alternative, choice, sacrifice) that the 2-minute tradeoffs segment must use verbatim.
- Coach-assessment gap attacked: Track F — "timed walkthrough, system design, interview defense" (the final schedule block in `artifacts/coach-assessment.md`).

## Background & motivation

The challenge is scored in a 45-minute conversation, not in the repository. Most candidates can build; few can compress a week of work into 10 minutes of narration that lands patient value first, infrastructure last, and failure evidence in the middle. This kata deliberately ignores code quality entirely — you are rehearsing *delivery*. It also deliberately ignores the full 45-minute walkthrough (the blog post covers that); 10 minutes is the demo-slice that fits inside it, and it is the slice you will actually be asked for when the interviewer says "show me something you built."

The hard constraint: you pick ONE elective and tell its story completely. A half-told X03 plus a half-told X02 is worse than one whole X02, because the interviewer's hypothesis — *can this person own a design end to end?* — is only answered by a complete arc.

## Learning objectives

- Write a 10-minute oral script with hard timing: 2 / 3 / 3 / 2 minutes.
- Lead with the user journey (what the patient or operator experiences) before any component name.
- Narrate one failure mode with claim → mechanism → evidence, from a live artifact you can point at (log line, test output, queue depth).
- Deliver the four-part tradeoff statement without notes.
- Prepare five likely follow-ups with honest, scoped answers — including at least one "what did you sacrifice?" answer.
- Rehearse to the clock and to interruption (the interviewer will cut you off; you must return to the script).

## Warm-up

Read `posts/series-5-interview/01-take-home-walkthrough.md` sections 1 and 2 (the patient-first opening and one-minute architecture). Then answer in one sentence each, in writing: *what does my elective do for a person?* and *what is the one thing that breaks in it?* If either answer is "not sure", pick a different elective — the script needs both sentences on day one. 5 minutes.

## System specification

**Scope in:** one script of ~900–1,100 words total, divided into four timed segments; cue cards; follow-up bank; two rehearsals to the clock.
**Scope out:** new code; new infrastructure; the full 45-minute walkthrough (the blog post's table is the reference, not the target); memorizing a transcript — cue cards and flow, not word-for-word.
**Functional requirements (minimal):**
- The script opens with the user journey in the first 30 seconds — no component names before the journey sentence.
- Each of the four segments has a stated target time and a "you are overrunning if…" tripwire.
- The failure segment ends with a real artifact you can show (a log line from X02, a failing-then-passing test from X03, a `docker compose ps` state from X01).
- The tradeoff segment delivers the four-part statement (assumption, alternative, choice, sacrifice) in under 2 minutes.
- At least five interviewer follow-ups have written answers.
**Constraints:** one elective; one artifact per segment (not a slide deck of evidence); no reading from the terminal as a substitute for narration (you narrate, the terminal illustrates); timings are hard — 10 minutes plus 5 minutes of questions, not 20 minutes of everything.

## Step-by-step code-along

1. **Choose the elective and write the journey sentence.**
   - **Do:** pick the elective whose failure mode you can show from memory, and write the opening sentence: "What I built is X, and the person it serves is Y." Example for X02: *"What I built is a logging spine where every patient journey carries one correlation id, so a support ticket becomes a grep."* Write it, say it out loud, cut it to under 20 words.
   - **Run:** say the sentence to yourself until it does not sound rehearsed. Time it.
   - **Observe:** if the sentence names a technology before a person, rewrite it — this is the exact discipline the walkthrough post's section 1 enforces.
   - **Decision:** if two electives tie, choose the one whose *failure* story is most visible. A demo is scored on the failure segment, not the happy path.

2. **Write segment 1 — the journey (2 min).**
   - **Do:** script 4–6 sentences: who, what they experience, the one synchronous question the system answers, and the cut that makes it fit (for X03: "the GET is the truth, SSE removes the refresh latency"). End with a sentence that names the core invariant ("SSE is a replay over a store, never a consumer").
   - **Run:** read it aloud; if it exceeds 2 minutes, delete the second-best sentence.
   - **Observe:** the segment must survive interruption — if the interviewer asks "why did you build that?", you should be able to answer in one breath and return to the journey, per the walkthrough post's rehearsal rule.
   - **Decision:** tripwire — if you are still describing the problem at 1:30, cut to the invariant and let the architecture segment carry the detail.

3. **Write segment 2 — architecture (3 min).**
   - **Do:** a diagram in words: 3–5 components for the elective's slice, one sentence each, and one dependency direction. For X03: store → broadcaster → SSE connection → client; the single sentence "the stream reads the store, the store is the truth."
   - **Run:** draw the diagram on paper, then narrate it from the paper, then narrate it without the paper.
   - **Observe:** the shape the walkthrough post demands — components and one-hop dependencies only, no class-level detail. If you catch yourself saying "and then this class…", you have left the architecture segment.
   - **Decision:** choose the ONE component you will spend an extra 30 seconds on — it must be the one the interviewer will attack (for X03, the store and the replay cut-off; for X02, the header threading).

4. **Write segment 3 — the failure mode (3 min).**
   - **Do:** pick one failure (X02: a corrupted payload whose error still carries the correlation id; X03: a client that drops, events 4–5 happen, reconnect with `Last-Event-ID: 3` returns exactly 4, 5; X01: a stopped broker and the recovery story). Script it as claim → mechanism → evidence, and prepare the artifact: copy the log line / test output / `docker compose ps` state into a scratch file you can show in one command.
   - **Run:** rehearse the exact commands that produce the artifact, twice, from a cold terminal. The demo's credibility dies the moment you fumble a command in front of the interviewer.
   - **Observe:** the narrative must match the artifact. If you say "exactly 4, 5" the artifact must show 4, 5 — the mismatch between narration and evidence is the most common demo failure there is.
   - **Decision:** tripwire — if the artifact needs more than one command to produce, pre-stage it and say "let me show you the evidence from a run I did earlier" — honest, and faster.

5. **Write segment 4 — tradeoffs (2 min).**
   - **Do:** write the four-part statement for the elective's fork: assumption ("single local instance, one patient"), alternative ("a DB-backed store" / "explicit context threading" / "Testcontainers instead of Compose"), choice (what you built), sacrifice ("replay dies on restart" / "MDC is implicit magic" / "the stack is a demo, not a deployment"). The tradeoffs post's "…in exchange for…" sentence is your template.
   - **Run:** deliver it aloud without notes. Then deliver it *backwards* (sacrifice first) to prove you own the content, not the order.
   - **Observe:** naming the sacrifice unprompted is the differentiator — the mock defense post's table marks "the honest limitation stated" on every act.
   - **Decision:** pick the one sacrifice sentence you will never drop, no matter how the interviewer steers: "the toy loses its store on restart, in exchange for a 100-line proof of the replay contract."

6. **Build the cue cards and the follow-up bank.**
   - **Do:** write 4 cards (one per segment): 3–5 bullets each, max 8 words per bullet. Then write answers to the five follow-ups below, in the claim → mechanism → honest-limitation shape.
   - **Run:** practice with a timer; card one visible, others face-down. Then have someone (or a voice recorder) interrupt you mid-segment and ask a follow-up.
   - **Observe:** the difference between a script and a transcript — cards are the script; the sentences you wrote in steps 1–5 are the safety net, not the performance.
   - **Decision:** if a card exceeds 5 bullets, the segment is trying to say too much — cut to the invariant.

7. **Rehearse to the clock, twice.**
   - **Do:** run the full 10 minutes with the terminal artifact, timed. Run it again the next day from the cards only.
   - **Run:** `date`-based timing or any stopwatch; mark where each segment *should* end: 2:00, 5:00, 8:00, 10:00.
   - **Observe:** the second rehearsal should be ~15% faster and 10% shorter — you are compressing, not adding.
   - **Decision:** anything still overrunning at rehearsal two gets cut from the *middle* (detail), never from the journey or the sacrifice.

## Try this

The deliberate sabotage rehearsal. Run the full demo and deliberately make two mistakes: (a) mispronounce or mis-state one mechanism in segment 3 (say "the id is generated per send" in X03), and (b) fumble one terminal command. Do not stop — finish the demo. Then write down: did you correct the mechanism on the spot, and did you recover from the fumble without apologizing at length? The interview will contain both; the question is whether the recovery is rehearsed. The mock-defense post's rule applies: answer the interruption fully, then return to the segment — never power through to finish your script in your head.

## Trade-off fork

**Option A — demo the smallest, most provable elective (X02 logging, or X01):** a 10-minute arc with one grep and one log line. Low risk, airtight evidence, but a modest story — the interviewer may not learn much about your system-design ceiling.

**Option B — demo the showiest, riskiest elective (X03 SSE):** a richer story — reconnect, replay, ordering — but every extra claim is an extra attack surface, and a fumbled `curl` or a flaky test output is a bigger loss in front of a skeptical interviewer.

**Option C — walk through the completed showcase exercise instead of an elective:** maximum relevance to the actual challenge, at the cost of a far longer and less rehearsable script than a 10-minute elective slice can carry.

Pick one and write 3–5 lines justifying it, naming the sacrificed property. No official winner — the correct choice depends on which artifact you can produce flawlessly on the day, and the honest answer ("I chose X because I can show its failure evidence without notes, in exchange for a thinner system-design story") is itself a tradeoff statement the interviewer will score. Whatever you pick, the other two options' strengths must be nameable in the follow-up "why did you demo this and not the SSE piece?"

## Hints

- **Hint 1:** if a segment keeps overrunning, find the sentence that is *context* rather than *claim* and delete it — the walkthrough post's table allocates 2 minutes to the patient journey and 3 to architecture; your elective slice has even less slack. The journey segment survives on one verb: what the person *experiences*.
- **Hint 2:** for the failure segment, the strongest artifact in the whole program is X02's corrupted-payload log line (error + correlation id in one JSON line) or X03's reconnect test output (`containsExactly(4, 5)`). Both are one command to produce. If your elective cannot produce its artifact in one command, that is the thing to fix before the demo — not the script.

## Checkpoint / success criteria

You may leave when:

- The script has four timed segments hitting 2 / 3 / 3 / 2 minutes, and the full run lands within 10:00–10:30 twice in a row.
- The opening sentence names a person before any technology.
- The failure segment ends with a real artifact produced by one command, and the narration matches it.
- The four-part tradeoff statement is deliverable without notes, including the sacrifice sentence.
- Five follow-ups below have written, honest answers — and at least one answer names a limitation unprompted.

## Bottleneck & reflection questions

1. Which segment is your personal bottleneck — journey, architecture, failure, or tradeoffs — and what does that say about the gap the coach assessment flagged? (The assessment's Track F target is exactly this: timed walkthrough and interview defense.)
2. The walkthrough post says the interviewer checks one hypothesis: can you own the design in production. What does your elective's *failure* story have to do with patient experience, and what happens if your demo has no failure story at all?
3. System design: the mock defense's Act 7 is "you have two hours — what dies?" Run it on your elective. If you had to cut the elective to half its scope, what would you claim and what would you sacrifice?
4. Simplicity: which sentence in your script is doing the most work, and which could be cut with zero loss? The walkthrough post's 90-second opening is the model — every sentence either states a claim or points at evidence.
5. Failure handling: the one artifact you can produce on command is the demo's backbone. Which elective gives you the strongest artifact, and why is that the one you should demo even if another has a prettier story?

## Handoff

- Next: `../advanced/A12_observability_slice.md` and `../advanced/A13_chaos_drill_script.md` — the observability and chaos electives exist to give this script deeper artifacts; then `../advanced/A14_cut_line_architecture.md` for the "what dies in two hours" answer. Pair the script with the full mock in `posts/series-5-interview/04-showcase-interview-defense.md`.
- Related showcase exercise: `showcase_projects/pharmacy-fulfillment/exercise_03_production.md` — its interview-polish phase is where this script meets the real walkthrough; the 10-minute elective demo is the opening act of the 45-minute defense.
- Interview line to be able to say aloud (your full script compressed to one breath): *"I built X for a person who needed Y; here is the one thing that breaks, here is the evidence it breaks exactly this way, and here is what I traded away to keep it this simple."* If you can say that sentence without notes, the elective worked.

## Optional stretch

Record the demo and transcribe it. Mark every sentence that is a claim, and beside it write the artifact that proves it (log line, test, command output). Any claim without an artifact becomes the follow-up you will not be able to answer — so either find the artifact or delete the claim. That one pass converts a rehearsed script into an evidence-complete one, which is the difference between a good demo and a defensible one.
