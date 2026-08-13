# A07 Idempotent HTTP and Brokers — Code-Along Elective

## Objective

You already built the patient-first API in S01 and made consumers idempotent in R05. This elective closes the loop at the *other* boundary: an **`Idempotency-Key` header on `POST /prescriptions`** whose dedupe state is aligned with the message system's stable event IDs — so a patient retry cannot create a second prescription and cannot spawn a second event. One primary objective: prove with evidence that a retried submission produces one prescription, one outbox row, one message, and one consumer effect.

## Time box

- Core: 2 hours
- Optional: 0.5h for the key-vs-payload-vs-actor collision matrix (when should a key be rejected)

## Prerequisites

- S01 (`../spring/S01_hello_prescription_api.md`) — the patient POST surface. You built the endpoint; now make it retry-safe.
- R05 (`../rabbit/R05_idempotent_consumer.md`) — stable event IDs and the inbox. The message side already dedupes; this kata gives the HTTP side the same vocabulary.
- A03 (`A03_outbox_at_scale_local.md`) or R07 (`../rabbit/R07_outbox_relay_mini.md`) — the outbox is where the HTTP-side ID must land.
- Showcase position: **during Exercise 3** — the patient contract in `../../pharmacy-fulfillment/exercise_03_production.md` demands deliberate retry behavior.

## Blog & curriculum links

- Primary: `posts/series-4-product-sse/01-patient-first-api.md` (the patient-first contract)
- Secondary: `posts/series-3-rabbitmq/05-idempotency-ordering.md` (stable identity across the boundary)
- Coach-assessment gap: API design "solid starting point" → defensive retry semantics with evidence.

## Background & motivation

Every mobile health app retries POSTs. A patient double-taps "Submit" on a flaky network; the client retries; without an `Idempotency-Key` the backend creates two prescriptions, two reservations, two outbox events — and the exactly-once-effect machinery you built in R05/A04 is never even consulted, because the *events have different IDs*. The message system's honesty (at-least-once, duplicates expected) is only reachable if the HTTP boundary emits stable identity. This kata exists to align the two vocabularies: the `Idempotency-Key` the client sends, the `event_id` the outbox writes, and the `event_id` the consumer dedupes on — one identity, three boundaries. It deliberately ignores full authn/authz (A15), response-caching semantics beyond the 2xx/4xx replay rule, and multi-endpoint key namespaces beyond submissions.

## Learning objectives

- Implement `Idempotency-Key` handling on `POST /prescriptions`: first request → 201 with stored result; retry with same key → same response (replayed, not re-executed).
- Store dedupe state (`idempotency` table) keyed by `(patient, key)` with a unique constraint as the race arbiter (P03 muscle).
- Persist the *result* (prescription ID, status, headers) so replays return the original response without re-running the transaction.
- Align the key with the outbox: derive `event_id` deterministically from the key (or store the mapping) so retried submissions never mint new event IDs.
- Prove end-to-end: one POST, one retry → one prescription, one outbox row, one message, one inbox effect.
- Decide and defend the failure cases: key reuse with different payload, key without payload, missing key.

## Warm-up

Re-read the "Three Surfaces" section of `posts/series-4-product-sse/01-patient-first-api.md` — the patient surface is *submit, then observe*. Then open the S01 controller and ask: *if the same client POSTs the same payload twice because the response timed out, what are the two possible duplicate consequences, and which is worse for the patient — two prescriptions, or one prescription with two reservation events?* Write both consequences down; this elective removes both.

## System specification

**Scope in:** one patient endpoint `POST /prescriptions`; an `Idempotency-Key` header (required for submissions); an `idempotency` table `(patient_id, key, prescription_id, request_hash, response_json, status, created_at)` with `PRIMARY KEY (patient_id, key)`; deterministic `event_id` derivation from `(patient_id, key)`; replay returns the stored original response (status + body); the outbox row for the submission event uses that same `event_id`.

**Scope out:** full authn (A15), idempotency for non-submission endpoints, distributed cache shared across instances beyond the single local app, background expiry of old keys (document retention instead).

**Functional requirements:**
- Same `(patient, key)` twice → 201 first, then the stored response; the DB shows one prescription and one outbox row.
- Same key, *different* payload → `409 Conflict` (or documented key-conflict error), never a second prescription.
- Two concurrent identical requests → exactly one creates, one replays (unique constraint arbitrates).
- The consumer (R05 inbox) sees the submission event once, with the `event_id` matching the outbox row.

