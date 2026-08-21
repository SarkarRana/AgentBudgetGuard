# agentbudget-spring

The `@Budgeted` annotation and its Spring AOP advice, so budget enforcement stays out of your
business logic.

```java
@Service
public class SummariserService implements Summariser {

    @Budgeted(limit = "$2.00", model = "gpt-4o")
    public String summarise(@SessionId String tenantId, String document) {
        ChatResponse response = chatClient.call(document);
        BudgetedUsage.report(response.inputTokens(), response.outputTokens());
        return response.text();
    }
}
```

Import `BudgetedConfiguration` (or let the Boot starter do it) and define a `BudgetGuard` bean.
The session's spend is checked **before** the method runs: an exhausted session throws
`BudgetExceededException` and the body never executes.

## Reporting what was spent

The advice sits outside your method and cannot see the provider call inside it, so it cannot know
what the call consumed unless you say so. Two ways:

- **`BudgetedUsage.report(...)`** inside the method, as above. Call it more than once and the
  amounts accumulate, so a method making several provider calls is charged for the total.
- **Return a `GuardedResult<T>`** and the advice reads the usage off it. The envelope is handed
  back to your caller unchanged, since that is the return type you declared.

A method that does neither is *enforced* but never *charged* — the budget is checked, but nothing
accumulates towards it.

## Resolving the session id

`DefaultSessionIdResolver` looks in two places, in order:

1. the argument of the parameter annotated `@SessionId`;
2. the annotation's own `session = "..."` literal.

If neither is present it throws `SessionIdUnresolvableException` and the call is refused. That is
deliberate — see [ADR 0005](../docs/adr/0005-session-id-resolution.md). Falling back to a shared
default session would pool every user's spend into one budget, which behaves perfectly in a
single-user test and disastrously the first time one user exhausts everyone else's allowance.

Nothing reads a thread-local, a security context, or a request attribute. To do any of that,
define your own `SessionIdResolver` bean and it replaces the default with no other configuration:

```java
@Bean
SessionIdResolver tenantResolver() {
    return invocation -> SecurityContextHolder.getContext().getAuthentication().getName();
}
```

## Limits

`limit` takes the form a human writes: `"$2.00"`, `"2.00 USD"`, `"€1.50"`. A malformed value fails
when the method is first intercepted, naming the offending text.

Every distinct limit gets its own `BudgetGuard`, derived from your guard bean and **sharing its
session store**. One session therefore has one running total, and each annotated method enforces
its own ceiling against it. A consequence: every limit must be in the same currency as your guard
bean's, and one that is not fails with an explanation rather than quietly mixing units.

Omit `limit` entirely and the method is enforced against your guard bean's own limit.

## Self-invocation does not work

This is Spring AOP. The advice lives on a **proxy**, so it applies only to calls that arrive
through that proxy. One method of a bean calling another on `this` bypasses the proxy entirely
and is **not intercepted**, however it is annotated:

```java
@Budgeted(limit = "$2.00", model = "gpt-4o")
public String outer(@SessionId String tenantId) {
    return inner(tenantId);   // NOT intercepted - `this`, not the proxy
}

@Budgeted(limit = "$5.00", model = "gpt-4o")
public String inner(@SessionId String tenantId) { ... }
```

`inner`'s own `$5.00` limit is never consulted. The fixes are the usual ones: call it from another
bean, split the two methods into separate beans, or inject the bean into itself.

This is a limitation of proxy-based AOP, not of this library, and it applies equally to
`@Transactional`, `@Cacheable`, and `@Async`. It is not fixable without load-time weaving or a
Java agent, which this module deliberately does not use.

## Proxying, and what is not on the classpath

Proxying is **JDK dynamic proxies only** — your budgeted beans need an interface. The advice is a
plain `MethodInterceptor` behind a `DefaultPointcutAdvisor`, with the pointcut expressed using
Spring's own `AnnotationMatchingPointcut`.

That means no `@Aspect`, no pointcut expression language, and therefore **no `aspectjweaver`,
no `cglib`, no `byte-buddy`, and no Java agent**. A `maven-enforcer-plugin` rule fails the build
if any of them appears on the compile or runtime classpath, so this stays true rather than merely
being true today.

Two honest caveats. `byte-buddy` does appear in the test classpath, arriving through AssertJ; it
is never shipped. And `spring-core` carries its own repackaged copy of cglib under
`org.springframework.cglib`, which no Spring application can avoid — what matters is that this
module never asks for class proxying, and `BudgetedConfiguration` never sets `proxyTargetClass`.
