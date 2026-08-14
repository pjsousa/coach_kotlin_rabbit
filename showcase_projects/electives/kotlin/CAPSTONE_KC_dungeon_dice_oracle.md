# KC Dungeon Dice Oracle — Track Capstone (Code-Along)

## Objective

Synthesize every Track A skill in one small, pure-Kotlin CLI: the **Dungeon Dice Oracle** — a tabletop dice engine that parses expressions like `2d6+3`, rolls with advantage/disadvantage, resolves results into a sealed outcome tree (`Crit` / `Hit` / `Miss` / `Fumble`), narrates rolls in dungeon-master prose, draws from weighted loot tables, and (stretch) runs a multi-round coroutine "combat tick" play-by-play. You build the project from zero — this file guides, it never dumps solutions.

## Time box

~4–6 hours total. M1–M3 are the core (~3.5–4.5h). M4 is the optional stretch (+1–2h) that earns K08. If you hit hour 5 and M4 is not started, stop — ship the checkpoint, skip M4 with a written waiver in the checklist, and keep the stretch for a spare evening.

## Prerequisites

**Track electives that should be complete** (or knowingly skipped with a waiver — the checklist below is the ledger):

| ID | Title | Status |
|---|---|---|
| K01 | [Prescription value objects](../kotlin/K01_prescription_value_objects.md) | required |
| K02 | [Nullable patient lookup](../kotlin/K02_nullable_patient_lookup.md) | required |
| K03 | [Workflow state machine](../kotlin/K03_workflow_state_machine.md) | required |
| K04 | [Inventory pure functions](../kotlin/K04_inventory_pure_functions.md) | required |
| K05 | [Test data builders](../kotlin/K05_test_data_builders.md) | required |
| K06 | [Collections and sequences](../kotlin/K06_collections_and_sequences.md) | required |
| K07 | [Extensions and scope functions](../kotlin/K07_extensions_and_scope_functions.md) | required |
| K08 | [Coroutines lite](../kotlin/K08_coroutines_lite.md) | optional (M4 only) |

**Tools:** JDK 17+, Gradle 8.x (or IntelliJ with the Kotlin plugin), nothing else. No Docker, no database, no broker, no Spring on this track's capstone.

**Position vs showcase:** this capstone runs **before** `../../pharmacy-fulfillment/exercise_01_foundation.md`. Everything you make testable here — value model, sealed outcomes, pure math, builder-style test data — is the vocabulary Ex1 will persist and expose. The domain is deliberately non-pharmacy (dice, not prescriptions) so the Kotlin habits transfer without the domain answers leaking.

## Blog & curriculum links

- `../../../posts/series-1-kotlin/01-kotlin-for-java-developers.md` — data classes, `require`, structural equality; the value-model baseline.
- `../../../posts/series-1-kotlin/02-nullability-results-domain-errors.md` — sealed outcomes and nullability at parse boundaries; the K02 framing this capstone reuses.
- `../../../posts/series-1-kotlin/03-state-machines-with-sealed-types.md` — why the sealed outcome tree is the engine's state machine.
- `../../../posts/series-1-kotlin/04-kotlin-testing-for-java-engineers.md` — parameterized tests, builders, deterministic test strategy.

## Background & motivation

A Product Engineer interview at a pharmacy/healthcare fulfillment company is rarely a trivia quiz — it is a series of *"design this, now break it, now tell me how you'd know it works"* conversations. The Kotlin electives gave you the moves in isolation (value objects, nullable lookups, sealed states, pure math, builders, sequences, extensions, coroutines). This capstone forces you to compose all of them in one coherent system so the moves become reflexes rather than flashcards.

The domain is a **dice oracle** on purpose: there is no pharmacy vocabulary to fall back on, so you cannot accidentally memorize answers. A dice roll is also a spectacularly honest system to model — the failure modes (malformed input, impossible states, unverifiable randomness) are concrete, small, and testable in milliseconds, which makes it a perfect rehearsal arena for the failure-handling stories you will tell about prescription workflows and fulfillment systems.

