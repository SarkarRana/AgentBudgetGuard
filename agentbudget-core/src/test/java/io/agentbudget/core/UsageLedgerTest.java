package io.agentbudget.core;

import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsageLedgerTest {

    private static final Currency USD = Currency.getInstance("USD");

    /** A recorded call at {@code cost}; the model and usage matter only to the breakdown tests. */
    private static CallRecord call(String callId, String cost) {
        return new CallRecord(CallId.of(callId), "fake-model", TokenUsage.of(1, 1), Money.of(cost, "USD"));
    }

    private static CallRecord call(CallId callId, String cost) {
        return new CallRecord(callId, "fake-model", TokenUsage.of(1, 1), Money.of(cost, "USD"));
    }

    @Test
    void startsAtZero() {
        UsageLedger ledger = new UsageLedger(USD);
        assertThat(ledger.total()).isEqualTo(Money.zero(USD));
    }

    @Test
    void accumulatesAcrossCalls() {
        UsageLedger ledger = new UsageLedger(USD);
        ledger.record(call("call-1", "1.50"));
        ledger.record(call("call-2", "2.25"));
        assertThat(ledger.total()).isEqualTo(Money.of("3.75", "USD"));
    }

    @Test
    void recordingTheSameCallIdTwiceChargesOnce() {
        UsageLedger ledger = new UsageLedger(USD);
        CallId callId = CallId.of("attempt-1");

        assertThat(ledger.record(call(callId, "1.50")).newTotal()).isEqualTo(Money.of("1.50", "USD"));
        assertThat(ledger.record(call(callId, "1.50")).newTotal()).isEqualTo(Money.of("1.50", "USD"));

        assertThat(ledger.total()).isEqualTo(Money.of("1.50", "USD"));
    }

    @Test
    void aDuplicateCallIdIsIgnoredEvenWhenItCarriesDifferentUsage() {
        UsageLedger ledger = new UsageLedger(USD);
        CallId callId = CallId.of("attempt-1");

        ledger.record(call(callId, "1.50"));
        ledger.record(call(callId, "9.99"));

        // the first record wins; the second neither replaces it nor adds to it
        assertThat(ledger.total()).isEqualTo(Money.of("1.50", "USD"));
        assertThat(ledger.recordOf(callId).cost()).isEqualTo(Money.of("1.50", "USD"));
    }

    @Test
    void distinctCallIdsEachRecordSeparatelyEvenAtTheSameCost() {
        UsageLedger ledger = new UsageLedger(USD);

        ledger.record(call("attempt-1", "2.00"));
        ledger.record(call("attempt-2", "2.00"));
        ledger.record(call("attempt-3", "2.00"));

        assertThat(ledger.total()).isEqualTo(Money.of("6.00", "USD"));
    }

    @Test
    void aZeroCostRecordsNothingAndLeavesTheIdUnclaimed() {
        UsageLedger ledger = new UsageLedger(USD);
        CallId callId = CallId.of("attempt-1");

        // an attempt that never reached the provider
        ledger.record(new CallRecord(callId, "fake-model", TokenUsage.ZERO, Money.zero(USD)));
        assertThat(ledger.total()).isEqualTo(Money.zero(USD));
        assertThat(ledger.hasRecorded(callId)).isFalse();

        // the retry under the same id is still charged
        ledger.record(call(callId, "2.00"));
        assertThat(ledger.total()).isEqualTo(Money.of("2.00", "USD"));
        assertThat(ledger.hasRecorded(callId)).isTrue();
    }

    @Test
    void remembersOnlyTheMostRecentCallIdsWithinTheConfiguredWindow() {
        UsageLedger ledger = new UsageLedger(USD, 2);

        ledger.record(call("a", "1.00"));
        ledger.record(call("b", "1.00"));
        ledger.record(call("c", "1.00")); // evicts "a"

        assertThat(ledger.hasRecorded(CallId.of("a"))).isFalse();
        assertThat(ledger.hasRecorded(CallId.of("b"))).isTrue();
        assertThat(ledger.hasRecorded(CallId.of("c"))).isTrue();

        // ids still inside the window stay idempotent
        ledger.record(call("c", "1.00"));
        assertThat(ledger.total()).isEqualTo(Money.of("3.00", "USD"));
    }

    @Test
    void rejectsANonPositiveHistoryWindow() {
        assertThatThrownBy(() -> new UsageLedger(USD, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void reachesExactTotalUnderConcurrentRecording() throws InterruptedException {
        UsageLedger ledger = new UsageLedger(USD);
        int threads = 50;
        int recordsPerThread = 100;
        Money perRecord = Money.of("0.01", "USD");

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            int thread = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int j = 0; j < recordsPerThread; j++) {
                        ledger.record(call("thread-" + thread + "-call-" + j, "0.01"));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        Money expected = perRecord.multiply(java.math.BigDecimal.valueOf((long) threads * recordsPerThread));
        assertThat(ledger.total()).isEqualTo(expected);
    }

    @Test
    void concurrentRecordingOfOneCallIdChargesExactlyOnce() throws InterruptedException {
        UsageLedger ledger = new UsageLedger(USD);
        CallId contested = CallId.of("attempt-1");
        Money cost = Money.of("0.01", "USD");
        int threads = 50;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        Queue<Money> observedTotals = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    observedTotals.add(ledger.record(call(contested, "0.01")).newTotal());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(ledger.total()).isEqualTo(cost);
        // the loser of the race never sees a total that is missing the winner's record
        assertThat(observedTotals).hasSize(threads).allMatch(cost::equals);
    }
}