**Constraints:** local Docker Compose, one Spring Boot app, Kotlin, pinned Postgres + Rabbit.

## Step-by-step code-along

1. **Do:** Add the header and the table. Migration: `CREATE TABLE idempotency (patient_id uuid NOT NULL, key text NOT NULL, prescription_id uuid, request_hash text NOT NULL, response_status int NOT NULL, response_body jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (patient_id, key))`. Require the header in the controller (`@RequestHeader("Idempotency-Key")`), rejecting missing keys with `400`.
   **Run:** `docker compose up -d`; POST without the header. **Observe:** 400 with a Problem Details body (S03 error mapping). **Decision:** required vs optional key — you chose required; write the line that defends requiring it from a patient-safety angle (double-tap on a form is a patient-safety bug, not a UX quirk).

2. **Do:** The dedupe transaction. In `createPrescription`, first `INSERT INTO idempotency (patient_id, key, ...) VALUES (..., status=0, body='{}') ON CONFLICT DO NOTHING RETURNING prescription_id` (or a `try/catch` around a plain insert — pick the P03-style deterministic shape). If the insert won (rows = 1): run the real submission logic, then `UPDATE idempotency SET response_status=201, response_body=:json WHERE patient_id=:p AND key=:k`. If it lost (0 rows): the row already exists — fetch it and **replay the stored response**.
   **Run:** POST twice with the same key. **Observe:** 201 both times; `SELECT count(*) FROM prescriptions` = 1. The second call never entered the submission transaction. **Decision:** replay the *stored* response vs re-deriving it from state (nudge: stored response preserves the original body and headers exactly — re-deriving can drift as the workflow advances).

3. **Do:** The key-conflict rule. Store `request_hash` (SHA-256 of the canonical payload). On the replay path, compare the incoming payload's hash; mismatch → `409 Conflict` with a Problem Details body, never a silent replay of a different request.
   **Run:** POST key K with payload A; retry key K with payload B. **Observe:** 201 then 409; no second prescription; log line classifies the conflict as a client bug. **Decision:** hash the *canonical* payload (sorted JSON, stable field order) — write the note about why `Map.toString` hashing would break this (field order is not stable across Jackson versions).

4. **Do:** Align with the outbox. In the submission transaction, derive `event_id` as a deterministic UUID from `(patient_id, key)` (e.g. `UUID.nameUUIDFromBytes("submission:$patient:$key".toByteArray())` — UUID v3 from a namespace string) instead of `UUID.randomUUID()`. Write the outbox row (A03/R07 shape) with that `event_id`.
   **Run:** POST twice with the same key; watch the outbox table and the management UI. **Observe:** one outbox row; one message published (relay dedupes by `event_id` — the second POST never created a second outbox row because the submission transaction ran once). **Decision:** derive-from-key vs store-a-random-UUID-in-the-idempotency-row (nudge: deriving makes the mapping *inspectable* — any operator can recompute the event ID from the request; a stored UUID is equally correct but hides the link).

5. **Do:** The end-to-end proof. A Testcontainers test: POST with key K (assert 201 + `Location`), retry with K (assert identical body + `Location`), then consume the Rabbit message and assert the R05 inbox applied it once, with `event_id` == the derived ID == the outbox row's `event_id`.
   **Run:** `./gradlew test`. **Observe:** three tables (prescriptions, outbox, inbox) and one Rabbit message all agree on one identity. **Decision:** whether the test asserts the *derived* ID by recomputing it (yes — that is the contract, not a coincidence).

6. **Do:** Wire into Ex3 (`../../pharmacy-fulfillment/exercise_03_production.md` patient contract): submission retries become explicit in the API docs and the guarantee ledger (Milestone 10) — "a retried submission with the same key is a replay, not a new submission."
   **Run:** Ex3's patient-behavior tests. **Observe:** no regression; the patient double-tap scenario now has a documented, tested answer.

## Try this