Throughout, you'll see bracketed **[patient-echo]** tags: these are prompts to notice where the dice mechanic rhymes with the pharmacy domain. That rhyming is interview gold — it is how you translate geek-lab evidence into healthcare credibility.

## Skill checklist (mandatory)

Every line below maps one prior elective to a concrete behavior in this capstone. Mark **pass** or **skip + waiver** with a one-line reason. The file ships with all boxes empty; fill them as you go.

| Elective | Concrete capstone behavior / test | pass / skip + waiver |
|---|---|---|
| K01 | `DiceExpr`/`DieSpec`/`RollResult` value objects as data classes with `require` validation (positive sides, >=1 die); `copy`-for-modification in reroll/advantage; structural-equality tests; zero `var` in `src/main` | ☐ |
| K02 | Parse boundary returns sealed `ParseOutcome` (success / invalid syntax / unknown die) plus `null` for absent modifiers; expected bad input never throws; no `!!`, no `Optional`. *K02 taught nullable + sealed outcome vs exceptions, not `kotlin.Result` — phrase your defense accordingly* | ☐ |
| K03 | Sealed outcome tree (`Crit`/`Hit`/`Miss`/`Fumble`); roll→outcome transitions return sealed results; exhaustive `when` maps outcomes to oracle narration text; illegal transitions rejected by type/test | ☐ |
| K04 | Damage and loot math as pure functions over immutable snapshots; boundary suite (exact fit, zero stock, duplicates); no input mutation | ☐ |
| K05 | `TestDataBuilders` for dice expressions and roll fixtures; parameterized test tables (malformed-string matrix, roll boundaries, transition matrix); zero object-literal repetition | ☐ |
| K06 | Weighted loot tables from pipeline primitives (`groupBy`/`sumOf`/`sortedBy` cumulative weights); >=10k-roll fairness simulation where `asSequence()` laziness is a deliberate justified choice; no `stream()`/`Collectors` | ☐ |
| K07 | DSL ergonomics via extensions — `d20.advantage()`, `d6.disadvantage()`, modifier extensions on `DiceExpr`; one deliberate `let`/`also`/`apply` use in parse/roll pipeline + one place reader defends NOT using them | ☐ |
| K08 (OPTIONAL, M4 stretch) | Coroutine multi-round "combat tick" play-by-play simulator using `coroutineScope`/`async` with `runTest` deterministic tests | ☐ |

## Learning objectives

- Compose K01–K07 into one coherent, container-free Kotlin module, in the order a real project needs them (values → parse boundary → outcomes → math → test ergonomics).
- Defend, aloud, two parse/error-handling forks and one laziness fork with 3–5 lines each (this is the interview muscle).
- Produce reproducible evidence: seeded-roll test output, a >=10k-roll fairness report, and a deterministic play-by-play log.
- Explain, in one sentence each, how the dice engine's failure handling maps to patient-facing pharmacy failure handling.

## Warm-up

Re-read `../../../posts/series-1-kotlin/03-state-machines-with-sealed-types.md`, section on exhaustive `when` and unrepresentable states. Then, on paper, in two minutes:

1. Write the smallest sealed hierarchy that can represent a d20 roll resolved against a DC (difficulty class): what do `Crit`, `Hit`, `Miss`, `Fumble` need to *carry* so narration needs no further branching?
2. Answer: "A roll of `1` on a d20 is a Fumble. A rerolled `1` with advantage — is it still a fumble? Which type system choice makes that question *unaskable*?"

You do not need to be right; you need to have answered before reading the Hints section.

## Project bootstrap

**Exact directory:** `showcase_projects/electives/projects/dungeon-dice-oracle/` — candidate-owned code; do not commit it (add `showcase_projects/electives/projects/` to `.gitignore`, optional).

**Do:** from repo root, create the directory and bootstrap a bare Kotlin JVM module:

```bash
mkdir -p showcase_projects/electives/projects/dungeon-dice-oracle/src/{main,test}/kotlin/oracle
cd showcase_projects/electives/projects/dungeon-dice-oracle
```

