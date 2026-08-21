package io.agentbudget.core;

/**
 * Thrown when pre-flight estimation projects that a call <em>would</em> breach the limit, so the
 * call is refused before it is dispatched.
 *
 * <p>Deliberately distinct from {@link BudgetExceededException}. That one is a fact — the money
 * is spent. This one is a forecast, and forecasts can be wrong: the projection rests on an
 * estimated prompt size and an assumed output allowance, so a caller may reasonably choose to
 * catch this, trim the request, and try again. Telling them apart is what makes that possible.
 */
public final class ProjectedBudgetExceededException extends BudgetDecisionException {

    private final String sessionId;
    private final Money limit;
    private final Money currentSpend;
    private final Money projectedCost;
    private final TokenUsage projectedUsage;

    public ProjectedBudgetExceededException(String sessionId, Money limit, Money currentSpend,
                                            Money projectedCost, TokenUsage projectedUsage) {
        super("Session '%s' has spent %s and this call is projected to cost a further %s, which would breach its limit of %s"
                .formatted(sessionId, currentSpend, projectedCost, limit));
        this.sessionId = sessionId;
        this.limit = limit;
        this.currentSpend = currentSpend;
        this.projectedCost = projectedCost;
        this.projectedUsage = projectedUsage;
    }

    public String sessionId() {
        return sessionId;
    }

    public Money limit() {
        return limit;
    }

    /** What the session had actually spent when the call was refused. */
    public Money currentSpend() {
        return currentSpend;
    }

    /** What the refused call was projected to cost. */
    public Money projectedCost() {
        return projectedCost;
    }

    /** The projected spend after the call, had it gone ahead. */
    public Money projectedTotal() {
        return currentSpend.plus(projectedCost);
    }

    /** The token usage the projection assumed. */
    public TokenUsage projectedUsage() {
        return projectedUsage;
    }
}
