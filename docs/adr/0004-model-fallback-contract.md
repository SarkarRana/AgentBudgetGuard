# ADR 0004 — The SWITCH_MODEL fallback contract

**Status:** Accepted
**Date:** 2026-08-20
**Slice:** [10 — SWITCH_MODEL fallback](../../issues/10-switch-model-fallback.md)
**User stories:** 29

## Context

`BudgetGuard.wrap` takes a `GuardedCall<T>` — an opaque supplier. The guard can invoke it or
not invoke it, and that is the entire extent of its control. `SWITCH_MODEL` asks for something
the guard has no means to do: re-issue that same call against a different model.

The library also cannot inspect the call. There is no request object to rewrite, no model field
to swap, no client to intercept. Whatever the fallback contract turns out to be, the caller has
to hand the guard something it can steer.

A second problem hides behind the first. `SWITCH_MODEL` as stated — "degrade instead of dying" —
has no stopping condition. A cheaper model is not a free model, and a guard that switches at the
limit and then runs indefinitely on the fallback has converted a budget into a suggestion.

## Decision

### The call becomes a function of the model

A second entry point, `BudgetGuard.call`, takes a **`ModelAwareCall<T>`** — `GuardedResult<T>
callWith(String model)`. The guard decides which model, and hands it in:

```java
String answer = guard.call("sess-1", "gpt-4o", model -> client.chat(model, prompt));
```

Under every policy but `SWITCH_MODEL` the model handed in is the one requested, so `call` is a
drop-in for `wrap` that costs the caller one lambda parameter. Once a `SWITCH_MODEL` session
crosses its limit, the guard passes the nominated fallback instead, and prices what comes back
at the fallback's rate.

Streaming takes the same shape by a different route. `openStream` is already called *before* the
caller opens their stream, so the selected model is simply exposed on the handle:
`StreamSession.model()`, which the caller opens their stream against.

### A hard limit bounds the degradation

`SWITCH_MODEL` requires both a `fallbackModel` and a `hardLimit` above the limit:

| Spend | Behaviour |
|---|---|
| below `limit` | requested model |
| `limit` … `hardLimit` | fallback model |
| at or above `hardLimit` | `BudgetExceededException` |

Both are validated in `build()`, along with a check that the pricing catalog can actually price
the fallback model — attempted by pricing `TokenUsage.ZERO` against it, which works for any
`PricingCatalog` implementation without adding a method to the interface.

### `wrap` refuses the switch rather than mis-pricing it

An opaque `GuardedCall` under a switched session throws `IllegalStateException` naming `call` as
the entry point that can do this. It does **not** invoke the call: doing so would send it to the
requested model and charge it at the fallback's rate — a silent under-charge, which is the exact
failure mode this library exists to prevent.

## Alternatives rejected

**A separate fallback supplier** — `wrap(session, model, primaryCall, fallbackModel,
fallbackCall)`. Two lambdas that differ only in a model string, which the caller must keep in
step by hand. Every call site duplicates its own body, and a fallback that drifts out of sync
with the primary fails silently.

**A request-rebuilding callback** — the caller supplies `Function<String, Request>` and the guard
dispatches. This makes the guard responsible for dispatch, which means knowing the client type.
Core depends on the JDK alone (ADR 0001); this reintroduces exactly the coupling that decision
was made to avoid.

**Switching at the warning threshold and stopping at the limit.** Genuinely tempting: it reuses
slice 09's threshold, needs no new configuration, and gives a natural degradation curve. Rejected
because it silently repurposes a knob the user set for a different reason — someone who
configured an 80% warning to page themselves would find it had also started rerouting their
traffic. Two behaviours, two settings.

**Inferring the fallback as "the cheapest model in the catalog."** No configuration at all, and
completely wrong: cheapest is not a proxy for acceptable, and a guard that silently rerouted an
agent to whatever was cheapest would produce results nobody asked for.

## Consequences

**Good.**

- The caller writes one lambda and gains a model parameter. That is the whole cost of adoption,
  and it reads naturally at the call site.
- Pricing stays honest by construction: the guard prices the model it selected, and it selected
  the model it handed to the call.
- The hard limit makes the budget a budget again. Degradation is bounded, and the stopping
  condition is explicit rather than implied.
- Misconfiguration fails in `build()`, at startup, rather than at the breach — which is the worst
  possible moment to discover the fallback model was never priced.

**Costs, accepted.**

- Two entry points, `wrap` and `call`, doing nearly the same thing. `call` is strictly more
  general and could have replaced `wrap` outright, but `wrap` is the shape every earlier slice
  and its tests already use, and the terser form is the one most callers want.
- `wrap` under a switched session fails at the breach, which is what slice 10's own acceptance
  criteria warn against. It cannot be caught at build time: the guard has no way to know which
  entry point a caller will use. Failing loudly is the least-bad option, and it surfaces the
  first time a session exceeds in testing.
- **The contract is honour-based.** A `ModelAwareCall` that ignores its parameter and calls the
  original model anyway is charged at the fallback's rate. The guard cannot detect this, since it
  never sees the request. It is documented on `ModelAwareCall` and `StreamSession.model()`, and
  it is the unavoidable price of never seeing the caller's client.
- Cost-based routing beyond one fallback model — a chain of progressively cheaper models — is not
  supported. One fallback covers the story; a chain can layer on later without changing this
  contract.
