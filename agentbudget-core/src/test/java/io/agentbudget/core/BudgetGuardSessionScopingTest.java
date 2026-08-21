package io.agentbudget.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves a single {@link BudgetGuard} tracks many sessions independently: the caller supplies
 * the session id per call, the guard invents no scoping of its own, and concurrent traffic
 * against one session or across many sessions accumulates correctly.
 */
class BudgetGuardSessionScopingTest {

    private static final String MODEL = "fake-model";

    private BudgetGuard guardWithLimit(String limit) {
        return guardWithLimit(limit, null);
    }

    private BudgetGuard guardWithLimit(String limit, SessionStore store) {
        ModelPricing pricing = ModelPricing.perMillionTokens("USD", 1_000_000, 1_000_000); // $1 per token
        PricingCatalog catalog = StaticPricingCatalog.withSingleModel(MODEL, pricing);
        BudgetGuard.Builder builder = BudgetGuard.builder()
                .limit(Money.of(limit, "USD"))
                .onExceed(ExceedPolicy.STOP)
                .pricingCatalog(catalog);
        if (store != null) {
            builder.sessionStore(store);
        }
        return builder.build();
    }

    @Test
    void twoSessionsAccumulateIndependently() {
        BudgetGuard guard = guardWithLimit("10.00");
        FakeLlmClient clientA = new FakeLlmClient().thenReply("a", 1, 1); // $2
        FakeLlmClient clientB = new FakeLlmClient().thenReply("b", 1, 0).thenReply("b2", 1, 0); // $1 + $1

        guard.wrap("session-a", MODEL, () -> clientA.chat("hi"));
        guard.wrap("session-b", MODEL, () -> clientB.chat("hi"));
        guard.wrap("session-b", MODEL, () -> clientB.chat("hi again"));

        assertThat(guard.spend("session-a")).isEqualTo(Money.of("2.00", "USD"));
        assertThat(guard.spend("session-b")).isEqualTo(Money.of("2.00", "USD"));
    }

    @Test
    void aSubstitutedSessionStoreBehavesIdenticallyToTheDefault() {
        BudgetGuard guard = guardWithLimit("2.00", new FakeSessionStore());
        FakeLlmClient client = new FakeLlmClient()
                .thenReply("hi", 1, 1) // costs $2, exactly hits the limit
                .thenReply("hi again", 1, 0);

        String first = guard.wrap("session-1", MODEL, () -> client.chat("hello"));
        assertThat(first).isEqualTo("hi");
        assertThat(guard.spend("session-1")).isEqualTo(Money.of("2.00", "USD"));

        assertThatThrownBy(() -> guard.wrap("session-1", MODEL, () -> client.chat("hello again")))
                .isInstanceOf(BudgetExceededException.class);
        assertThat(client.callCount()).isEqualTo(1);

        // an unrelated session on the same fake store is untouched
        assertThat(guard.spend("session-2")).isEqualTo(Money.zero(java.util.Currency.getInstance("USD")));
    }

    @Test
    void concurrentCallsOnOneSessionReachTheExactExpectedTotal() throws InterruptedException {
        BudgetGuard guard = guardWithLimit("1000000.00"); // high enough that STOP never trips
        String sessionId = "concurrent-session";
        int threads = 50;
        int callsPerThread = 20;

        runConcurrently(threads, () -> {
            for (int j = 0; j < callsPerThread; j++) {
                guard.wrap(sessionId, MODEL, () -> GuardedResult.of("ok", TokenUsage.of(1, 0))); // $1 each
            }
        });

        Money expected = Money.of((threads * callsPerThread) + ".00", "USD");
        assertThat(guard.spend(sessionId)).isEqualTo(expected);
    }

    @Test
    void concurrentCallsAcrossDifferentSessionsDoNotInterfere() throws InterruptedException {
        BudgetGuard guard = guardWithLimit("1000000.00");
        int sessionCount = 20;
        int callsPerSession = 25;

        ExecutorService pool = Executors.newFixedThreadPool(sessionCount);
        CountDownLatch ready = new CountDownLatch(sessionCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(sessionCount);

        for (int i = 0; i < sessionCount; i++) {
            String sessionId = "session-" + i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int j = 0; j < callsPerSession; j++) {
                        guard.wrap(sessionId, MODEL, () -> GuardedResult.of("ok", TokenUsage.of(1, 0)));
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

        Money expected = Money.of(callsPerSession + ".00", "USD");
        for (int i = 0; i < sessionCount; i++) {
            assertThat(guard.spend("session-" + i)).isEqualTo(expected);
        }
    }

    private void runConcurrently(int threads, Runnable task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run();
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
    }

    /**
     * A minimal SessionStore double: a plain concurrent map with get-or-create and no eviction.
     * Standing in for {@link InMemorySessionStore} proves the guard's session behavior depends
     * only on the {@link SessionStore} contract, not on the default implementation.
     */
    private static final class FakeSessionStore implements SessionStore {
        private final ConcurrentHashMap<String, UsageLedger> ledgers = new ConcurrentHashMap<>();

        @Override
        public UsageLedger ledgerFor(String sessionId, Supplier<UsageLedger> ledgerFactory) {
            return ledgers.computeIfAbsent(sessionId, id -> ledgerFactory.get());
        }
    }
}