Write `settings.gradle.kts` (project name `dungeon-dice-oracle`) and `build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.1.x"
}
kotlin { jvmToolchain(17) }
dependencies {
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))
}
```

**Run:** `./gradlew build` → "BUILD SUCCESSFUL" with zero sources.

**Observe:** this is the same bare shape as K01 — the capstone deliberately adds no framework. Every compile is fast, every test is a single-process run. That speed is what makes the >10k-roll sims and parameterized matrices practical; note it, you'll defend it in interviews as "test latency budget."

Create `README.md` — a skeleton you fill as you go:

```markdown
# Dungeon Dice Oracle
One-line pitch. Stack (one line). How to run (one line).
Evidence index: link each file under evidence/ and what it proves.
What I'd change if this were a real product (1-2 lines).
```

Fill the last section at the *end*, not now.

## System specification

**Product fantasy / actors.** A dungeon master runs a tabletop session. Players roll dice; the **oracle** is the DM's impartial adjudicator: it parses roll expressions (`2d6+3`), rolls them (honestly), resolves them against a target number, narrates the result in game prose, and hands out treasure from weighted loot tables. Actors: the DM (single human typing expressions), the players (whose decisions produce expressions), and the oracle (pure, deterministic-under-seed, narrating).

**Scope in.** `DiceExpr` parsing with a sealed parse boundary; rolling with a swappable `Random` (seeded for tests); advantage/disadvantage as roll modifiers; outcome resolution against a DC; oracle narration via exhaustive `when`; pure damage math; weighted loot tables; a CLI `main` that reads an expression and prints a roll line; tests throughout.

**Scope out.** Web UI, network, database, Spring, RabbitMQ, real-time tables, physical-dice fairness, a full RPG rulebook, persistent campaign state.

**Functional requirements (minimal):**

- `2d6+3`, `d20`, `d8-1`, `3d4`, `2d6` (no modifier) parse and roll correctly.
- `d0`, `2d0`, `-1d6`, `2d6++3`, `2x6`, `d7` are rejected — as invalid syntax or unknown die, never as a crash.
- Advantage/disadvantage: roll twice, keep the better/worse result; the kept roll is still a single `RollResult`.
- Outcomes: `1` on a d20 → `Fumble`; `20` → `Crit`; else compare total vs DC → `Hit`/`Miss`.
- Narration: every outcome maps to one prose line via an exhaustive `when` — a new outcome subtype without a narration line must not compile.
- Loot: a weighted table with cumulative probabilities picks items; two identical entries (duplicates) are legal and individually reachable.
- CLI: `oracle roll "2d6+3" --dc 10` and `oracle loot --table goblin --count 3` print readable output and exit 0; malformed input exits with a helpful message and exit 1.

**Non-functional / evidence requirements:**

- Zero `var` in `src/main`.
- Seeded `Random` ⇒ byte-identical output for a fixed seed (test reproducibility is a feature).
- A fairness report artifact for a >=10k-roll loot simulation (see M2).
- A deterministic multi-round play-by-play log artifact (M4, if taken).
- All expected-bad input paths proven by tests to never throw out of the CLI.

**Constraints:** pure Kotlin CLI only — no Spring, no DB, no Rabbit, no web framework, no cloud. Single Gradle module. Tests via `kotlin.test`.

## Milestones (code-along)

### M1 — Value model, parse boundary (K01, K02)

**Do.** In `src/main/kotlin/oracle/`:

1. `DieSpec(sides: Int)` and `DiceExpr(rolls: DieSpec, modifier: Int)` as data classes with `require` validation (positive sides, at least one die).
2. `RollResult(dieValues: List<Int>, modifier: Int)` — data class, total derived via a `val total: Int` property.
3. A sealed parse boundary:

```kotlin
sealed interface ParseOutcome {
    data class Parsed(val expr: DiceExpr) : ParseOutcome
    data class InvalidSyntax(val input: String, val reason: String) : ParseOutcome
    data class UnknownDie(val sides: Int) : ParseOutcome
}
```

