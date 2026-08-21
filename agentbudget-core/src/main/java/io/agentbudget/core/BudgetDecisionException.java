package io.agentbudget.core;

/**
 * Base type for the guard's <em>deliberate refusals</em> — the budget was exhausted, the
 * projection did not fit, the call rate tripped. These are the library working, not the library
 * failing.
 *
 * <p>The distinction matters to {@link FailureMode}. A refusal must reach the caller under every
 * failure mode, including fail-open: swallowing it would be swallowing the whole point of the
 * library. An internal failure — a pricing lookup blowing up, a store misbehaving — is the
 * opposite, and is what fail-open is for.
 */
public abstract class BudgetDecisionException extends AgentBudgetException {

    protected BudgetDecisionException(String message) {
        super(message);
    }
}
