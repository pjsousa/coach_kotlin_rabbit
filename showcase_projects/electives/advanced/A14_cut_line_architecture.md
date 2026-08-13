# A14 Cut-Line Architecture — Code-Along Elective

## Objective

Document — on a system you already own — the exact cut line between the "correct and honest" 2-hour submission and the "closes the failure windows" 5-hour version, then *prove* the cut by running the 2-hour slice and watching it pass the same patient-journey assertions the full system passes. You leave with a `CutLine.md` that reads like a scoping ADR (two plans, a risk-ordered gap list, a "next hour" order) and a walkthrough script that states the cut in three sentences.

## Time box

~1.5–2h. Core: steps 1–4. Optional: step 5 (a live timed cut) and the "defend the cut" exercise in "Try this".

## Prerequisites

- `../spring/S06_timebox_readme.md` — you already wrote a time-box README for a tiny API there. This kata is the **wave-3 capstone of the time-box theme**: it applies S06's discipline to the full stack, referencing S06 rather than duplicating it.
- Your Exercise 2 code (`../../pharmacy-fulfillment/exercise_02_optimization.md` built it) — the "small system" this kata cuts. Or, if your Exercise 3 is complete, the full production build from `exercise_03_production.md`.
- Position: **interview polish — after Exercise 3.** A14 is the scoping story you tell *about* the completed work; it is also a rehearsal for `posts/series-5-interview/01-take-home-walkthrough.md`.

## Blog & curriculum links

- Primary: `posts/series-4-product-sse/04-time-box-scoping.md` — "two submissions, not two endpoints of one"; the deferral rule; the three-sentence scoping story; "the README is scope."
- Secondary: `posts/series-5-interview/01-take-home-walkthrough.md` — the walkthrough structure the cut-line story slots into (patient first, then architecture, then failure behavior).
- Coach-assessment gap: production judgment and interview defense — specifically the "scope instinct" correction (reliable backend + tests + README before UI) that the blog post addresses.

## Background & motivation

S06 taught you to write a cut line for a tiny API: what ships at 2h, what the next hour buys, what is documented as a gap. This kata asks the question three levels up, on the real system: **what is the smallest slice of the *full production* system that is still a correct product, and what exactly does each extra hour buy back?**

The blog post's core argument is that the 2-hour and 5-hour submissions are *different plans*, not two budgets for one plan — and the difference is visible to a reviewer in the README before the code is even opened. You already have the code for both plans (Ex2 = the 2h shape, Ex3 = the 5h shape). What you have never done is *prove the cut*: run the 2-hour slice with the reliability machinery deliberately disabled, watch it pass the same patient-journey assertions, and write the gap list in the exact "one-line fix each" format the blog post demands.

Why this kata exists as a capstone: cutting is a judgment, and judgment is what the interview scores. The candidate who can say "the 2h slice drops the outbox, and here is the patient-visible consequence and the one-line fix" is demonstrating the failure-handling criterion in its purest form — with a time budget attached.

What this kata deliberately ignores: writing new production code. The system exists. This is an architecture *documentation and demonstration* exercise, and the hardest skill it trains is deciding what does not get built.

## Learning objectives

- Produce a two-plan scoping document (2h slice vs 5h version) for an existing system, with every deferred item carrying a one-line fix.
- Run the 2-hour slice against the real stack and show it passes the same patient-journey assertions the full system passes.
- Name the patient-visible consequence of each cut (what does the patient experience without the outbox, without retries, without SSE?).
- Order the "next three hours" by risk — each step closes a documented failure before adding a feature.
- Deliver the three-sentence scoping story from the blog post without notes.

## Warm-up

Re-read the "Two Submissions, Not Two Endpoints of One" and "The Two-Hour Slice" sections of `posts/series-4-product-sse/04-time-box-scoping.md`, and the deferral rule ("defer anything whose absence does not break the patient journey and does not hide a correctness bug"). Then open your S06 README and note the three sections it used — you will reuse those sections at full-stack scale.

## System specification

**Scope in**