4. `parse(expression: String, knownSides: Set<Int>): ParseOutcome` — a pure function. Unknown die = well-formed syntax but a die the oracle doesn't recognize (say `d7` when the set is `{4,6,8,10,12,20}`); invalid syntax = anything structurally wrong (`2d6++3`, `d`, `x6`). A missing modifier yields `null` — that is the K02 nullable handoff: *"no modifier"* is an absent value, not an error.

**Run.** `./gradlew test` with a first test file. Structural-equality tests (`DiceExpr(2,6,3) == DiceExpr(2,6,3)`, and copy-changed instances unequal) and a malformed-string matrix asserting each bad input maps to the *right* outcome kind — never a thrown exception.

**Observe.** The `require` guards in the value classes make `parse`'s last line trivially safe: the boundary has already vetted everything. You have now split the system into *"can it even exist"* (value layer) and *"what does the user mean"* (parse layer) — the same split you'd draw between request validation and domain rules in the pharmacy service.

**Mini trade-off.** Unknown die as a *third outcome kind* vs a second reason inside `InvalidSyntax`. Pick one, 3 lines. (Interview flavor: what does the distinction buy the *caller*, e.g. a CLI that wants to suggest "try d20" vs a form that wants one error box?)

### M2 — Outcome tree, pure math, loot (K03, K04, K06)

**Do.**

1. Sealed outcome tree:

```kotlin
sealed interface RollOutcome {
    data class Crit(val total: Int, val dieValues: List<Int>) : RollOutcome
    data class Hit(val total: Int, val dieValues: List<Int>) : RollOutcome
    data class Miss(val total: Int, val dieValues: List<Int>) : RollOutcome
    data class Fumble(val total: Int, val dieValues: List<Int>) : RollOutcome
}
```

2. `resolveOutcome(result: RollResult, dc: Int, naturalDie: Int): RollOutcome` — pure function: natural `1` → Fumble, natural `20` → Crit, else compare `total` to `dc`. **Illegal transitions:** the *type* makes them unrepresentable — you cannot construct a Fumble from a 20 without the function being the only door. Later, a test proves `resolveOutcome` is the sole constructor (see K03 row: transitions return sealed results).

3. `narrate(outcome: RollOutcome): String` — one exhaustive `when`, each branch a short DM voice line. Add a `critical` outcome in a scratch branch; watch the *compiler* refuse your build.

4. Damage math as pure function over immutable snapshot: `DamageSource(flat: Int, dice: DiceExpr)` and `applyDamage(source, target: ImmutableTargetSnapshot): ImmutableTargetSnapshot` — returns a new snapshot, never mutates input.

5. Loot via pipelines. `LootItem(name, weight, value)` and a table built from primitives:

```kotlin
val goblinLoot = listOf(
    LootItem("copper teeth", 40, 1),
    LootItem("rusty dagger", 30, 5),
    // ... duplicates allowed and deliberate
)
// cumulative weights via sortedBy/sumOf/groupBy — assemble your own picker
```

`pickItem(table, rng)` → weighted draw; `TreasureBag.fill(contents, capacity)` → pure allocation with boundary tests: **exact fit** (contents sum exactly to capacity), **zero stock** (empty bag in, empty bag out), **duplicates** (two copies of one item both placeable).

**Run.**

1. `./gradlew test` — outcome/narration/transition tests, damage boundaries, loot fill boundaries.
2. A fairness sim: roll the goblin table **10,000 times**, count per item, and print a table:

```
Dungeon Dice Oracle — loot fairness, goblin table, 10_000 rolls (seed 42)
item            weight  expected%   observed%    delta
copper teeth    40      40.0%       39.87%       -0.13
rusty dagger    30      30.0%       30.12%       +0.12
...
max |delta| = 0.24 points — PASS (band = 1.5)
```

**Observe.** The report *is* the evidence artifact — it is not the tests, it is the story you hand an interviewer when they ask "how do you know the loot is fair?" Keep it under `evidence/`. The lazy-vs-eager choice for the 10k loop is a preview of the M3–M4 fork.

**Mini trade-off.** `resolveOutcome` thresholds as magic numbers vs an `OutcomeTable` value object. Same decision as a pricing rule in a pharmacy service: encode the rule as data, or hard-code it. Pick one.

