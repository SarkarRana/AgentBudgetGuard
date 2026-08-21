package io.agentbudget.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock a test moves by hand. Every timing assertion in this suite advances one of these
 * instead of sleeping, so the tests are deterministic and finish instantly.
 */
final class MutableClock extends Clock {

    private final ZoneId zone;
    private Instant instant;

    private MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    static MutableClock startingAt(Instant instant) {
        return new MutableClock(instant, ZoneId.of("UTC"));
    }

    static MutableClock startingAtEpoch() {
        return startingAt(Instant.EPOCH);
    }

    void advance(Duration amount) {
        instant = instant.plus(amount);
    }

    void advanceSeconds(long seconds) {
        advance(Duration.ofSeconds(seconds));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