- `CutLine.md` in the kata folder: the two-plan table, the gap list in one-line-fix format, the risk-ordered "next three hours," and the deferral list with the rule applied.
- A "2h slice" run configuration on the existing system: the full stack *minus* the reliability machinery — outbox/relay bypassed or disabled, consumers simplified to ack-after-effect, retries/DLQ collapsed to immediate requeue, SSE absent (polling GET is the patient experience). Prefer configuration/flags over code deletion; a feature flag or profile switch is the honest way to cut.
- A script that runs the slice: submit → approve → package → fulfill, asserting the status GET at each step (the Ex2 end-to-end test shape).
- Evidence folder: `slice-run.txt` (assertions), `CutLine.md` (the document).

**Scope out**

- No new features, no second product, no code archaeology. If a piece of Ex3 is genuinely entangled (outbox cannot be disabled), that entanglement itself is a finding — record it as the cost of the 5h version, which is the point.
- No estimating the *original* build time; the 2h/5h labels refer to the challenge's budget framing, not today's wall-clock.

**Functional requirements (minimal)**

1. `CutLine.md` contains both plans, every gap with a one-line fix, and the "next hour" ordering.
2. The slice run passes: submit returns `SUBMITTED`; approve reaches `APPROVED`; package → `READY`; fulfill → `FULFILLED`; each step asserted against the real API and real Postgres.
3. The document names the patient-visible consequence of each cut in one sentence each.
4. The three-sentence scoping story is written in the document's final section, ready to speak.

**Constraints**

- Local Docker stack only, the pinned images from your tests (the version-pin rule applies to the *demonstration* too).
- No cloud, no managed services, no claims of production capacity.

## Step-by-step code-along

### Step 1: Write the two-plan table from the code, not from memory

**Do:** Open your Ex3 system and inventory what actually exists: outbox, relay, confirms, manual acks, prefetch, retry/DLQ, inbox, projection, SSE, metrics. Build the table in `CutLine.md` — every row is a capability with three cells: *2h slice*, *5h version*, *patient-visible consequence of the cut*. The consequence cell is the one nobody writes and the one the blog post's evaluator criteria reward. Example: *"Outbox/relay: 2h = direct publish after commit (documented gap); 5h = transactional outbox + confirms. Patient consequence: an event can be lost between the DB commit and the publish, so a status update may never arrive — the GET remains correct, the notification may lag or vanish."*

**Run:** `grep -rl "outbox\|publisher\|dlq\|sse" src/main --include=*.kt | head` to make sure the inventory matches the code.

**Observe:** Every row you can name from memory has a code home; if a row has no code home, it is not in the system and belongs in the *deferral list*, not the table.

**Decision:** Do you include the *cost* of the 5h version (entanglement, complexity) in the table? Nudge: yes — one "cost of upgrading" column makes the table a real tradeoff record instead of a feature list, and the blog post's four-part tradeoff statement wants the sacrificed property named.

### Step 2: Find the cut point — what can actually be disabled

**Do:** For each 5h-row, decide the honest cut mechanism on your real system: an application flag (`reliability.enabled=false`), a profile (`--spring.profiles.active=slice2h`), or a documented "run Ex2's code path" instruction. Where the cut is *not* clean (e.g., the consumer requires the inbox table to exist even when disabled), write that down as entanglement — it is evidence that the 5h version is not a pure additive layer.

**Run:** A dry run with the slice profile active; fix only what the dry run breaks in configuration, never by disabling assertions.

**Observe:** The slice boots with: no relay consumer, no DLQ bindings (or they exist but nothing dead-letters), no SSE controller, no projection applier. The status GET and the workflow commands work exactly as in Ex2.

**Decision:** Cut by disabling consumers vs cutting the *bindings*? Nudge: disabling the consumers keeps topology visible and honest ("the queues exist, nothing consumes them") — that matches what a real 2h submission would look like, which is the fidelity the demo needs.

### Step 3: Prove the slice — run the patient journey

**Do:** Write `slice-run.sh`: submit a prescription (assert `SUBMITTED`), pharmacist approve (assert `APPROVED`), packaging worker completes (assert `READY`), fulfillment completes (assert `FULFILLED`), each with a real `curl` + `jq` against the status GET. Capture the output to `evidence/slice-run.txt`. If your Ex3 suite already has this e2e test, *run that test against the slice profile* — do not write a second journey.

