# Explaining Tradeoffs Without Overclaiming

A Product Engineer interviewer is not looking for a design that is correct in every possible universe. They are looking for a design that is *demonstrably* correct in the universe you actually described, which means you must state your assumptions, name your alternatives, justify your choice, and — the part most candidates skip — say what you sacrificed to get it. Every tradeoff explanation is a claim about scope: what your system guarantees, where it stops guaranteeing, and why that boundary is the right one for the problem. This post turns that discipline into a rehearsable language for the pharmacy challenge, using RabbitMQ, PostgreSQL, and SSE as the proving ground.

## The four-part tradeoff statement

Before any answer about a design decision, get into the habit of delivering four moves in order:

1. **Assumption** — what the system takes for granted about the world (traffic, failure rates, users, time budget).
2. **Alternative** — what else you considered and why it was rejected.
3. **Chosen design** — what you actually built, in one or two precise sentences.
4. **Sacrificed property** — what this design gives up, stated without spin.

The fourth move is the differentiator. Most engineers are fluent in the first three and then stop, because admitting a sacrifice feels like admitting weakness. In a Product Engineer interview, the opposite is true: an unsolicited, accurate list of sacrificed properties is the strongest evidence of senior judgment. It tells the interviewer you know the space of designs well enough to see what you are *not* getting.

Weak answers are generic or absolute: "RabbitMQ is reliable" or "we use at-least-once because it's the standard." Strong answers are scoped and comparative: "given a single-node local deployment and a two-hour budget, I chose X over Y because Z, at the cost of W."

## At-least-once delivery: state it, then prove you understand the consequences

The single most common overclaim in messaging interviews is some version of "we don't lose messages." The problem is not that it is false — it is that it is unmeasurable and unowned. The honest claim is at-least-once delivery with an idempotent consumer, and the strong answer explains *what that sentence actually commits the system to*.

**Weak:**

> "Our messages are durable, so nothing gets lost. RabbitMQ confirms they're saved."

**Strong:**

> "Delivery is at-least-once. An outbox row is written in the same PostgreSQL transaction as the state change, so an event exists only after the decision is committed. The relay publishes with publisher confirms and a persistent message, and acknowledges the broker's confirmation. But none of that removes duplicates: the relay can crash after the broker accepted a message and before the outbox row is marked published, so it republishes on restart. A consumer can also receive the same message twice after a redelivery. So I never claim exactly-once — the consumer is idempotent instead: an inbox table with a unique constraint on the event ID, and business effects applied only when the inbox insert succeeds."

Now count what that answer does. It makes four separate claims (durability after commit, broker acceptance, possible duplicate publication, possible duplicate delivery) and assigns a mechanism to each. It names the exact crash window that creates duplicates. And it converts "no loss" into the precise statement *at-least-once delivery with idempotent handling of duplicates* — which is the strongest durability claim RabbitMQ-based systems can honestly make.

The interviewer will often probe one level deeper: *"What exactly can you still lose?"* The strong answer holds here too. A crash between the database commit and the outbox insert is not a message loss — it is a *state change without an event*, and the prescription's status in the database is still authoritative. That distinction — losing an *event notification* versus losing a *durable decision* — is worth stating explicitly, because it is the difference between a design whose source of truth is the database and one whose source of truth is the broker.

An interviewer can rescue a weak claim, but they cannot un-hear a precise one. Decide your scope before the interview and say it in full sentences.

## Ordering scope: per prescription, never global

The second-most common overclaim is ordering. Candidates who have built one work queue tend to say "messages are processed in order." That is either vague or false — RabbitMQ gives ordering only within a single queue and only when messages survive without redelivery, and even then, redelivery can reorder a stream.

The strong move is to *scope* the guarantee to the unit that matters to the user:

**Weak:**

> "We guarantee ordering of status updates."

**Strong:**

> "Ordering is guaranteed per prescription, not globally. Status events for one prescription flow through a single queue keyed on the prescription ID, and the consumer processes them one at a time with manual acknowledgements, so the events a patient sees are monotonic. Different prescriptions run in parallel, so overall throughput is not capped by one slow prescription. A redelivery can still interleave a duplicate of an earlier event, which is why the SSE side replays from a sequence number in the status history rather than trusting arrival order."

This answer demonstrates the exact thought pattern the series has been building: per-prescription ordering is the guarantee the patient actually experiences, and it is the only one the design needs. Global ordering is a sacrificed property — name it: "we explicitly do not guarantee global ordering, because no user depends on it and it would serialize the whole system."

Note the deeper consequence, worth volunteering: because ordering is scoped per prescription, *processing* order and *presentation* order are two different things. The database status history carries a monotonic sequence; SSE clients replay from it. The queue is a work-distribution mechanism, not the patient's event log. Saying this in one sentence pre-empts the classic follow-up "what if a packaging event arrives before the approval event?"

## Replay: the mechanism that makes "real-time" honest

When the interviewer asks about SSE, the common instinct is to advertise the realtime story and get vague about what happens when a connection drops. The strong framing flips this: the status GET endpoint is the correctness baseline, and SSE is an enhancement on top of the same history. That framing is itself a tradeoff statement — *the user-visible truth never depends on the realtime channel.*

The pattern to explain:

```sql
-- status_history: append-only, per-prescription, monotonic
CREATE TABLE status_history (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    prescription_id UUID NOT NULL REFERENCES prescriptions(id),
    sequence_no     BIGINT NOT NULL,
    status          TEXT NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (prescription_id, sequence_no)
);
```

**Weak:**

> "We stream updates to patients in real time. If the connection drops it reconnects and keeps going."

**Strong:**

