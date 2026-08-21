package io.agentbudget.core;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySessionStoreTest {

    private static final Currency USD = Currency.getInstance("USD");

    private static Supplier<UsageLedger> newLedger() {
        return () -> new UsageLedger(USD);
    }

    @Test
    void getOrCreateReturnsTheSameLedgerForTheSameSession() {
        InMemorySessionStore store = InMemorySessionStore.withDefaults();

        UsageLedger first = store.ledgerFor("session-1", newLedger());
        first.record(new CallRecord(CallId.of("call-1"), "fake-model", TokenUsage.of(1, 1), Money.of("1.00", "USD")));
        UsageLedger second = store.ledgerFor("session-1", newLedger());

        assertThat(second).isSameAs(first);
        assertThat(second.total()).isEqualTo(Money.of("1.00", "USD"));
    }

    @Test
    void differentSessionsGetIndependentLedgers() {
        InMemorySessionStore store = InMemorySessionStore.withDefaults();

        UsageLedger a = store.ledgerFor("session-a", newLedger());
        UsageLedger b = store.ledgerFor("session-b", newLedger());
        a.record(new CallRecord(CallId.of("call-2"), "fake-model", TokenUsage.of(1, 1), Money.of("5.00", "USD")));

        assertThat(a.total()).isEqualTo(Money.of("5.00", "USD"));
        assertThat(b.total()).isEqualTo(Money.zero(USD));
    }

    @Test
    void idleSessionIsEvictedAfterMaxIdleAgeElapses() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemorySessionStore store = InMemorySessionStore.builder()
                .maxIdleAge(Duration.ofMinutes(30))
                .clock(clock)
                .build();

        UsageLedger original = store.ledgerFor("session-1", newLedger());
        original.record(new CallRecord(CallId.of("call-3"), "fake-model", TokenUsage.of(1, 1), Money.of("1.00", "USD")));

        clock.advance(Duration.ofMinutes(31));

        UsageLedger afterEviction = store.ledgerFor("session-1", newLedger());

        assertThat(afterEviction).isNotSameAs(original);
        assertThat(afterEviction.total()).isEqualTo(Money.zero(USD));
    }

    @Test
    void sessionWithinMaxIdleAgeIsNotEvicted() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemorySessionStore store = InMemorySessionStore.builder()
                .maxIdleAge(Duration.ofMinutes(30))
                .clock(clock)
                .build();

        UsageLedger original = store.ledgerFor("session-1", newLedger());
        original.record(new CallRecord(CallId.of("call-4"), "fake-model", TokenUsage.of(1, 1), Money.of("1.00", "USD")));

        clock.advance(Duration.ofMinutes(10));

        UsageLedger stillThere = store.ledgerFor("session-1", newLedger());

        assertThat(stillThere).isSameAs(original);
        assertThat(stillThere.total()).isEqualTo(Money.of("1.00", "USD"));
    }

    @Test
    void leastRecentlyTouchedSessionIsEvictedWhenMaxSessionsIsExceeded() {
        InMemorySessionStore store = InMemorySessionStore.builder()
                .maxSessions(2)
                .build();

        UsageLedger session1 = store.ledgerFor("session-1", newLedger());
        session1.record(new CallRecord(CallId.of("call-5"), "fake-model", TokenUsage.of(1, 1), Money.of("1.00", "USD")));
        store.ledgerFor("session-2", newLedger());

        // session-3 pushes the store over capacity; session-1 is the least recently touched
        store.ledgerFor("session-3", newLedger());

        assertThat(store.size()).isEqualTo(2);
        UsageLedger session1Again = store.ledgerFor("session-1", newLedger());
        assertThat(session1Again).isNotSameAs(session1);
        assertThat(session1Again.total()).isEqualTo(Money.zero(USD));
    }

    @Test
    void touchingASessionProtectsItFromCountEviction() {
        InMemorySessionStore store = InMemorySessionStore.builder()
                .maxSessions(2)
                .build();

        UsageLedger session1 = store.ledgerFor("session-1", newLedger());
        session1.record(new CallRecord(CallId.of("call-6"), "fake-model", TokenUsage.of(1, 1), Money.of("1.00", "USD")));
        store.ledgerFor("session-2", newLedger());

        // touch session-1 again so session-2 becomes the least recently touched
        store.ledgerFor("session-1", newLedger());
        store.ledgerFor("session-3", newLedger());

        UsageLedger session1Again = store.ledgerFor("session-1", newLedger());
        assertThat(session1Again).isSameAs(session1);
        assertThat(session1Again.total()).isEqualTo(Money.of("1.00", "USD"));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
