package io.agentbudget.core;

/**
 * What happens when the guard's <em>own</em> accounting fails — a pricing lookup throws, a
 * {@link SessionStore} implementation misbehaves, a ledger update blows up.
 *
 * <p>Not to be confused with a budget breach or a breaker trip. Those are
 * {@link BudgetDecisionException}s: the library doing its job, and they reach the caller under
 * both modes.
 */
public enum FailureMode {

    /**
     * Log the failure and let the call through unmetered. The default, because a budget library
     * that takes down the application it was added to protect has failed at something more
     * important than accounting.
     */
    FAIL_OPEN,

    /**
     * Throw {@link GuardFailureException} and block the call. For a cost-critical service, where
     * an unmetered call is worse than a failed one.
     */
    FAIL_CLOSED
}