> "SSE is a derived view, not a second source of truth. Each patient's stream is served from the same append-only status history, filtered by the authenticated patient's ID. Events carry `id:` fields equal to the history sequence numbers, so a reconnect with `Last-Event-ID` resumes from the exact next event — nothing is missed and nothing is replayed out of order. If SSE is down entirely, the patient's synchronous status GET still works. The tradeoff is a slightly higher polling cost per connection and a need to keep history queryable, in exchange for making the realtime channel a pure optimization of the synchronous baseline."

The key tradeoff vocabulary here: **baseline versus enhancement**. Realtime adds latency reduction; correctness was never its job. An interviewer who asks "what happens if the SSE server restarts?" should receive the `Last-Event-ID` + sequence-number answer in one breath. One who asks "what happens if SSE never existed?" should hear "the patient refreshes the status endpoint — the experience degrades, the truth does not."

A second, frequently probed tradeoff: *fan-out*. The assessed design keeps each SSE connection away from RabbitMQ — one fan-out of status events from the history, filtered per connection server-side, rather than each patient connection consuming a queue. State the sacrificed property honestly: this does not scale to thousands of concurrent connections on a single instance without horizontal sharding of history reads, and that is an accepted limitation for the challenge's scope. Naming the scale boundary is stronger than pretending the design scales forever.

## Simplicity as an explicit design axis

The challenge rewards simplicity, and so do Product Engineer interviews. But "I kept it simple" is a claim without content until you say what complexity you *removed* and what it cost. The disciplined format: for every feature you deferred, state the trigger that would justify building it.

**Weak:**

> "I kept the design simple — no auth, no UI, just the core flow."

**Strong:**

> "Within the two-to-five hour budget, the design omits authentication, a pharmacist UI, and exactly-once machinery. Each omission is deliberate: patient isolation in SSE is already enforced by server-side filtering keyed on the patient ID, so authorization is a drop-in boundary rather than a redesign. The pharmacist and packager are simulated workers because the assessed experience is the patient's, and internal staff UIs would consume the budget without producing signal. And there is no exactly-once claim anywhere — RabbitMQ does not provide it, and the inbox pattern is the honest way to absorb duplicates. The first thing I would add given another week is real authentication, not more reliability features."

The strength comes from the pattern *"I omitted X, and the seam that would unlock X is already designed."* Every deferred item gets a boundary condition. That is the product-engineer habit: a limitation is a problem only if it is unacknowledged or unbounded.

The same discipline applies to the *biggest* simplicity decision in the whole challenge — the choice to make the database the source of truth and messaging the transport. That one sentence ("every durable decision is committed to PostgreSQL first; RabbitMQ moves work afterwards") is the highest-leverage tradeoff statement in the walkthrough, because it tells the interviewer the system has one truth, one recovery story, and one place to look when something breaks. Offer that sentence unprompted.

## The exactly-once and real-time vocabulary check

Interviewers listen for a small set of words that separate practitioners from paraphrase-ers. Run yourself against this table before the interview:

| Term | Weak usage | Strong usage |
| --- | --- | --- |
| Exactly-once | "We achieve exactly-once delivery." | "We do not claim exactly-once; duplicates are possible and absorbed by an idempotent inbox." |
| Real-time | "Patients get real-time updates." | "SSE removes the refresh latency; correctness comes from replay over the status history, so realtime is an enhancement, not a guarantee." |
| Ordered | "Messages are ordered." | "Ordering is guaranteed per prescription via a single keyed queue; global ordering is explicitly not guaranteed." |
| Durable | "Messages are durable." | "The decision is durable in PostgreSQL before any event exists; the event itself is persistent and confirmed, and may still be republished once after a relay crash." |
| Reliable | "RabbitMQ is reliable." | "Delivery is at-least-once with manual acknowledgements after the business effect is committed; transient failures retry, permanent ones dead-letter." |

A good exercise: take any sentence you plan to say and append "…in exchange for…" to it. If you cannot finish the sentence, you have not stated the tradeoff yet. *"We made the database the source of truth, in exchange for the relay being an extra moving part."* *"We scoped ordering per prescription, in exchange for abandoning global ordering."* *"We kept SSE as a derived view, in exchange for realtime never being the source of truth."*

## The failure-mode version of the same skill

Tradeoff language is not only for happy-path design descriptions — it is also how you answer failure questions. The crash-window walkthrough is the same four-part structure applied to a single moment in time. Pick the strongest window from the series, the crash between the consumer's database commit and its `basicAck`:

> "If the consumer crashes after committing the business effect but before acknowledging, the message is redelivered. The inbox's unique constraint makes the duplicate a no-op, and the ack is sent only after the commit, so the invariant is: no business effect without a durable record, no acknowledgement without the effect being durable. The price of this safety is that every consumer does two writes — the effect and the inbox row — and the queue holds the message during the whole window. I accept that cost because the alternative — acknowledging before the commit — trades correctness for throughput, and the throughput win is small next to a duplicated fulfillment."

That is the whole skill in miniature: assumption (consumers can crash mid-window), alternative (ack-before-commit), choice (inbox + ack-after-commit), sacrifice (extra write, longer message retention). Practice it for four or five windows — relay crash, consumer crash, SSE disconnect, inventory contention — and the pattern becomes automatic.

## Interview takeaway

The interviewer's ear is trained to catch overclaiming, because a candidate who overclaims on the walkthrough will overclaim on the first incident call. Make your default position the scoped truth: at-least-once delivery, not exactly-once; ordering per prescription, not globally; realtime as an enhancement over a correct synchronous baseline; simplicity as a series of named omissions with their unlock conditions. Deliver every tradeoff as assumption, alternative, chosen design, sacrificed property — and if you cannot say what a decision costs, you have not finished thinking about it. Candidates who can state their own limitations precisely are the ones interviewers believe when they state their guarantees.