**Run:** `./slice-run.sh` (or the e2e test with the slice profile).

**Observe:** The same journey the full system runs, passing on the cut system. Save the output. This single artifact is the proof that the cut is *a complete product, not a truncated plan* — the blog post's exact distinction.

**Decision:** Assert only the happy path, or also the *documented gaps* (e.g., "a mid-publish crash in the slice can lose an event")? Nudge: assert the happy path and *demonstrate* one gap live (step 4), because a gap you can reproduce is a gap you can defend — the README sentence is only honest if you have seen the failure happen.

### Step 4: Reproduce the slice's worst gap, once

**Do:** Pick the most serious gap the table names (the commit-publish window is the classic), and reproduce it on the slice: pause or kill the app between the DB commit and the direct publish — or simply disable the direct publish call and observe the event never reaches the queue. Then restore, and verify the full system's outbox closes the window (the A13 drill already proved this; one re-run of its scenario 1 is the evidence).

**Run:** The gap reproduction + the A13 scenario-1 re-run.

**Observe:** In the slice: the status GET is correct (the DB committed), the queue is empty (the publish never happened) — patient sees the truth by polling, the notification is simply absent. In the full system: the outbox row survives, the relay publishes after restart. You have now *witnessed* both sides of the cut-line table.

**Decision:** Reproduce via kill or via disable-flag? Nudge: the flag is deterministic and scriptable for the walkthrough; the kill is the A13 artifact. Record which one you did.

### Step 5: The "next three hours" and the deferral list

**Do:** In `CutLine.md`, write the next-three-hours order with the blog post's rationale ("each step closes a documented failure before it adds a feature"): (1) outbox+relay closes the loss window, (2) manual acks + inbox neutralize redelivery, (3) retry/DLQ bounds failures, (4) SSE with replay + isolation. Then the deferral list — apply the rule to your system: auth beyond headers, staff UI, dashboards, cloud deployment, exactly-once claims. Every deferral gets the one-line fix, exactly as the blog post's README sections demand.

**Run:** Read the final `CutLine.md` aloud, end to end.

**Observe:** The document is ~200 lines, three sections, no diagrams required — it is a *scoping statement*, not a design doc. If it reads like a design doc, you have drifted from the format; the blog post's sections are "What is included / Known limitations and crash windows / What I would do next."

**Decision:** Document-first or code-first for the cut? Nudge: the document you just wrote was only possible because the code existed — but for the *challenge* itself the blog post is explicit that the README is written before the feature, which is the difference between documenting a decision and rationalizing one. Write that sentence into the document's last section.

### Step 6: The three-sentence story (optional but recommended)

**Do:** Write the three sentences from the blog post's "Interview Framing" section, adapted to your system: (1) the slice, (2) the gap stated precisely, (3) the order of the next three hours. Record yourself saying them.

**Run:** Playback.

**Observe:** If any sentence requires reading, rewrite it. These three sentences are the walkthrough's opening in `posts/series-5-interview/01-take-home-walkthrough.md`, and the blog post says the structure demonstrates the failure-handling criterion by itself.

## Try this

**The timed cut.** Set a 2-hour timer (or 90 minutes if you have done this before), start from a clean `docker compose down && up`, and *rebuild the slice from the Ex2 code alone* — no opening Ex3 files. When the timer fires, stop and run the slice assertions. Whatever state the system is in, write the "would have done next" paragraph as the blog post prescribes. The experiment's output is not the code — it is the honest paragraph, and whether you hit the correct-slice-in-time boundary. This is the capstone of the whole time-box theme: S06 was the mini version, this is the full-stack dress rehearsal.

**Second experiment — the reviewer's first five minutes.** Give `CutLine.md` and `evidence/slice-run.txt` to someone (or tomorrow-you) without any other context. Time how long it takes them to answer: "what does this system guarantee, what does it not guarantee, and what would you add first?" If the answer takes more than five minutes or requires the code, the document failed the interview test — the blog post's "README is scope" claim.

## Trade-off fork

Pick **one**, write 3–5 lines justifying it, and name the lost benefit.