**The two-thread double-tap.** Fire two concurrent requests with the same key (a small driver script, two threads, same payload). Run it 20 times and count: exactly one `201` + one `200/201` replay pair per run, one prescription, one outbox row. Then re-run with a `Thread.sleep` inserted *inside* the submission transaction after the idempotency insert — if your design does the check-then-act (read row, then write), the race produces two prescriptions in some runs; if the constraint arbitrates, it never does. The observation to say aloud: *the unique constraint is the referee; my code only decides who wins the race, and the loser replays.*

## Trade-off fork

Pick one pair, implement it, justify in 3–5 lines:

- **Key = message ID vs key separate from message ID:** deriving `event_id` from the key (what you built) ties HTTP and messaging identity together — one identity to trace, but you can't reuse the key pattern across event types without namespacing. Separate IDs (random `event_id` stored in the idempotency row) decouples the layers but adds a mapping table and a second identity to correlate. Name the lost benefits of the one you didn't pick.
- **Client-generated key vs server-side dedupe (e.g. content hash as key):** client keys survive client restarts and let the client own retry semantics, but a buggy client can reuse a key wrongly. Server-side content-hash dedupe needs no client cooperation but can't distinguish "retry of the same submission" from "genuinely identical second submission" — a real pharmacy problem if a patient's two prescriptions are identical. Both are defensible; write your lines with the patient in mind.

## Hints

- **Hint 1:** The stored response body must be captured *inside the same transaction as the submission* (or immediately after commit) — if the app crashes between commit and response write, a retry would replay a blank row. The idempotency `UPDATE` after the submission insert is the pattern; the crash window is A03's relay window wearing HTTP clothes.
- **Hint 2:** If two threads both call `ON CONFLICT DO NOTHING`, exactly one insert wins and the other blocks briefly then sees the winner's row — that's your race-safe path. Do not add a `SELECT` before the insert; that re-introduces the check-then-act bug R05 warned about. For `event_id` derivation, `UUID.nameUUIDFromBytes` is deterministic and Java-visible; document the namespace string so operators can recompute.

## Checkpoint / success criteria

Done when:

- Retry with same key: 201 then replay, one prescription, one outbox row (row-count evidence).
- Same key + different payload: 409, zero new prescriptions (evidence).
- Concurrent double-tap × 20 runs: exactly one winner per run, one effect (log + counts).
- End-to-end test: HTTP idempotency, outbox `event_id`, Rabbit message, and inbox effect all share one identity.
- API docs + guarantee ledger entry for submission retry semantics.

## Bottleneck & reflection questions

1. The key is scoped to `(patient_id, key)`. If the patient header is spoofable in production (no real authn yet — A15), what does a *different* patient reusing the same key see? Say the worst case out loud.
2. Your replay returns the stored 201 body. If the submission later becomes `REJECTED`, a retried request still replays "201 created" — is that the right contract, or should replay reflect live status? (Patient experience question, not just semantics.)
3. The derived `event_id` means a patient retry can never create a duplicate message — but it also means a *new* submission with a *new* key always mints a new event. Where does that leave the consumer's exactly-once-effect machinery — redundant or still necessary?
4. Idempotency rows grow forever. What retention policy is safe given that a mobile client may retry for days, and how does pruning interact with the outbox's own retention (A03)?
5. In a 2-hour submission, would you ship `Idempotency-Key` or the DLQ first? Defend the order in product terms, not protocol terms.

## Handoff

- Next: A08 (`A08_connection_channel_lifecycle.md`) — the transport under the publisher side. Or A15 (`A15_security_baselines.md`) when you're ready to make the patient-scoping story real.
- Related showcase work: `../../pharmacy-fulfillment/exercise_03_production.md` — patient contract + Milestone 10 guarantee ledger; your key→event-id mapping is a crash-matrix row.
- Interview line: *"Submissions are retried by real clients, so POST /prescriptions takes an Idempotency-Key: the unique constraint on (patient, key) arbitrates the race, the first request stores its response and replays it on retry, and the outbox event ID is derived from the same key — so a patient double-tap produces one prescription, one outbox row, one message, and one consumer effect, and a retry is a replay, never a duplicate submission."*

## Optional stretch

Build the key-conflict matrix as a small table-driven test suite: `(same key, same payload) → replay`, `(same key, different payload) → 409`, `(different key, same payload) → new submission`, `(same key, different patient) → independent — new submission`. Each row is a product decision; document each as an explicit contract, then extend the matrix to two keys racing on the same patient with the second arriving after the first commits.
