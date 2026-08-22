# AgentBudgetGuard

Budget enforcement and a circuit breaker for Java AI agents. Wrap a call to any LLM client (or
annotate a Spring bean method), give it a spend limit, and the session that exceeds it is refused
before another dollar goes out -- instead of your bill telling you about it tomorrow.

```java
BudgetGuard guard = BudgetGuard.builder()
        .limit(Money.of("2.00", "USD"))
        .pricingCatalog(BuiltInPricingCatalog.catalog())
        .build();

String answer = guard.wrap("user-42", "gpt-4o", () -> {
    ChatResponse response = openAiClient.chat("gpt-4o", prompt);
    return GuardedResult.of(response.text(), TokenUsage.of(response.inputTokens(), response.outputTokens()));
});
```

A session that has already spent `$2.00` throws `BudgetExceededException` here, before
`openAiClient.chat` is ever called.

## Status

`0.1.0`, published on Maven Central under `io.github.sarkarrana` -- add the dependency shown
below and it resolves like any other library, no local build required. See
[issue 17](issues/17-maven-central-publishing.md) for how it got there.

## Quickstart

Two ways to use this library, both charging the same underlying ledger: wrap a call directly, or
annotate a Spring bean method and let the AOP advice do it. Pick whichever fits how your calls are
already structured -- they compose, so a codebase can use both.

### Wrapping a call directly

Needs only `agentbudget-core`, and nothing about your project beyond the JDK:

```xml
<dependency>
    <groupId>io.github.sarkarrana</groupId>
    <artifactId>agentbudget-core</artifactId>
    <version>0.1.0</version>
</dependency>
```

```java
BudgetGuard guard = BudgetGuard.builder()
        .limit(Money.of("2.00", "USD"))
        .pricingCatalog(BuiltInPricingCatalog.catalog())
        .build();

try {
    String answer = guard.wrap("user-42", "gpt-4o", () -> {
        ChatResponse response = openAiClient.chat("gpt-4o", prompt);
        return GuardedResult.of(response.text(),
                TokenUsage.of(response.inputTokens(), response.outputTokens()));
    });
} catch (BudgetExceededException e) {
    // e.sessionId(), e.limit(), e.currentSpend() -- refuse, degrade, or surface to the caller
}
```

The session id (`"user-42"` above) is whatever scoping makes sense to your application -- a user
id, a tenant id, an agent run id. Nothing here invents scoping on its own; one `BudgetGuard`
tracks as many independent sessions as you give it distinct ids for.

### The `@Budgeted` annotation (Spring Boot)

Add `agentbudget-spring-boot-starter` and the limit resolves from `application.yml`:

```xml
<dependency>
    <groupId>io.github.sarkarrana</groupId>
    <artifactId>agentbudget-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

```yaml
agentbudget:
  limit: "$5.00"
```

```java
@Service
public class SummariserService {

    @Budgeted(limit = "$2.00", model = "gpt-4o")
    public String summarise(@SessionId String userId, String document) {
        ChatResponse response = chatClient.call(document);
        BudgetedUsage.report(response.inputTokens(), response.outputTokens());
        return response.text();
    }
}
```

The session's spend is checked **before** the method runs -- an exhausted session throws
`BudgetExceededException` and the body never executes. For a web application, that exception
already maps to `HTTP 402 Payment Required` with no code of your own; see
[agentbudget-spring-boot-starter's README](agentbudget-spring-boot-starter/README.md) for the
full status table, `application.yml` reference, and the one hard rule of proxy-based AOP
(self-invocation isn't intercepted).

`agentbudget-spring` alone (without the Boot starter) gets you the annotation and
`${property}` placeholders without pulling in `spring-boot-starter-web` -- see
[its README](agentbudget-spring/README.md).

## Streaming

The same budget, for a call that arrives in pieces. The check happens before the stream opens;
usage is watched as chunks pass through your own consumption loop, and reconciled and charged
when the session closes -- whether the loop finished, broke early, or threw:

```java
ChunkInspector<ChatCompletionChunk> inspector = ChunkInspector.of(
        ChatCompletionChunk::textDelta,   // the text this chunk carried, or null
        ChatCompletionChunk::usageFrame); // the provider's final usage frame, or null

