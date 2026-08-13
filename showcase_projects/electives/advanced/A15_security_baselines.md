# A15 Security Baselines — Code-Along Elective

## Objective

Establish the three security baselines a healthcare system must be able to defend — patient scoping, least-privilege database roles, and no PII in logs — on the local system you already built, using stand-ins and assertions rather than an auth framework. You leave with a scoping test that proves one patient cannot read another patient's data (including via the SSE replay path), a migration that creates a least-privilege app role with a privilege-evidence test, and a log-assertion test that fails if PII leaks.

## Time box

~2h. Core: steps 1–5. Optional: step 6 (privilege-matrix write-up) and the "log-scrape on a failure path" experiment in "Try this".

## Prerequisites

- `A11_sse_hard_edges.md` or `../glue/X03_sse_toy.md` — the SSE authz/scoping edge you proved there is where the patient-scoping baseline plugs in.
- `../postgres/P01_schema_and_migrations.md` — you own the migration discipline; the role work is a migration.
- The exercise stack from `../../pharmacy-fulfillment/exercise_02_optimization.md` (or your Ex3 build) — the app whose API and schema this kata hardens. Optional: `A12_observability_slice.md` for the log-shape it made greppable.
- Position: **interview polish — after Exercise 3.** Healthcare-grade defense is a series-5 story; tie it to `posts/series-5-interview/02-tradeoffs.md`.

## Blog & curriculum links

- Primary: `posts/series-4-product-sse/02-sse-correctness.md` — the "Authorization And Isolation" section: ownership on connect *and* in the replay query, misrouting vs unauthorized replay, and the EventSource-header caveat.
- Secondary: `posts/series-2-postgres/01-schema-design.md` — "some rules are local validation concerns... rules shared by concurrent requests belong in PostgreSQL" — the same argument applied to access control.
- Coach-assessment gap: production judgment and interview defense; the patient-notification showcase (series 4, post 5) treats scoping as part of the "defensible product slice."

## Background & motivation

Everything you have built so far authenticates with `X-Patient-Id`, a documented header stand-in. That stand-in was a deliberate scoping decision — A14's cut-line defers "auth beyond headers." This kata makes the stand-in *defensible* by pinning the three baselines that must hold regardless of who issues the identity:

