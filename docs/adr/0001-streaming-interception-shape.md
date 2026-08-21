# ADR 0001 — Streaming interception shape

**Status:** Accepted
**Date:** 2026-08-20
**Slice:** [04 — Streaming interception API](../../issues/04-streaming-interception-api.md)
**User stories:** 16, 21, 23

## Context

`BudgetGuard.wrap` works because a non-streaming call has one return value and one usage
record, both available at the same instant. A streaming call has neither. The response
arrives as a sequence of chunks over a live connection, the authoritative usage frame
arrives at the end (or never), and the consumer may walk away halfway through.

Two constraints bound the design, and they pull against each other:

1. **Core depends on the JDK alone.** Spring AI hands back a `Flux`, the OpenAI Java SDK
   hands back a `StreamResponse`, others hand back an `Iterator` or take a callback. Core
   cannot name any of those types, and must not drag `reactive-streams` — let alone
   Reactor — into a module whose whole value proposition is that it is dependency-free.
2. **The caller's consuming code does not change** (US-23). Whatever we hand back must be
   consumed exactly the way the caller already consumes their client's stream. A design
   that makes them convert `Flux → Iterator → Flux` has failed the story even if the
   accounting is perfect.

A third, quieter constraint decides between the survivors: **a streaming call is never
silently free.** Whatever shape we pick has to make the abort and error paths hard to
skip, because those are the paths a caller will forget to wire.

## Decision

`BudgetGuard.openStream` returns a **`StreamSession<T>`: an `AutoCloseable` accounting
handle that the caller drives from inside their own consumption loop.** Core never sees,
holds, wraps, or returns the caller's stream.

```java
StreamSession<Chunk> session = guard.openStream("sess-1", "gpt-4o", inspector);
try (session) {
    for (Chunk chunk : client.stream(prompt)) {   // the caller's own API, untouched
        session.observe(chunk);
        render(chunk);
    }
}   // close() reconciles and records; an abort or throw records what was consumed
```

The budget check happens in `openStream`, before the caller has asked their client for
anything — an over-budget session throws `BudgetExceededException` and the generation
never starts (US-21).

Chunk semantics are supplied by the caller as a **`ChunkInspector<T>`**, given once at
open time rather than per chunk. The inspector answers two questions about a chunk — what
text did it carry, and did it carry an authoritative usage frame — which is the entire
provider-specific surface of streaming accounting, isolated in one small object:

```java
ChunkInspector<Chunk> inspector =
        ChunkInspector.of(Chunk::textDelta, Chunk::usageFrame);
```

That object is also the seam slice 06 validates against real providers, and the thing a
Spring AI adapter will eventually ship as a constant.

### Why the handle rather than the alternatives

**Rejected: a type-preserving decorator with a `StreamAdapter<S>` SPI.** `wrapStream`
would take the caller's native stream plus an adapter that knows how to attach hooks to
that type, and hand the same type back. Ergonomically this is the best of the three — one
call, nothing to wire, structurally impossible to forget `close()`. It was rejected as
the *first* move, not as a bad idea. It makes every stream type unusable until somebody
ships an adapter for it, which means core's streaming support would be worth nothing
without a satellite module; and it asks us to freeze a generic hook SPI before we have
built a single working streaming path. The handle is the primitive the adapter would be
implemented on top of anyway — `attach` is `doOnNext(session::observe)` plus
`doFinally(sig -> session.close())`. Building it later is additive and breaks nothing.

**Rejected: a callback tap.** `guard.streamTap(...)` returning a plain `Consumer<T>` is
the smallest possible surface and the weakest possible guarantee. A `Consumer` has no
close, so completion, abort, and error each need a separately-wired callback. The failure
mode is a caller who wires `doOnNext` and nothing else, and gets streams that are silently
free — exactly the outcome the library exists to prevent. Rejected for making the
dangerous path the easy one.

**Rejected: decorating into JDK types.** Core could return `Iterator<T>` or `Stream<T>`
and stay honestly dependency-free. This fails US-23 outright: a Reactor caller would have
to convert out of `Flux` and back, changing exactly the code we promised not to touch.

## Consequences

**Good.**

- Core stays JDK-only, with no generics over stream types and no hook SPI to freeze early.
- One primitive covers every stream flavour — pull, push, and reactive — because the caller
  owns the iteration and we only observe.
- `close()` is the single reconciliation point, so normal completion, early `break`,
  and a thrown exception all converge on the same recording path. Under
  try-with-resources, being charged for a partial stream is the default, not an opt-in.
- `StreamingUsageAggregator` sees only `(text delta, optional usage frame)` pairs, so it
  is unit-testable with no I/O, no guard, and no provider types — which is what slice 05
  needs to attack the hard cases in isolation.

**Costs, accepted.**

- The caller writes two lines they did not write before (`observe`, and the
  try-with-resources) and can, in principle, forget them. Mitigated by `close()` being the
  only way to get a recorded cost back, and by the adapter layer later removing the wiring
  entirely for the frameworks that matter.
- `observe` may be called from a different thread than `close` on reactive pipelines, so
  `StreamSession` synchronizes rather than assuming single-threaded consumption.
- To guarantee chunks are never buffered, the aggregator retains a running **character
  count**, not the text. `TokenEstimator` therefore estimates from a count, which rules out
  a tokenizer-based fallback estimate. This is the right trade for the fallback path — it
  is an estimate by definition, and it is only reached when the provider sent no usage
  frame — but slice 05 should revisit the signature if measurement says the heuristic is
  too far off.