try (StreamSession<ChatCompletionChunk> session = guard.openStream("user-42", "gpt-4o", inspector)) {
    for (ChatCompletionChunk chunk : client.stream(session.model(), prompt)) {
        session.observe(chunk);
        render(chunk);
    }
} // usage reconciled and charged here

System.out.println("This stream cost " + session.recordedCost());
```

Open the stream against `session.model()`, not the model you asked for -- under
`ExceedPolicy.SWITCH_MODEL` the guard may have nominated a cheaper fallback, and pricing has to
match what actually ran. A stream is never silently free: a provider that sends no final usage
frame is charged a text-length estimate instead, marked as such on the reconciled result.

## Custom model pricing

The built-in catalog covers current OpenAI and Anthropic models. Add your own on top of it --
a self-hosted model, a provider not yet in the catalog, or a rate you negotiated directly:

```java
PricingCatalog catalog = BuiltInPricingCatalog.withCustomRegistration()
        .register("my-self-hosted-llama", ModelPricing.perMillionTokens("USD", /* input */ 0.20, /* output */ 0.20))
        .build();

BudgetGuard guard = BudgetGuard.builder()
        .limit(Money.of("5.00", "USD"))
        .pricingCatalog(catalog)
        .build();
```

Or build one with nothing pre-registered:

```java
PricingCatalog catalog = StaticPricingCatalog.builder()
        .register("my-model", ModelPricing.perMillionTokens("USD", 1.00, 3.00))
        .onUnknownModel(StaticPricingCatalog.UnknownModelBehavior.THROW_EXCEPTION) // the default
        .build();
```

`ModelPricing.perMillionTokens(currency, input, output)` covers the common case where cached
input tokens price the same as fresh ones; pass a third rate
(`perMillionTokens(currency, input, cachedInput, output)`) when a provider prices cached input
separately, as OpenAI and Anthropic both do.

In a Spring Boot application, define a `PricingCatalog` `@Bean` and the starter's own default
backs off in its favour automatically -- see
[`agentbudget-demo`'s `DemoPricingConfiguration`](agentbudget-demo/src/main/java/io/agentbudget/demo/DemoPricingConfiguration.java)
for exactly that, wired into a running app.

## See it running: `agentbudget-demo`

A small Spring Boot app, checked into this repository, that runs the annotation style end to end
with no API key required:

```bash
mvn install               # once, so the demo can resolve its sibling modules
cd agentbudget-demo
mvn spring-boot:run
```

Then a few `curl` requests show a session's spend climbing and the third call refused with a 402
naming exactly how much it had spent against its limit. Full walkthrough in
[agentbudget-demo's README](agentbudget-demo/README.md).

## Modules

| Module | What it is |
|---|---|
| [`agentbudget-core`](agentbudget-core) | The engine: `BudgetGuard`, pricing, streaming reconciliation, the circuit breaker. Depends on the JDK only. |
| [`agentbudget-spring`](agentbudget-spring) | The `@Budgeted` annotation and its Spring AOP advice. Boot-free. |
| [`agentbudget-spring-boot-starter`](agentbudget-spring-boot-starter) | Auto-configuration, `application.yml` properties, HTTP exception mapping. |
| [`agentbudget-demo`](agentbudget-demo) | The runnable app above. Not published. |

Design rationale for the trickier decisions -- how streaming interception is shaped, how a call
id is defined, how a session id resolves -- lives in [`docs/adr`](docs/adr).

## Other things this library does

Beyond what is above: a `CallRateBreaker` that stops a runaway loop by call rate rather than
spend, an early warning threshold notified before the limit itself, `ExceedPolicy.SWITCH_MODEL`
to degrade to a cheaper model instead of stopping outright, pre-flight cost estimation that
refuses a call before it is even dispatched, and `FailureMode` controlling whether a bug in the
guard's own accounting ever becomes an outage. Each module's README covers the parts it owns.
