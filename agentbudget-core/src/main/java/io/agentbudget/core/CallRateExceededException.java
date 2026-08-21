package io.agentbudget.core;

import java.time.Duration;

/**
 * Thrown when a session makes calls faster than its {@link CallRateBreaker} allows. Distinct
 * from {@link BudgetExceededException} on purpose: a runaway loop and an exhausted budget are
 * different failures with different fixes, and a log that cannot tell them apart is not much
 * help at three in the morning.
 */
public final class CallRateExceededException extends BudgetDecisionException {

    private final String sessionId;
    private final int observedCalls;
    private final int maxCalls;
    private final Duration window;

    public CallRateExceededException(String sessionId, int observedCalls, int maxCalls, Duration window) {
        super("Session '%s' made %d calls within %s, exceeding its limit of %d"
                .formatted(sessionId, observedCalls, window, maxCalls));
        this.sessionId = sessionId;
        this.observedCalls = observedCalls;
        this.maxCalls = maxCalls;
        this.window = window;
    }

    public String sessionId() {
        return sessionId;
    }

    /** Calls counted within the window, including the one this exception rejected. */
    public int observedCalls() {
        return observedCalls;
    }

    public int maxCalls() {
        return maxCalls;
    }

    public Duration window() {
        return window;
    }
}