1. **Patient scoping is a query shape, not a filter.** "WHERE patient_id = :me" in the repository is the guarantee; a service that fetches by prescription id and *checks afterwards* is the classic bug — the check is one `if` away from being forgotten. The baseline proves scoping at the SQL level, including on the SSE replay path (A11's "the replay query is the same class of read as the GET").
2. **Least privilege is a migration, not a wish.** In P01 you ran migrations as a superuser with full DDL. A production app role should not be able to `DROP TABLE` or read the outbox with a different tenant's data. The baseline is a second role with only the grants the app's SQL needs — and a test that *proves* the missing privileges are missing.
3. **No PII in logs is an assertion, not a habit.** The healthcare bar is blunt: a medication name, a patient name, or a prescription payload in a log line is reportable. The baseline is a test that greps your own structured logs and fails the build on a violation — because "we try not to log PII" is not a baseline, and a reviewer knows it.

What this kata deliberately ignores: real authentication (OAuth/OIDC, JWT verification, password handling), encryption at rest and in transit, and audit logging frameworks. The identity transport stays the documented header; the *authorization decision* is what the baselines harden. That split — identity may be stand-in, authorization must be real — is the sentence to state in the interview.

## Learning objectives

- Make patient scoping a repository-level SQL shape, and prove cross-patient reads fail with `403`/empty at both the REST and the SSE replay surfaces.
- Create a least-privilege Postgres role via migration, run the app on that role, and assert the role cannot perform DDL or cross-schema reads.
- Define a PII policy (what is forbidden, what is allowed), apply it to log call sites, and automate it with a log-scrape assertion test.
- Write the "stand-in vs real auth" decision as a four-part tradeoff statement with the sacrificed property named.
- Demonstrate the three baselines with local evidence: test output, role-privilege query results, and a log sample.

## Warm-up

Re-read the "Authorization And Isolation" section of `posts/series-4-product-sse/02-sse-correctness.md` and the "Design Around Invariants" section of `posts/series-2-postgres/01-schema-design.md` (the local-validation vs database-shared-rules distinction). Then run one query against your dev database:

```sql
SELECT grantee, privilege_type FROM information_schema.role_table_grants WHERE table_name = 'prescriptions';
```

Note what role currently owns everything. That role is the baseline you are about to narrow.

## System specification

**Scope in**

- A migration (or a `security.sql` applied by your P01 pattern) creating `app_reader`/`app_writer`-style roles with grants limited to what the app's SQL actually needs.
- Repository-level scoping: every patient-facing read is `WHERE patient_id = :me AND ...`; the ownership check lives in the SQL, not after a fetch.
- A PII policy file (10 lines max: forbidden classes, allowed identifiers) and a log-scrape test that fails on violations.
- Evidence folder: `scoping-test.txt`, `privilege-check.txt`, `log-pii-check.txt`.

**Scope out**

- No OAuth/OIDC/JWT, no password hashing, no TLS termination — the header stand-in stays, and the document says so in one line.
- No per-field column encryption (a documented future item, not this kata).
- No changes to the broker topology or the workflow semantics.

**Functional requirements (minimal)**

1. Patient A fetching patient B's prescription via REST gets an empty/`403` result; patient A *replaying* patient B's SSE stream gets `403` with zero bytes.
2. The app runs against a role that cannot `DROP`, `TRUNCATE`, `CREATE`, or read tables outside its granted set — proven by attempting each with that role and observing denial.
3. The log-scrape test passes on the current app log and fails when a deliberate PII line is inserted (you prove the test works by breaking it).
4. `SECURITY.md` (or a section of the kata README) states the three baselines, the stand-in identity decision, and the one-line fix for real auth.

**Constraints**

- Local Docker Postgres only; local stand-ins for identity only.
- The app's *authorization* must be real SQL, never a documented wish.

## Step-by-step code-along

### Step 1: Scoping at the SQL level

**Do:** Audit every patient-facing repository method in your Ex2/Ex3 code. Any method that fetches by prescription id and checks ownership in Kotlin afterwards gets reworked: the check moves into the `WHERE` clause (`WHERE id = :prescriptionId AND patient_id = :patientId`). The return contract changes from "row + maybe deny" to "row or absent," with the service mapping absence to `403`/`404` per your A11 decision.

**Run:** `SELECT ... FROM prescriptions WHERE id = :b AND patient_id = :a;` manually in `psql` with two real ids — and expect zero rows.

**Observe:** The SQL itself returns nothing for the cross-patient case; there is no post-fetch decision to forget. Note the Kotlin idiom for the Java veteran: keep the repository returning `PrescriptionRow?` — the nullability is the security boundary, and `?: throw ForbiddenException()` at the service is the entire defense, which is easier to review than a `if (row.patientId != me)` after a full fetch.

**Decision:** One repository method with the scoping predicate vs a separate "admin read" that omits it? Nudge: one scoped method plus an *explicitly named* admin path with its own authorization is honest; an unscoped method that "happens to be used only internally" is how the cross-patient bug ships. Name the difference in a comment.

### Step 2: Prove the scoping with tests

**Do:** Write two integration tests against real Postgres: (1) patient A reading patient B's prescription via the API returns `403` (or your documented disclosure choice), and the response body contains zero prescription data; (2) patient A connecting to patient B's SSE stream with a valid `Last-Event-ID` for B's sequence range returns `403` and zero bytes — reusing the A11 test shape if it exists.

**Run:** The tests; save output to `evidence/scoping-test.txt`.

**Observe:** Both surfaces deny before any data leaves the app. The SSE test matters beyond REST because the replay path is the second authorization surface the blog post calls out — a bug that checks on connect but not in the replay query fails exactly this test.

**Decision:** `403` vs `404` for cross-patient reads? Nudge: A11 forced you to pick one for SSE; apply the *same* choice to REST so the disclosure story is consistent across surfaces, and document the reasoning once.

### Step 3: The least-privilege role

**Do:** Migration: create `pharmacy_app` role (LOGIN NOSUPERUSER), grant `USAGE` on schema, `SELECT/INSERT/UPDATE` on the specific tables the app touches, `USAGE` on sequences, and *no* DDL grants. Also create `pharmacy_migrator` (owns DDL) if your P01 flow runs migrations as a separate role — the blog post's "identity and migration roles separate" is the same principle one level down. Point the app's datasource at `pharmacy_app` in a `security` profile.

**Run:** Boot the app on the `security` profile, run the happy-path workflow test once, then as `pharmacy_app` in `psql` attempt:

```sql
DROP TABLE prescriptions;
TRUNCATE status_projection;
CREATE TABLE pwned (id int);
SELECT * FROM pg_catalog.pg_settings;  -- or any table outside the grant set
```

Save the error lines to `evidence/privilege-check.txt`.

**Observe:** The workflow passes; every privileged attempt is denied with `permission denied` and SQLSTATE `42501`. The app runs on a role that *cannot* destroy its own schema or read outside its grant — that is the least-privilege baseline as an observed fact.

**Decision:** One role for read+write vs separate reader/writer roles? Nudge: for a single-app local system, one app role with the union of needed grants is honest; split roles earn their complexity only when different code paths have different trust. Write one line on which you chose and why.

### Step 4: The PII policy and the log-scrape test

**Do:** Write `PII_POLICY.md` (10 lines): forbidden — patient names, medication names, full payloads, addresses, any field of the prescription body; allowed — prescription id, patient id, event id, trace id, status, counts, timestamps. Then a test that runs the app's failure paths (a malformed submit, a dead-lettered message, a DLQ replay), captures `docker compose logs` (or the app's log file), and asserts none of the forbidden tokens appear — via a small allowlist-aware scanner (e.g., parse JSON log lines and check field names/values against a list of regexes).

