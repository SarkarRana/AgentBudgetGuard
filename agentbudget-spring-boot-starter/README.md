# agentbudget-spring-boot-starter

Spring Boot auto-configuration for `@Budgeted`: `application.yml` properties, default beans that
back off on your own, and HTTP exception mapping.

## Quickstart

Add the dependency and set a limit — that's the whole setup:

```yaml
agentbudget:
  limit: "$5.00"
```

```java
@Service
public class SummariserService {

    @Budgeted(limit = "${app.summarise-limit}", model = "gpt-4o")
    public String summarise(@SessionId String tenantId, String document) {
        ChatResponse response = chatClient.call(document);
        BudgetedUsage.report(response.inputTokens(), response.outputTokens());
        return response.text();
    }
}
```

Everything from [agentbudget-spring's README](../agentbudget-spring/README.md) — reporting
usage, resolving session ids, self-invocation — applies unchanged. This module adds four things.

## 1. Properties

```yaml
agentbudget:
  limit: "$5.00"          # required for the default BudgetGuard bean
  on-exceed: WARN          # STOP (default) | WARN | SWITCH_MODEL
  warn-at: "80%"            # a fraction of the limit, or an amount like "$4.00"
  on-failure: FAIL_OPEN     # FAIL_OPEN (default) | FAIL_CLOSED
  breaker:
    enabled: true
    max-calls: 20
    window: 60s
    cool-off: 5m
  fallback:                 # only consulted when on-exceed is SWITCH_MODEL
    model: gpt-4o-mini
    hard-limit: "$10.00"
```

Every field carries a Javadoc description, which `spring-boot-configuration-processor` turns
into `META-INF/spring-configuration-metadata.json` — so your IDE shows the same description on
hover as you type. Not on this list: a `PricingCatalog` or a custom `TokenEstimator`. Those are
beans, not strings, and stay code-only.

With no `agentbudget.limit` set and no `BudgetGuard` bean of your own, **no default guard is
created at all** — a Boot application that has this starter on its classpath purely as a
transitive dependency, and never touches `@Budgeted`, still starts cleanly.

## 2. Auto-configuration that backs off

Every bean is `@ConditionalOnMissingBean`: define your own `BudgetGuard`, `PricingCatalog`, or
`SessionIdResolver`, and this backs off in favour of it, no other configuration needed.

```java
@Bean
BudgetGuard budgetGuard(PricingCatalog catalog) {
    return BudgetGuard.builder()
            .limit(Money.of("5.00", "USD"))
            .pricingCatalog(catalog)
            .callRateBreaker(myOwnBreaker())   // whatever the properties can't express
            .build();
}
```

## 3. `${property}` placeholders on the annotation

`limit`, `model`, and `session` on `@Budgeted` all accept a placeholder, resolved the same way
`@Value` is — this works in plain `agentbudget-spring` too, since it is core Spring
(`EmbeddedValueResolverAware`), not a Boot feature:

```java
@Budgeted(limit = "${app.tiers.pro.limit}", model = "${app.tiers.pro.model}")
```

## 4. The budget window: `session`, `request`, or a named scope

```java
@Budgeted(limit = "$2.00", window = "request")
public String summarise(@SessionId String tenantId, String document) { ... }
```

- **`"session"`** (the default) — whatever the resolved session id naturally means to your
  application. Unchanged from `agentbudget-spring`'s behaviour.
- **`"request"`** — a fresh budget per HTTP request. Two `@Budgeted` calls in the same request
  share one ledger; the next request starts at zero. Requires an active HTTP request — calling a
  `window = "request"` method with none throws `IllegalStateException` naming the problem.
- **anything else** — a **named scope**: that literal string *becomes* the whole session id,
  ignoring the resolved caller entirely. Every caller, regardless of tenant, shares one budget
  under that name — useful for an org-wide or job-wide ceiling: `window = "org-daily-batch"`.

Windowing decorates whichever `SessionIdResolver` is active, default or your own — with one
exception: if *you* define a `SessionIdResolver` bean, this decoration is skipped entirely and
your resolver owns scoping outright (see [ADR 0005](../docs/adr/0005-session-id-resolution.md)).
That is a deliberate consequence of "a custom resolver replaces the default with no other
configuration" — windowing is a property of the default chain, not bolted onto every resolver.

## 5. HTTP exception mapping

For a servlet web application, budget exceptions surface as an HTTP status and a
[`ProblemDetail`](https://datatracker.ietf.org/doc/html/rfc7807) body instead of an unhandled 500:

| Exception | Status | Why |
|---|---|---|
| `BudgetExceededException` | 402 Payment Required | spend exhausted, as fact |
| `ProjectedBudgetExceededException` | 402 Payment Required | spend exhausted, as forecast |
| `CallRateExceededException` | 429 Too Many Requests | textbook rate limiting |
| anything else this library throws | 500 Internal Server Error | an internal or config failure, not something an API caller can act on |

```json
{
  "type": "about:blank",
  "title": "Payment Required",
  "status": 402,
  "detail": "Session 'tenant-a' has spent 2.00 USD against a limit of 2.00 USD",
  "sessionId": "tenant-a",
  "limit": "2.00 USD",
  "currentSpend": "2.00 USD"
}
```

Skipped entirely for a non-web Boot application (a batch job, a CLI), and skipped when your
application defines its own `AgentBudgetExceptionHandler` bean or otherwise handles these
exceptions itself.

## What is not here

No `agentbudget-spring-boot-starter`-specific bytecode manipulation exemption: the same
`maven-enforcer-plugin` rule from `agentbudget-spring` applies here too. `spring-boot-starter-web`
is a required dependency (needed for the `"request"` window and the exception handler), which
means the usual embedded Tomcat and Jackson come along with it — if that is unwelcome in a
non-web application, depend on `agentbudget-spring` directly instead and wire
`BudgetedConfiguration` by hand.