### M3 — Extension DSL, builders, matrix tests (K07, K05)

**Do.**

1. Extension-based ergonomics — `RollSpec.d20` / `d6` singletons, then:

```kotlin
fun d20.advantage(): AdvantageRoll = /* roll twice, keep higher — reuse copy/reroll primitives from M1 */
fun d6.disadvantage(): ...
fun DiceExpr.bonus(amount: Int): DiceExpr = /* copy-for-modification */
fun d20.plus... // if you want expression-style: `d20 + 2` — your call, keep it small
```

Your DSL must be usable in **at least two** extension-based expressions (`d20.advantage()`, `2.d6.disadvantage().bonus(3)` style), and each must reduce to the same primitives as the string path — prove it with a test that `parse("2d6+3")` and `2.d6.bonus(3)` produce *equal* `DiceExpr` instances.

2. Scope functions — a deliberate, single use of one of `let`/`also`/`apply` inside the parse→roll pipeline (e.g. logging the raw input `also { println("rolling $it") }`). Then write in your README the place where you considered using them and deliberately did **not** — with 3 lines on why plain code read better there.

3. Test ergonomics (K05):

```kotlin
// TestDataBuilders, one per fixture family:
fun diceExpr(count: Int = 2, sides: Int = 6, modifier: Int = 0): DiceExpr = ...
fun rollResult(values: List<Int> = listOf(4, 3), modifier: Int = 0): RollResult = ...
```

Parameterized tables via `withData`:

```kotlin
withData(
    "2d6+3" to ParseExpectation.Parsed(...), "d0" to ParseExpectation.Invalid,
    "d7" to ParseExpectation.UnknownDie, ...
) { (input, expected) -> /* one test body, no repeated literals */ }
```

Also a **transition matrix**: every (natural die, dc, total) triple → expected outcome, table-driven.

**Run.** `./gradlew test` — full suite green. Then run the CLI path once:

```bash
./gradlew run --args='roll "2d20+4" --dc 15 --seed 7'
# example output:
# 2d20+4 → [17, 9] keep best 17 +4 = 21 ≥ 15 → HIT
```

**Observe.** The DSL and the string parser produce *the same model* — that is the seam you want in any system where two entry points (UI text field, programmatic API) must agree. The parameterized matrix compressed ~30 near-identical tests into one table; that is not laziness, it is readability — and it is the exact move for a status-transition matrix in the fulfillment domain.

**Mini trade-off.** DSL via extension functions vs plain static factory functions (`advantage(d20)` vs `d20.advantage()`). Pick one; name one cost of the loser.

### M4 — Combat tick stretch: coroutines + 100k-roll sim (K08, K06 lazy path)

**Optional** (+1–2h). Do it only if K08 is complete.

**Do.**

1. A `combatTick` simulator: two sides with HP and attack expressions; each round resolves `coroutineScope { val attacks = listOf(..., ...).map { async { rollAndResolve(...) } }; attacks.awaitAll() }`, applies damage via the M2 pure functions, prints a play-by-play line:

```
Round 3 — Fighter hp 7/12, Goblin hp 2/6
  Fighter attacks with 1d8+2 → 9 → HIT → goblin hp 2→0 — goblin defeated
  Goblin is defeated — no retaliation
```

2. `runTest` deterministic tests: the full battle from seed 42 must end with byte-identical logs; assert exact round counts and HP sequences, not just "one side won".

3. The 100k-roll sequence sim: rerun the M2 fairness sim at **100,000** rolls where the accumulation uses `asSequence()` laziness (build the pipeline lazily, force at the terminal op) — and write the 3-line justification for why laziness is a *deliberate choice* here, or why you rejected it.

**Run.** `./gradlew test`; capture `evidence/playbyplay_seed42.txt` and `evidence/fairness_100k_seed42.txt`.

**Observe.** Determinism under `runTest` is your answer to "how would you prove a retry/idempotency path is correct?" — virtual time plus fixed seed plus pure damage math means the whole battle is a pure function of its inputs. The play-by-play is, structurally, an event log; the pure functions are the projection. That is the one-liner you take to the showcase.

