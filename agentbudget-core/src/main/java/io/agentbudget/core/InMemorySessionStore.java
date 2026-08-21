package io.agentbudget.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The default {@link SessionStore}: session ledgers held in a bounded, in-process map. No
 * external infrastructure — this is what makes the library usable with zero setup.
 *
 * <p>Idle sessions are evictable by age, by count, or both, so a long-running server that
 * accumulates one ledger per user request does not leak memory. Both policies key off the same
 * access-ordered map, so "least recently touched" is the eviction order for count-based
 * trimming, and "not touched within the max age" is the test for age-based trimming.
 *
 * <p>All access is synchronized on the store itself. Contention is limited to the get-or-create
 * lookup, not to recording spend: {@link UsageLedger} is independently thread-safe, so once a
 * caller holds a ledger reference, concurrent recording against it never touches this lock.
 */
public final class InMemorySessionStore implements SessionStore {

    private final LinkedHashMap<String, Entry> sessions;
    private final Duration maxIdleAge;
    private final int maxSessions;
    private final Clock clock;

    private InMemorySessionStore(Duration maxIdleAge, int maxSessions, Clock clock) {
        this.maxIdleAge = maxIdleAge;
        this.maxSessions = maxSessions;
        this.clock = clock;
        // access-order: get() and put() both move an entry to the end, so the front of the
        // map is always the least recently touched session.
        this.sessions = new LinkedHashMap<>(16, 0.75f, true);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static InMemorySessionStore withDefaults() {
        return builder().build();
    }

    @Override
    public synchronized UsageLedger ledgerFor(String sessionId, Supplier<UsageLedger> ledgerFactory) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(ledgerFactory, "ledgerFactory");

        evictIdle();

        Entry entry = sessions.get(sessionId);
        if (entry == null) {
            entry = new Entry(ledgerFactory.get());
            sessions.put(sessionId, entry);
        }
        entry.lastAccessed = clock.instant();

        evictOverCapacity();
        return entry.ledger;
    }

    /**
     * Current number of tracked sessions, exposed for tests rather than as part of the SPI.
     */
    synchronized int size() {
        return sessions.size();
    }

    private void evictIdle() {
        if (maxIdleAge == null) {
            return;
        }
        Instant cutoff = clock.instant().minus(maxIdleAge);
        Iterator<Entry> it = sessions.values().iterator();
        while (it.hasNext()) {
            if (it.next().lastAccessed.isBefore(cutoff)) {
                it.remove();
            } else {
                // access order means later entries are strictly more recent
                break;
            }
        }
    }

    private void evictOverCapacity() {
        Iterator<Entry> it = sessions.values().iterator();
        while (sessions.size() > maxSessions && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private static final class Entry {
        private final UsageLedger ledger;
        private Instant lastAccessed;

        private Entry(UsageLedger ledger) {
            this.ledger = ledger;
        }
    }

    public static final class Builder {
        private Duration maxIdleAge;
        private int maxSessions = Integer.MAX_VALUE;
        private Clock clock = Clock.systemUTC();

        /**
         * Evicts a session once it has gone unaccessed for this long. Unset by default: age-based
         * eviction is off unless configured.
         */
        public Builder maxIdleAge(Duration maxIdleAge) {
            this.maxIdleAge = Objects.requireNonNull(maxIdleAge, "maxIdleAge");
            return this;
        }

        /**
         * Caps the number of tracked sessions, evicting the least recently touched once the cap
         * is exceeded. Unset by default: count-based eviction is off unless configured.
         */
        public Builder maxSessions(int maxSessions) {
            if (maxSessions <= 0) {
                throw new IllegalArgumentException("maxSessions must be positive, got " + maxSessions);
            }
            this.maxSessions = maxSessions;
            return this;
        }

        /**
         * Overrides the clock used to time idle eviction. Defaults to the system clock; tests
         * substitute a fixed or manually-advanced clock to assert eviction deterministically.
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public InMemorySessionStore build() {
            return new InMemorySessionStore(maxIdleAge, maxSessions, clock);
        }
    }
}