**Run:** The failure paths; then the scanner against the captured log; save to `evidence/log-pii-check.txt`. Then deliberately insert one forbidden log line in a test-only branch and re-run — the scanner must fail.

**Observe:** The clean run passes; the sabotage run fails with the offending line named. That red-green pair is the baseline: *the test proves it can detect a violation*, which is the difference between a policy and a promise.

**Decision:** Mask PII (redact in-place, keep the line) vs remove PII (drop the field, keep the line)? Nudge: for a healthcare baseline, *remove* is the default — masking leaves a shape an operator can reconstruct or a scrubber can miss; write the masked-removal tradeoff into the policy (see the fork).

### Step 5: The security write-up

**Do:** `SECURITY.md`: (1) the three baselines with the evidence commands, (2) the identity stand-in decision as a four-part tradeoff — assumption (single local app, header transport, no real users), alternative (OIDC/JWT verification), chosen (header + real SQL authorization), sacrificed (identity assurance — a caller can *claim* any patient id), (3) the one-line path to real auth: verify a token at the boundary, keep every repository predicate unchanged, (4) the disclosure decision (`403` vs `404`) and the EventSource transport caveat from the blog post.

**Run:** Read it aloud; verify every evidence command in it runs from a clean shell.

**Observe:** The document is the interview defense: it names what is guaranteed, what is a stand-in, and what real auth would *not* change — because the authorization was never the header, it was the SQL.

### Step 6: The privilege matrix (optional)

**Do:** Extend `evidence/privilege-check.txt` with a small matrix: rows = roles (`pharmacy_app`, `pharmacy_migrator`, superuser), columns = operations (`SELECT on prescriptions`, `INSERT on status_projection`, `DROP TABLE`, `TRUNCATE`), cells = PASS/DENIED as observed. Generate it with a script that runs each operation under each role.

**Run:** The matrix script; commit the output.

**Observe:** The matrix is the artifact a reviewer or interviewer can scan in ten seconds — it is the "least privilege, proven" statement in tabular form, and it doubles as the regression test when someone grants too much.

## Try this

**The cross-patient leak attempt, made real.** Run two concurrent API sessions as patients A and B (the A11 interleaving shape): A submits, B submits, each reads the other's prescription id from the seed data — and assert both reads deny, then open A's SSE stream and replay it with B's sequence range. The assertion that makes it meaningful: the deny happens *while events for both patients exist*, so a scoping bug has a real chance to fire. Save the outputs.

**Second experiment — the PII leak under failure.** Reproduce your A13 DLQ scenario with the consumer logging its exception's `toString()` on the retry path (a very natural place for a payload to leak). Run the log-scrape test and watch it fail with the payload fragment named. Then fix the call site to log the classification + event id instead, and re-run. The red-green pair on a *real failure path* — not a planted line — is the healthcare-grade evidence.

## Trade-off fork

Pick **one**, write 3–5 lines justifying it, and name the lost benefit.