- **A: the 2-hour slice as the primary deliverable, 5h as documented-next vs B: the 5h version with the slice documented as a mode.** The slice-first submission is complete, honest, and immediately readable — it sacrifices the credit earned by demonstrated reliability machinery. The 5h-first submission shows the advanced capability — it risks reading as unfinished the moment any 5h piece is half-built, because the reviewer compares it to a complete slice.
- **A: document-first (write the README's limitation sections before coding the next item) vs B: code-first with documentation afterward.** Document-first forces every hour to close a named gap — it risks documenting intent that never gets built. Code-first produces evidence the document can cite — it risks the document rationalizing whatever was built, which is exactly the "rationalization" trap the blog post warns about.

## Hints

**Hint 1:** If the slice profile refuses to boot, the entanglement is usually in configuration: the relay bean declares queues the slice still depends on, or the app requires the projection tables to exist. Do not delete the beans — make them conditional on the profile (`@ConditionalOnProperty`) so the cut is a *flag*, which is both cleaner to demo and a finding about the 5h version's cost. If the e2e test fails only on timing, your slice is still polling correctly — the GET is the truth and the test is polling too fast; slow the poll, not the product.

**Hint 2:** If the gap reproduction in step 4 does not show a loss, you probably disabled the wrong publish path: the *direct publish* inside the transaction is the loss window, not the relay. In the slice, that publish should be a plain `convertAndSend` after commit with no outbox; if your code never had that shape, reproduce the gap by stopping the app before the publish completes and showing the queue never receives the event — the DB commit survived, the notification did not.

## Checkpoint / success criteria

You may leave when:

- `CutLine.md` has the two-plan table with patient-consequence cells, the gap list in one-line-fix format, the next-three-hours ordering, and the deferral list with the rule applied.
- `evidence/slice-run.txt` shows the full patient journey passing on the slice profile.
- One gap was reproduced live, and the A13 evidence (or its re-run) shows the full system closing it.
- The three-sentence story reads aloud without notes.
- You can say aloud: "the 2-hour slice is a complete product — submit, track, fulfill — with the status GET as truth and a documented gap list; the five-hour version closes those gaps in risk order, and I have the slice run and the gap reproduction to prove the difference."

## Bottleneck & reflection questions

1. The slice's worst gap (commit-publish loss) is invisible to the patient's GET but visible in the queue. Which of your *other* cuts has a patient-visible symptom, and which has only an operator-visible one — and does the README distinguish them?
2. Step 2 found entanglement between the 5h machinery and the slice boot. What does that entanglement tell a reviewer about the *cost* of the 5h version — and does it appear in your table?
3. The blog post defers auth and UI with the same rule. What is the one correctness bug your slice would hide if you deferred the *inbox* instead of SSE — and why does that make the ordering of the next three hours non-negotiable?
4. A reviewer asks "what would you cut from the 5h version if you only had 4h?" What is your answer, and which failure window does the cut reopen?
5. Your slice run is one journey. Which assertion, if added to the slice run, would *fail* in the slice but pass in the full system — and why is that assertion the honest boundary between the two plans?

## Handoff

- **Next elective:** `A15_security_baselines.md` (the slice defers auth "beyond headers" — A15 closes that deferral with the healthcare bar), or `../glue/X04_walkthrough_script.md` for the full oral rehearsal.
- **Related showcase exercise:** `../../pharmacy-fulfillment/exercise_03_production.md`, Milestone 10 — the final architecture and tradeoff record, of which `CutLine.md` is the scoping section; pair it with `posts/series-5-interview/01-take-home-walkthrough.md`.
- **Interview line:** "The 2-hour and 5-hour versions are different plans. The slice is a complete, correct patient journey with a documented gap list — the outbox gap first, because it is the only cut that can lose a notification — and the next three hours close the gaps in risk order, which I proved by running the slice and reproducing the gap it documents."

## Optional stretch

One harder twist: produce a *cut-line for a different product decision* — e.g., "multi-pharmacy isolation" or "prescription expiry/cleanup" — using the same document format, on paper only. The test is whether the format transfers: if it does, you own the *method* of scoping, not just the example, which is the actual senior skill the interview is probing.
