# ADR 0002 — Streaming usage reconciliation precedence

**Status:** Accepted
**Date:** 2026-08-20
**Slice:** [05 — Streaming reconciliation](../../issues/05-streaming-reconciliation.md)
**User stories:** 17, 18, 19, 20

## Context

[ADR 0001](0001-streaming-interception-shape.md) settled *where* streaming accounting happens:
`StreamingUsageAggregator` sees a sequence of `(text delta, optional usage frame)` pairs and
`StreamSession.close()` asks it once for a total. It did not settle *what* that total is when the
observations disagree, and providers disagree in more ways than the happy path suggests:

- **OpenAI** (`stream_options: {include_usage: true}`) sends one usage frame as the final chunk,
  after all content. Without that option it sends none at all.
- **Anthropic** sends usage *twice*: `message_start` carries the input counts (including cache
  reads) with a placeholder `output_tokens`, and `message_delta` carries the final output count
  while leaving the input fields at zero.
- Several gateways and local runtimes send no usage frame ever.
- Any of them can stop mid-sentence, because the consumer cancelled or the connection died — in
  which case whatever frame would have closed the stream never arrives.

Two rules were already fixed by the PRD: the authoritative frame overrides interim estimates
(US-17), and a call with no frame at all still costs something (US-18). Three questions were open,
and each one is a way to under-report:

1. What happens when a **second frame** arrives — does it add to the first or replace it?
2. What happens to fields the second frame **omits**?
3. What happens to text that arrives **after** a frame?

## Decision

Reconciliation is **last word wins, per field, with an estimated top-up for whatever the last word
did not cover.**

**A later frame supersedes an earlier one; it is never summed.** Providers restate a running
total rather than emitting increments, so adding frames double-counts. Both of Anthropic's frames
describe the same message, not two messages.

**A superseding frame merges field by field: a non-zero value wins, a zero leaves the previous
value standing.** Reading Anthropic's closing `output_tokens`-only frame as a whole-record
replacement would zero the input tokens it does not mention — silently dropping the entire prompt
cost, which on a long-context call is most of the bill. Treating "absent" as "unchanged" is the
only reading under which both providers reconcile correctly.

**Text observed after the last frame is estimated and added on top, and the record is then marked
`estimated`.** A frame describes the stream as of the moment it was sent. On Anthropic's shape the
first frame arrives *before generation starts*, so "the frame wins outright" would charge one
output token for a response of any length and make every cancelled Anthropic stream effectively
free — the exact failure this library exists to prevent. The aggregator therefore counts
characters *since the last frame*, not since the start: a frame resets the counter, so text before
it is never double-counted, and text after it is never free.

The top-up is provisional. If a later frame arrives, it absorbs that text and the record goes back
to being fully authoritative — which is why a normally-completing Anthropic stream is charged
exact numbers, and only a stream cut short pays an estimate.

**`estimated` means "not wholly from the provider."** A frame topped up with an estimate is
reported as estimated, because overstating confidence is what makes an accuracy suite (slice 06)
measure the wrong thing.

**An abort and an error are not special cases.** Neither is modelled in the aggregator at all:
both are just `reconcile()` being asked earlier than usual, and it answers the same way at any
point in a stream's life. That is what keeps US-19 and US-20 from needing their own code paths —
they need only the guarantee that `close()` runs, which try-with-resources provides.

## Consequences

**Good.**

- Every provider shape in the field reconciles correctly under one rule, with no per-provider
  branching: the aggregator still knows nothing about who is on the other end of the connection.
- The dangerous outcomes are structurally unreachable. There is no path through `reconcile()` that
  returns nothing, and no path that discards observed text without a frame having accounted for it.
- `reconcile()` never throws — a negative estimate from a caller-supplied `TokenEstimator` is
  floored at zero and an overflowing total saturates — because an exception raised there would
  surface inside `close()`, where it would be suppressed by whatever error ended the stream and
  take the charge with it.
- Memory stays O(1): one character counter and one merged `TokenUsage`, whatever the stream length.

**Costs, accepted.**

- A provider that emits genuinely *incremental* per-chunk usage counts (rather than restatements)
  would reconcile to its last increment instead of a sum. No provider in scope behaves that way;
  if one appears, it needs a cumulative-vs-incremental flag on `ChunkInspector`, not a change to
  this rule.
- A frame that legitimately reports zero for a field it means to be zero cannot be distinguished
  from one that omits the field. The cost of guessing wrong is charging an earlier non-zero value,
  which over-reports slightly rather than under-reporting — the correct direction to err for a
  budget guard.
- The estimated top-up inherits the character-count heuristic's accuracy, so a cancelled stream is
  only as accurate as the estimator. Slice 06 measures how far off that is against real providers;
  it is the fallback path either way.
