package io.agentbudget.core;

/**
 * What a {@link BudgetGuard} does once a session has reached its limit.
 */
public enum ExceedPolicy {

    /** Refuse the call with a {@link BudgetExceededException} before it is dispatched. */
    STOP,

    /**
     * Log the breach and let the call through. Observation mode: run the library over an existing
     * service to see what it <em>would</em> have blocked before committing to enforcing anything.
     */
    WARN,

    /**
     * Route the call to a nominated cheaper model instead of refusing it, so a long-running agent
     * degrades rather than dies. Requires a fallback model and a hard limit at which even the
     * fallback stops, both checked when the guard is built.
     *
     * <p>Only {@link BudgetGuard#call} can honour this — the guard has to be able to tell the
     * call which model to use, and an opaque {@link GuardedCall} gives it no way to.
     */
    SWITCH_MODEL
}