- **A: header stand-in identity vs B: real token verification (JWT/OIDC) at the boundary.** The stand-in keeps the kata local and lets the SQL authorization be the star — it sacrifices identity assurance entirely: any caller can claim any patient. Real tokens raise assurance — they add a dependency, a key/jwks story, and a test surface that A14's cut-line would have to re-schedule.
- **A: one shared app role vs B: separate reader/writer roles per surface.** One role is simple, matches a single app, and is honestly least-privilege *for the app as a whole* — it cannot distinguish the read path from the write path. Split roles harden the write surface (e.g., the SSE endpoint's code literally cannot mutate) — they double the grant bookkeeping and still do not help if the app itself is the attacker.
- **A: mask PII in logs (redact in place) vs B: remove PII from logs entirely.** Masking preserves log structure and diagnosis value — it leaves a reconstructed-able shape and needs a scrubber that must never fail. Removing is unforgivingly safe — it costs the ability to diagnose payload-shaped bugs from logs, which pushes debugging to the database.

## Hints

**Hint 1:** If the scoping test passes at REST but the SSE replay test leaks, the bug is the one the blog post names: the ownership check runs on connect, and the replay *query* — a separate repository read — is missing the `patient_id` predicate. Look for any read method that takes only `prescriptionId` and `fromSequence`; it must take the caller's patient id too. If the role grants fail to boot the app, check that the role has `USAGE` on the schema and on every *sequence* the app's inserts touch — `permission denied for sequence` at runtime is the classic half-done grant.

**Hint 2:** If the log-scrape test passes but you know a leak exists, the scanner is too narrow: check *values* of structured fields against medication-name patterns (not just field names), include the `exception`/`stack_trace` fields, and scan the RabbitMQ/relay logs in the same capture — payload leaks usually live in exception `toString()`s, not in the happy-path fields. For the privilege matrix, remember `TRUNCATE` requires `TRUNCATE` privilege (not just `DELETE`) — a denial there is evidence, not a bug.

## Checkpoint / success criteria

You may leave when:

- `evidence/scoping-test.txt` shows both REST and SSE replay denying cross-patient access with zero data bytes.
- `evidence/privilege-check.txt` shows `DROP`/`TRUNCATE`/`CREATE` denied for the app role while the workflow test passes on that role.
- `evidence/log-pii-check.txt` shows the clean run passing and the sabotage run failing, including one real failure-path leak caught.
- `SECURITY.md` states the three baselines, the stand-in tradeoff in four parts, and the one-line real-auth path.
- You can say aloud: "Identity is a header stand-in locally, but authorization is real SQL — scoping lives in the repository predicates, the app runs on a role that cannot drop its own schema, and the log-scrape test fails the build on PII. The stand-in costs identity assurance, and I named that."

## Bottleneck & reflection questions

1. Your scoping is repository-level SQL. What does that protect against a *new* endpoint that forgets to call `ownsPrescription`, and what does it not protect against a *service* method that assembles data across repositories?
2. The app role cannot `TRUNCATE` — so how does your A10 rebuild-from-log recovery procedure run in production, and what does that say about the operational story around least privilege?
3. The SSE replay path is scoped by `patient_id` in the query. If a future engineer adds a `GET /prescriptions/{id}/audit` admin endpoint on the same table, which of your baselines catches the mistake, and how?
4. The header stand-in means any caller can claim any patient id. What is the *first* thing that must change to close that — and which of your three baselines survives the change untouched?
5. A reviewer reads your `SECURITY.md` and asks what a PII leak would cost *the patient* in this system. How does your answer differ from "it's a compliance issue," and does the policy's remove-vs-mask choice support that answer?

## Handoff

- **Next elective:** `../glue/X04_walkthrough_script.md` (the security story needs a 10-minute oral form), or back to `../advanced/A14_cut_line_architecture.md` if the auth deferral line needs updating.
- **Related showcase exercise:** `../../pharmacy-fulfillment/exercise_03_production.md`, Milestones 8 and 10 — this kata is the authorization/isolation evidence (Milestone 8) and part of the "what would be built next if the product gained real authentication" decision (Milestone 10).
- **Interview line:** "Three baselines, all proven locally: patient scoping is enforced in the SQL so the SSE replay path is as guarded as the GET, the app runs on a least-privilege role that cannot drop its own schema, and a log-scrape test fails the build on PII — including on failure paths, where payload leaks actually happen. Identity is a documented stand-in; authorization is not."

## Optional stretch

One harder twist: add row-level security (`ALTER TABLE ... ENABLE ROW LEVEL SECURITY` with a `patient_id = current_setting('app.patient_id')` policy) as an alternative to repository-level scoping, and compare the two with a cross-patient test. Write the five-line comparison: what RLS protects (every query path, including future ones) and what it costs (connection-level state, policy management, and an invisible predicate that makes the repository SQL harder to reason about).
