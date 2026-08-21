package io.agentbudget.core;

import java.util.Objects;

/**
 * Thrown when the guard's own accounting fails and it is configured to
 * {@link FailureMode#FAIL_CLOSED}. Never a budget breach or a breaker trip — those are
 * {@link BudgetDecisionException}s and reach the caller regardless of failure mode.
 *
 * <p>The cause is the original failure, kept so the underlying bug is still diagnosable.
 */
public final class GuardFailureException extends AgentBudgetException {

    private final String operation;

    public GuardFailureException(String operation, Throwable cause) {
        super("Budget accounting failed while trying to %s; the call was blocked because this guard is fail-closed"
                .formatted(operation), cause);
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    /** What the guard was doing when it failed, in plain words. */
    public String operation() {
        return operation;
    }
}
