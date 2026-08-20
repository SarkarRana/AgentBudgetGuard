package io.agentbudget.core;

import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class UsageLedgerTest {

    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void startsAtZero() {
        UsageLedger ledger = new UsageLedger(USD);
        assertThat(ledger.total()).isEqualTo(Money.zero(USD));
    }

    @Test
    void accumulatesAcrossCalls() {
        UsageLedger ledger = new UsageLedger(USD);
        ledger.record(Money.of("1.50", "USD"));
        ledger.record(Money.of("2.25", "USD"));
        assertThat(ledger.total()).isEqualTo(Money.of("3.75", "USD"));
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
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int j = 0; j < recordsPerThread; j++) {
                        ledger.record(perRecord);
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
}
