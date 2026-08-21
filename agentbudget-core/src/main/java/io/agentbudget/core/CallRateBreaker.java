package io.agentbudget.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Runaway-loop protection, counted per session and independent of spend. A session may make at
 * most {@code maxCalls} within a trailing {@code window}; the call that would exceed that is
 * rejected with {@link CallRateExceededException}, and the session stays rejected until
 * {@code coolOff} has elapsed.
 *
 * <p>Separate from the budget by design. A tight tool-calling loop against a cheap model can run
 * for a long time before it costs a dollar, so the thing worth reacting to is the rate, not the
 * spend — and a guard with a generous limit still needs this.
 *
 * <p><strong>Where the boundary sits.</strong> {@code maxCalls} is the number permitted, not the
 * number at which rejection starts: with {@code maxCalls(5)}, the fifth call in the window is
 * allowed and the sixth trips. Calls leave the window as it slides, so a session calling steadily
 * but under the rate never trips, however long it runs.
 *
 * <p>Time comes exclusively from the injected {@link Clock}. Nothing here sleeps, reads
 * {@code System.currentTimeMillis()}, or schedules anything, so a test drives cool-off and window
 * expiry by advancing a fake clock.
 */
public final class CallRateBreaker {

    private final int maxCalls;
    private final Duration window;
    private final Duration coolOff;
    private final Clock clock;
    private final int maxSessions;
    private final Map<String, SessionBreaker> breakers;

    private CallRateBreaker(int maxCalls, Duration window, Duration coolOff, Clock clock, int maxSessions) {
        this.maxCalls = maxCalls;
        this.window = window;
        this.coolOff = coolOff;
        this.clock = clock;
        this.maxSessions = maxSessions;
        this.breakers = new HashMap<>();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Counts one call against {@code sessionId}'s rate, or rejects it.
     *
     * <p>Call this immediately before dispatching, and only for calls that are actually about to
     * go out: a call the budget already refused never reached the provider and must not count
     * towards a loop.
     *
     * @throws CallRateExceededException if the breaker is open, or if this call would exceed the
     *                                   configured rate — which opens it
     */
    public synchronized void recordCall(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");

        Instant now = clock.instant();
        SessionBreaker breaker = breakerFor(sessionId);

        if (breaker.isOpenAt(now)) {
            throw new CallRateExceededException(sessionId, breaker.callsInWindow(now), maxCalls, window);
        }

        int wouldBe = breaker.callsInWindow(now) + 1;
        if (wouldBe > maxCalls) {
            breaker.openUntil(now.plus(coolOff));
            throw new CallRateExceededException(sessionId, wouldBe, maxCalls, window);
        }
        breaker.recordAt(now);
    }

    /**
     * This session's breaker as it stands, without counting a call against it. Safe to poll from
     * a health endpoint.
     */
    public synchronized BreakerStatus status(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");

        Instant now = clock.instant();
        SessionBreaker breaker = breakerFor(sessionId);
        boolean open = breaker.isOpenAt(now);
        return new BreakerStatus(sessionId,
                open ? BreakerState.OPEN : BreakerState.CLOSED,
                breaker.callsInWindow(now),
                maxCalls,
                window,
                open ? breaker.openUntil : null);
    }

    /**
     * Forgets {@code sessionId}'s call history and closes its breaker. For a long-lived process
     * starting a fresh session's worth of work under a reused id.
     */
    public synchronized void reset(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        breakers.remove(sessionId);
    }

    private SessionBreaker breakerFor(String sessionId) {
        SessionBreaker breaker = breakers.get(sessionId);
        if (breaker == null) {
            evictSettledSessions();
            breaker = new SessionBreaker();
            breakers.put(sessionId, breaker);
        }
        return breaker;
    }

    /**
     * Drops sessions that hold nothing worth remembering — no calls inside the window and no
     * open cool-off — once the map has grown past its cap. A session that has gone quiet is
     * indistinguishable from one that never existed, so forgetting it changes no behaviour.
     */
    private void evictSettledSessions() {
        if (breakers.size() < maxSessions) {
            return;
        }
        Instant now = clock.instant();
        breakers.values().removeIf(breaker -> breaker.isSettledAt(now));
    }

    private final class SessionBreaker {

        private final Deque<Instant> calls = new ArrayDeque<>();
        private Instant openUntil;

        private boolean isOpenAt(Instant now) {
            if (openUntil == null) {
                return false;
            }
            if (now.isBefore(openUntil)) {
                return true;
            }
            // cool-off elapsed: close, and start the session's rate count fresh, otherwise the
            // calls that tripped it would trip it again the moment it reopened.
            openUntil = null;
            calls.clear();
            return false;
        }

        private int callsInWindow(Instant now) {
            Instant cutoff = now.minus(window);
            while (!calls.isEmpty() && !calls.peekFirst().isAfter(cutoff)) {
                calls.pollFirst();
            }
            return calls.size();
        }

        private void recordAt(Instant now) {
            calls.addLast(now);
        }

        private void openUntil(Instant until) {
            openUntil = until;
        }

        private boolean isSettledAt(Instant now) {
            return !isOpenAt(now) && callsInWindow(now) == 0;
        }
    }

    public static final class Builder {
        private int maxCalls;
        private Duration window;
        private Duration coolOff;
        private Clock clock = Clock.systemUTC();
        private int maxSessions = 10_000;

        /** The most calls a session may make within the window. */
        public Builder maxCalls(int maxCalls) {
            if (maxCalls <= 0) {
                throw new IllegalArgumentException("maxCalls must be positive, got " + maxCalls);
            }
            this.maxCalls = maxCalls;
            return this;
        }

        /** The trailing window over which calls are counted. */
        public Builder window(Duration window) {
            this.window = requirePositive(window, "window");
            return this;
        }

        /**
         * How long a tripped session stays rejected before its breaker closes again. Defaults to
         * the window when unset.
         */
        public Builder coolOff(Duration coolOff) {
            this.coolOff = requirePositive(coolOff, "coolOff");
            return this;
        }

        /**
         * Overrides the clock the breaker times against. Defaults to the system clock; every test
         * substitutes a manually-advanced one so nothing has to sleep.
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Caps how many sessions the breaker tracks before it sweeps out settled ones. Only
         * sessions with no recent calls and no open cool-off are dropped, so the cap bounds
         * memory without ever forgetting a session that is currently misbehaving.
         */
        public Builder maxSessions(int maxSessions) {
            if (maxSessions <= 0) {
                throw new IllegalArgumentException("maxSessions must be positive, got " + maxSessions);
            }
            this.maxSessions = maxSessions;
            return this;
        }

        public CallRateBreaker build() {
            if (maxCalls == 0) {
                throw new IllegalArgumentException("maxCalls must be set");
            }
            if (window == null) {
                throw new IllegalArgumentException("window must be set");
            }
            return new CallRateBreaker(maxCalls, window, coolOff != null ? coolOff : window, clock, maxSessions);
        }

        private static Duration requirePositive(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive, got " + value);
            }
            return value;
        }
    }
}
