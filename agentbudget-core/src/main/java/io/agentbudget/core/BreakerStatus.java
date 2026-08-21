package io.agentbudget.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A snapshot of one session's breaker, taken at an instant and safe to hand to another thread.
 * This is what a health endpoint reports.
 *
 * @param sessionId     the session this describes
 * @param state         whether calls are flowing
 * @param callsInWindow calls observed within the trailing window as of this snapshot
 * @param maxCalls      the most calls permitted within the window
 * @param window        the trailing window over which calls are counted
 * @param reopensAt     when an {@link BreakerState#OPEN} breaker will close again, or
 *                      {@code null} when it is already closed
 */
public record BreakerStatus(String sessionId,
                            BreakerState state,
                            int callsInWindow,
                            int maxCalls,
                            Duration window,
                            Instant reopensAt) {

    public BreakerStatus {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(window, "window");
    }

    public boolean isOpen() {
        return state == BreakerState.OPEN;
    }
}
