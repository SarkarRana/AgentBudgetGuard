# ADR 0005 — Session-id resolution in the Spring adapter

**Status:** Accepted
**Date:** 2026-08-20
**Slice:** [14 — @Budgeted annotation and AOP aspect](../../issues/14-budgeted-annotation-aop.md)
**User stories:** 52, 53, 56, 57

## Context

`@Budgeted` has to charge *some* session, and the adapter has to decide which one without being
told. This is the seam where the library meets the host application, and it is the one thing the
library genuinely cannot guess: a session is a tenant to one application, an authenticated user
to another, an agent run to a third, a single HTTP request to a fourth.

Slice 03 already fixed one half of the answer — "the library does not invent session scoping: no
implicit thread-local, no request magic." That constrains what the default may do, but it does
not say what the default *is*, and "make it pluggable" only moves the question: whatever ships as
the default is what most people will run.

The failure mode to design against is specific. A default that quietly resolves *something* for
every call is a default that works in every test anyone writes — because tests have one user —
and fails in production the first time two users share a process.

## Decision

**`SessionIdResolver` is the strategy interface. `DefaultSessionIdResolver` resolves explicitly,
in two steps, and refuses when it cannot.**

1. The argument of the parameter annotated `@SessionId`.
2. Failing that, the annotation's own `session = "..."` literal.
3. Failing that, throw `SessionIdUnresolvableException` and refuse the call.

A custom resolver replaces the default by existing — `BudgetedConfiguration` declares no
`SessionIdResolver` bean at all, taking whichever one the context has via `ObjectProvider` and
falling back to the default only when there is none. One bean, no other configuration.

The strategy receives a `BudgetedInvocation` — a small value record of method, arguments,
annotation and target — rather than Spring's `MethodInvocation`. Implementing a resolver
therefore does not mean learning the AOP API, and a resolver can be unit-tested without a proxy.

## Alternatives rejected

**Fall back to a shared default session** (`"default"`, or the bean name). The tempting one,
because it makes the quickstart shorter and nothing ever throws. Rejected outright: it pools every
user's spend into one budget. It behaves correctly in a single-user test and disastrously in
production the first time one user exhausts everyone else's allowance — and it does so *silently*,
which is the worst property a budget library can have. Refusing is loud, immediate, and impossible
to ship by accident.

**Read the Spring Security principal by default.** The most "helpful" option, and genuinely what
many applications want. Rejected because it makes `spring-security` a de facto dependency of the
adapter, and because it is exactly the implicit context-reading slice 03 ruled out. It is three
lines as a custom resolver, and the README shows those three lines.

**Read a request-scoped attribute or `RequestContextHolder`.** Same objection, plus it silently
restricts the adapter to web applications — an agent run from a scheduled job or a queue consumer
has no request, and would fail at runtime in a way the annotation gives no hint of.

**Require the session id as the first parameter, by position.** No annotation needed, but
positional contracts are invisible at the call site and break silently under refactoring. The
annotation costs one import and says what it means.

**A SpEL expression on the annotation** — `@Budgeted(session = "#tenantId")`. Flexible, and how
`@Cacheable` does it. Rejected for this slice: it needs an expression evaluation context, turns
typos into runtime failures, and the custom-resolver route already covers everything SpEL would,
in ordinary Java. Worth revisiting if it is genuinely asked for.

## Consequences

**Good.**

- The default is explicit. Reading `@SessionId` off a signature is visible at every call site, and
  nothing is resolved from ambient state that a reader of the method cannot see.
- It cannot silently pool budgets. The one catastrophic failure mode is unreachable by
  construction, because the alternative to resolving is refusing.
- The adapter stays free of `spring-security` and of any web dependency, and works in a plain
  `spring-context` application that is not a Boot application at all.
- Custom resolution is one bean and costs nothing to discover — the interface is a
  `@FunctionalInterface` over a value type.

**Costs, accepted.**

- **The quickstart is one annotation longer.** `@SessionId` has to appear on a parameter, and
  someone who forgets it gets an exception on their first call rather than a working demo. This
  is the trade the whole decision turns on, and it is deliberate: the failure is immediate and
  the message names all three ways to fix it.
- A method with no natural session parameter must either declare one, use the `session` literal,
  or supply a resolver. That is friction, and it is friction in exactly the case — a service with
  no visible notion of who it is acting for — where an implicit default would be most dangerous.
- Resolution runs per call and reflects over the method signature. Parameter indexes are cached
  per `Method` in a `ConcurrentHashMap`, so the reflection happens once and the steady-state cost
  is a map lookup.
- The `session` literal is a plain string in this slice, so a per-environment session naming
  scheme needs a custom resolver. Property-placeholder support arrives with slice 15.