## Try this

1. **Malformed-string torture.** Feed the parser `"2d6+3"`, `"2d6 + 3"` (spaces), `"2D6+3"` (caps), `"d"`, `"+3"`, `"2d6--1"`, `"0d6"`, `"2d-3"`, `"2d6+0"`. Decide, with a test, which are valid, which are invalid-syntax, and which (if any) are "normalized-away". You will disagree with yourself at least once — good; that disagreement is a spec bug you found in 20 minutes instead of production.
2. **Weighted loot fairness.** Run the goblin table sim at 1k, 10k, 100k rolls. Note how the observed deltas shrink. Then rig the table so one item has weight 1 and another 400 — does your sim still pass a 1.5-point band? What does that tell you about *where* fairness claims are actually load-bearing?
3. **Illegal combat transitions.** From the M2 rule set, write tests that *cannot pass*: "a natural 1 with advantage can still crit" — then either change the model or write the test that pins the rule. This is K03's "illegal transitions rejected by type/test" in action: you should end up with the compiler or the test as your enforcer, never a runtime `if`.

## Trade-off forks

Write 3–5 lines of justification per fork in your README trade-off log — this is the interview rehearsal material.

1. **Sealed `ParseOutcome` vs exceptions for parse errors.** Sealed outcome: every caller must handle every case, exhaustive `when`, errors are values; cost: plumbing at every layer. Exceptions: concise, familiar from Java; cost: the error path is invisible in signatures, `try/catch` spreads. Note: K02 deliberately taught nullable + sealed-outcome-vs-exceptions, *not* `kotlin.Result` — phrase your defense using those two ideas, and be ready for "why not `kotlin.Result`?" as a follow-up.
2. **Eager `List` pipeline vs `asSequence()` for the big loot sims.** Sequences: one-element-at-a-time, no intermediate lists, wins on the 100k path; cost: terminal ops are easy to forget, harder to debug. Lists: obvious, debuggable, fine at 10k. Your call must name the *measured* number (10k vs 100k) and what changes the answer.
3. **DSL via extension functions vs plain functions for the roll API.** Extensions: reads like the tabletop's own notation, discoverable via autocomplete, trivially testable against the string parser; cost: a "second syntax" the parser must stay in sync with. Plain functions: one way to say things, zero DSL surface; cost: `d20.advantage()` reads better than `withAdvantage(d20)` — argue the opposite if you can.

## Hints

*Progressive — Hint 1 first; only descend when stuck.*

- **Parse (M1).** Hint 1: tokenize on `d` and sign-separated modifiers; the grammar is `count? d sides (modifier)?`. Hint 2: think in terms of three regex-free scans: leading optional count (default 1), mandatory `d`, sides, then a single optional `+N`/`-N`. Hint 3: parse into *raw* pieces first (`count`, `sides`, `mod` as strings/nullables), validate pieces, then construct — never construct-then-validate at the boundary.
- **Outcome tree (M2).** Hint 1: `naturalDie` is the die value, `total` includes the modifier — a `1` on the die is a Fumble even at `dc 1` with a +5 mod. Hint 2: make `resolveOutcome` the only public way to build a `RollOutcome`; consider a `private` constructor or a sealed `internal` construction seam. Hint 3: if `narrate` starts needing `when` branches with `else`, your outcome tree is leaking values — put data *in* the subtypes.
- **Loot sim (M2).** Hint 1: cumulative weights are a running sum; `pick` = `rng.nextInt(totalWeight)` then first item whose cumulative ≥ value. Hint 2: `sortedBy` on weight is *not* the same as cumulative ordering — think about what ordering means for the `>=` probe. Hint 3: for the fairness band, compute max `|expected - observed|` per item; 1.5 points at 10k rolls is a sane default, justify it.
- **DSL (M3).** Hint 1: `advantage` is two `DieSpec.roll()` calls plus a `maxOf` — do not create a new value class yet. Hint 2: extension functions on a `DiceExpr` receiver compose with the string parser's model because *they share the same data class* — that is the whole point of "one model, two front doors."
- **Coroutines (M4).** Hint 1: `async` inside `coroutineScope` for each attacker, `awaitAll()` for the round; damage application stays a pure function call after the await. Hint 2: keep `Random` out of the suspend path — seed once at the top and thread it through, or `runTest` determinism vanishes. Hint 3: if a round takes longer to print than to compute, print *after* `awaitAll` — your play-by-play is a projection of a settled round, like a projection after a commit.

