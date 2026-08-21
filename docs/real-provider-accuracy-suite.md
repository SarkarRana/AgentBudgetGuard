# Real-provider streaming accuracy suite

**Slice:** [06 — Real-provider streaming accuracy validation](../issues/06-real-provider-accuracy.md)
**Test class:** `agentbudget-core/src/test/java/io/agentbudget/core/RealProviderStreamingAccuracyTest.java`

## What it proves

Every other streaming test in the project (`BudgetGuardStreamingTest`,
`StreamingUsageAggregatorTest`) drives the pipeline against a fake client the test itself wrote —
proof the code agrees with itself, not proof it agrees with a real provider. This suite streams a
short prompt from OpenAI and from Anthropic through the real `ChunkInspector` →
`StreamingUsageAggregator` → `BudgetGuard` pipeline, and asserts the reconciled token counts land
within one percent of what the provider itself reported. That is the acceptance bar for slice 5
(streaming reconciliation): if this suite fails, nothing built on streaming should be trusted
until it passes again.

It spends real money and needs API keys a human supplies, so it is a HITL slice, excluded from
the default build and from CI, and only runs when explicitly invoked.

## Running it

```
OPENAI_API_KEY=sk-...       \
ANTHROPIC_API_KEY=sk-ant-... \
  mvn -pl agentbudget-core -P real-provider-tests test -Dtest=RealProviderStreamingAccuracyTest
```

- Either key may be omitted; the tests for the other provider skip cleanly with a message
  explaining why, rather than failing.
- The `real-provider-tests` Maven profile clears the `real-provider` tag exclusion that keeps
  this suite out of `mvn verify` and CI (see `agentbudget-core/pom.xml`).

## What it covers

- A short, single-word reply from each provider — the base case.
- A ~150-word reply from each provider — a non-trivial, multi-chunk generation, which for
  Anthropic also exercises the two-frame merge path (`StreamingUsageAggregator`'s reason for
  existing): an opening `message_start` frame with input counts, and a closing `message_delta`
  frame that restates output only.

## Cost per run

Models used are the cheapest ones already in `BuiltInPricingCatalog`: `gpt-4o` and
`claude-3-haiku-20250307`. Prompts are short and replies are capped at 10 or 300 tokens.

| Test | Tokens (approx.) | Cost (approx.) |
|---|---|---|
| OpenAI, short | ~20 | < $0.001 |
| OpenAI, long | ~300 | ~$0.003 |
| Anthropic, short | ~20 | < $0.0001 |
| Anthropic, long | ~300 | ~$0.001 |
| **Full run (all four)** | | **well under $0.01** |

At that rate the suite can run hundreds of times over without approaching the project's
twenty-dollar development-cycle spend ceiling (issue 06's acceptance bar). If prices or model
choices change, re-check this table against the providers' current pricing pages before assuming
it still holds.
