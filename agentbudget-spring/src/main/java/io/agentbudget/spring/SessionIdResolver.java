package io.agentbudget.spring;

/**
 * Works out which session an intercepted call belongs to. The seam where this adapter meets the
 * host application, and the one thing the library genuinely cannot guess: a session is a tenant
 * to one application, an authenticated user to another, an agent run to a third.
 *
 * <p>{@link DefaultSessionIdResolver} is registered unless the application defines its own bean
 * of this type, in which case that one is used instead with no further configuration.
 *
 * <p>Implementations must be thread-safe, and should be fast — this runs on every intercepted
 * call, before the method does.
 */
@FunctionalInterface
public interface SessionIdResolver {

    /**
     * The session id to charge for {@code invocation}.
     *
     * @throws SessionIdUnresolvableException if no session id can be determined; the call is then
     *                                        refused rather than charged to the wrong session
     */
    String resolveSessionId(BudgetedInvocation invocation);
}