## Checkpoint / success criteria

You may leave this capstone when **all** of these hold:

- **Skill checklist:** every row marked `pass`, or `skip + waiver` with a one-line reason (K08 may be honestly skipped if M4 was cut — a waiver beats a fake pass).
- **Build:** `./gradlew build` green; zero `var` in `src/main` (enforce by eye; the checklist row is your witness).
- **CLI:** both commands from the spec work; malformed input exits 1 with a helpful line, never a stack trace.
- **Evidence folder** under the project dir contains: the 10k fairness report (M2), at least two extension-DSL test results or the `run` output proving `parse("2d6+3") == 2.d6.bonus(3)` (M3), and, if M4 was taken, the seeded play-by-play and 100k report.
- **Trade-off log:** at least the three major forks and the two milestone mini-forks, 3–5 lines each.

**Demo script (60–90s, for a senior interviewer):** 1) show the parse boundary — type `d7`, `2d6++3`, `2d6+3`; 2) roll `d20 --dc 15` twice and narrate; 3) run `loot --table goblin --count 3`; 4) open `evidence/fairness_10k_seed42.txt` and say one sentence about the band; 5) if M4: run the seeded play-by-play and point at `runTest` determinism.

## Bottleneck & reflection questions

- **Bottleneck first.** Which milestone took the longest, and was it a *language* friction or a *modeling* friction? Your answer tells you what to warm up before Ex1 (say: "I burned 40 minutes deciding where `UnknownDie` belongs — it was a modeling decision, not a Kotlin one").
- **Patient experience by analogy.** The CLI's "never a stack trace" rule — where does the pharmacy service have the same rule, and what is its `InvalidSyntax` equivalent? *(Hint: a bad rx code, an unknown NDC, a form error — same three-way split: parsed / invalid / unknown.)*
- **Simplicity.** The DSL and the string parser share one model. Where in the fulfillment system do two front doors need to agree, and what happens when they silently disagree?
- **System design.** The outcome tree is a state machine. Which prescription lifecycle would you draw the same way, and which transition would you want the *compiler* to reject for you?
- **Failure handling.** The fairness sim's 1.5-point band is a tolerance, not a guarantee. What's the analogous tolerance-vs-guarantee line in a pharmacy (fill accuracy? SLA? inventory parity)? What evidence would you bring?

## Handoff

**Next:** `../../pharmacy-fulfillment/exercise_01_foundation.md` — you are now the Kotlin-native engineer Ex1 expects: value model habits, sealed-state discipline, builder-style tests, and a rehearsed trade-off log are all in hand. If you skipped M4, K08 remains a known gap — close it before the showcase's coroutine sentence comes up. **Other tracks:** this capstone is Track A's finish line; if you want the glue lab next, `../glue/X01_docker_compose_trio.md` is the natural follow-on for the Spring track.

**Interview one-liner:** "I model illegal states as unrepresentable and keep pure domain logic testable without a container." Practice saying it over the fairness report — one sentence, then hand over the evidence.

## Optional stretch

- **Cheating die.** A `LoadedDie` variant that biases one face — prove your fairness sim *detects* it (this is your "how would you test for bias" story).
- **Roll history log.** A pure `SessionLog` projection (append-only list of `RollOutcome`s) with groupBy day-like bucketing — the read-model muscle for A10 later.
- **`ParseOutcome` → error text.** Map every outcome kind to user-facing copy in one exhaustive `when`, and test that no combination compiles to blank output.
- **Multi-attacker combat as data.** Run the M4 battle with 3-vs-3 and serialize the play-by-play to a file you can diff across seeds.
