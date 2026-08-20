package io.agentbudget.core;

/**
 * Thrown by {@link BudgetGuard#wrap} when the session has already reached its limit. The
 * underlying call is never invoked.
 */
public final class BudgetExceededException extends AgentBudgetException {

    private final String sessionId;
    private final Money limit;
    private final Money currentSpend;

    public BudgetExceededException(String sessionId, Money limit, Money currentSpend) {
        super("Session '%s' has spent %s against a limit of %s".formatted(sessionId, currentSpend, limit));
        this.sessionId = sessionId;
        this.limit = limit;
        this.currentSpend = currentSpend;
    }

    public String sessionId() {
        return sessionId;
    }

    public Money limit() {
        return limit;
    }

    public Money currentSpend() {
        return currentSpend;
    }
}
