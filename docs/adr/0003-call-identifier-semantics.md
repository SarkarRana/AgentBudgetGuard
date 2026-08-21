# ADR 0003 — What a call identifier identifies

**Status:** Accepted
**Date:** 2026-08-20
**Slice:** [07 — Retry-safe recording via call identifiers](../../issues/07-retry-idempotency.md)
**User stories:** 24, 25

## Context

Retries pull the ledger in two directions at once, and slice 07 has to satisfy both:

> Three attempts where two failed at the connection and one succeeded is **one** charge.
> Three attempts where the provider generated tokens each time is **three**.

The instinct is to key recording on "the logical call" — one identifier per thing the
application asked for, retries folded underneath it. That gets the first sentence right and
the second one badly wrong: three generations that each cost real money would be charged once,
and a budget guard that under-reports by a factor of three is worse than no guard at all.

The opposite instinct — record once per invocation, no identifier — gets the second sentence
right and gives up on US-25 entirely. Instrumentation that fires at-least-once (a retried hook,
a replayed webhook, an at-least-once event pipeline) would double-charge, and there would be no
way for a caller to say "this is the same attempt you already saw."

## Decision

**A `CallId` identifies one provider attempt, not one logical call.** Recording is idempotent per
id: `UsageLedger.record(callId, cost)` charges the first record of an id and ignores every later
one, including one that arrives with different usage.

Two rules make the retry cases fall out of that single semantic rather than needing their own
handling:

1. **An attempt that cost nothing records nothing, and does not claim its id.** A connection-level
   failure never reaches the ledger at all (the guard records only on success or
   `PartialUsageException`), and a zero cost that does reach it is a no-op. The id stays free.
2. **`BudgetGuard` generates a fresh id per invocation** when the caller supplies none. A retry
   loop outside the guard therefore produces a distinct id per attempt by construction.

The two sentences from the issue then resolve as:

| Scenario | Ids | Charges |
|---|---|---|
| 2 connection failures, then success — caller reuses one id | one, reused | 1 — the failures never claimed it |
| 2 connection failures, then success — generated ids | three | 1 — only the success cost anything |
| 3 attempts that each generated tokens — generated ids | three | 3 |
| Same attempt recorded twice (at-least-once instrumentation) | one, reused | 1 |

Note the third row read against the first: **reusing one id across a retry sequence is only
correct when at most one attempt consumes tokens.** That is the common case (a connection reset
before the provider responds), and it is what a caller reaching for "an id for my logical call"
usually means. It is nonetheless a choice with a cost, stated below.

Recorded ids are remembered per session in a bounded access-ordered window
(`UsageLedger.DEFAULT_RECORDED_CALL_HISTORY`, 1024, overridable on the builder).

## Consequences

**Good.**

- One rule — idempotent per attempt id — covers both retry sentences and US-25, with no notion of
  "retry" anywhere in the code. The ledger does not know what a retry is, and does not need to.
- The default is the safe direction. A caller who supplies nothing gets one charge per attempt
  that consumed tokens, which over-reports relative to a logical-call view rather than under.
- Idempotency is scoped to a session's ledger, so the same id under two sessions charges both —
  correct, since ids are the caller's namespace and collisions across tenants are their business.
- The remembered window is bounded, so a long-lived session's ledger cannot grow without limit,
  in the same spirit as the session eviction from slice 03.

**Costs, accepted.**

- A caller who reuses one id across three attempts that *each* generated tokens is charged once
  and under-reports. This is unavoidable given the same id genuinely cannot distinguish "you saw
  this attempt already" from "this is a new attempt of the same call"; the javadoc on `CallId`
  says so directly. Distinguishing them would need the caller to tell us which — an attempt
  counter alongside the id — and no user story asks for it.
- A duplicate id is charged at whatever it was *first* recorded as. A replay carrying corrected
  (higher) usage will not correct the total. Last-write-wins would fix that and break the
  at-least-once guarantee, which is the one the story actually asks for.
- An id replayed after 1024 further calls in the same session has fallen out of the window and is
  charged again. Retries and duplicate deliveries arrive within seconds; a replay that far back is
  not the case this protects against, and the alternative is an unbounded set per session.
- Recording now checks and updates two pieces of state as one step, so `UsageLedger` is guarded by
  its monitor instead of the lock-free `AtomicReference` loop it used before. The critical section
  is a map lookup next to an LLM round-trip, so the contention is not real.
