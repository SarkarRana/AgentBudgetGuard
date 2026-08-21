package io.agentbudget.core;

import java.util.Objects;

/**
 * Handed to a {@link BudgetEventListener} when a session's spend crosses a line. Carries the
 * snapshot at the moment of crossing, so a listener can page someone, emit a metric, or write a
 * row without going back to ask the guard anything.
 *
 * @param sessionId the session that crossed
 * @param boundary  which line — the warning threshold or the limit itself
 * @param amount    the spend at which that line sits
 * @param snapshot  the session's spend at the moment of crossing, immutable
 */
public record BudgetEvent(String sessionId, BudgetBoundary boundary, Money amount, SpendSnapshot snapshot) {

    public BudgetEvent {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(snapshot, "snapshot");
    }

    /** The session's spend when it crossed. */
    public Money currentSpend() {
        return snapshot.total();
    }

    /** The limit the session is spending against. */
    public Money limit() {
        return snapshot.limit();
    }
}
