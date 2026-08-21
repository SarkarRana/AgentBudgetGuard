package io.agentbudget.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Makes the ledger legible: what a session spent, on which model, on which call, and what is
 * left. Slice 12's acceptance is a realistic multi-call, multi-model sequence reporting
 * correctly, so that is what most of this drives.
 */
class BudgetGuardIntrospectionTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final String SESSION = "session-1";

    /** planner costs $2/token, worker $1/token, so every figure below reads off the token counts. */
    private BudgetGuard guard() {
        PricingCatalog catalog = StaticPricingCatalog.builder()
                .register("planner", ModelPricing.perMillionTokens("USD", 2_000_000, 2_000_000))
                .register("worker", ModelPricing.perMillionTokens("USD", 1_000_000, 1_000_000))
                .build();
        return BudgetGuard.builder()
                .limit(Money.of("100.00", "USD"))
                .pricingCatalog(catalog)
                .build();
    }

    /** One planner call and three worker calls, the shape of a real agent run. */
    private BudgetGuard anAgentRun() {
        BudgetGuard guard = guard();
        guard.wrap(SESSION, "planner", CallId.of("plan"),
                () -> GuardedResult.of("a plan", TokenUsage.of(3, 2)));     // 5 tokens @ $2 = $10
        for (int step = 1; step <= 3; step++) {
            guard.wrap(SESSION, "worker", CallId.of("step-" + step),
                    () -> GuardedResult.of("done", TokenUsage.of(2, 2)));   // 4 tokens @ $1 = $4
        }
        return guard;
    }

    @Test
    void reportsCurrentSpendAndRemainingBudgetAtAnyTime() {
        BudgetGuard guard = anAgentRun();
        SpendSnapshot snapshot = guard.snapshot(SESSION);

        assertThat(snapshot.sessionId()).isEqualTo(SESSION);
        assertThat(snapshot.total()).isEqualTo(Money.of("22.00", "USD")); // 10 + 4 + 4 + 4
        assertThat(snapshot.limit()).isEqualTo(Money.of("100.00", "USD"));
        assertThat(snapshot.remaining()).isEqualTo(Money.of("78.00", "USD"));
        assertThat(snapshot.total()).isEqualTo(guard.spend(SESSION));
        assertThat(snapshot.remaining()).isEqualTo(guard.remaining(SESSION));
    }

    @Test
    void breaksSpendDownPerModelSoTheExpensiveStepIsVisible() {
        SpendSnapshot snapshot = anAgentRun().snapshot(SESSION);

        assertThat(snapshot.perModel()).containsOnlyKeys("planner", "worker");
        assertThat(snapshot.spentOn("planner")).isEqualTo(Money.of("10.00", "USD"));
        assertThat(snapshot.spentOn("worker")).isEqualTo(Money.of("12.00", "USD"));
        assertThat(snapshot.spentOn("never-ran")).isEqualTo(Money.zero(USD));
    }

    @Test
    void breaksSpendDownPerCallWithModelTokensAndCost() {
        SpendSnapshot snapshot = anAgentRun().snapshot(SESSION);

        assertThat(snapshot.calls()).hasSize(4);
        CallRecord first = snapshot.calls().get(0);
        assertThat(first.callId()).isEqualTo(CallId.of("plan"));
        assertThat(first.model()).isEqualTo("planner");
        assertThat(first.usage()).isEqualTo(TokenUsage.of(3, 2));
        assertThat(first.cost()).isEqualTo(Money.of("10.00", "USD"));

        assertThat(snapshot.calls()).extracting(CallRecord::model)
                .containsExactly("planner", "worker", "worker", "worker");
        assertThat(snapshot.calls()).extracting(CallRecord::callId)
                .containsExactly(CallId.of("plan"), CallId.of("step-1"),
                        CallId.of("step-2"), CallId.of("step-3"));
    }

    @Test
    void everyAmountCarriesAnExplicitCurrency() {
        SpendSnapshot snapshot = anAgentRun().snapshot(SESSION);

        assertThat(snapshot.currency()).isEqualTo(USD);
        assertThat(snapshot.total().currency()).isEqualTo(USD);
        assertThat(snapshot.remaining().currency()).isEqualTo(USD);
        assertThat(snapshot.perModel().values()).allSatisfy(m -> assertThat(m.currency()).isEqualTo(USD));
        assertThat(snapshot.calls()).allSatisfy(c -> assertThat(c.cost().currency()).isEqualTo(USD));
    }

    @Test
    void reportsHowMuchOfTheLimitIsUsed() {
        assertThat(anAgentRun().snapshot(SESSION).fractionUsed()).isEqualTo(0.22d);
    }

    @Nested
    class TheSnapshot {

        @Test
        void isImmutableAndSafeToHandToAnotherThread() {
            BudgetGuard guard = anAgentRun();
            SpendSnapshot snapshot = guard.snapshot(SESSION);

            assertThatCode(() -> snapshot.perModel().put("injected", Money.zero(USD)))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatCode(() -> snapshot.calls().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void doesNotChangeUnderneathItsHolderAsTheSessionKeepsSpending() {
            BudgetGuard guard = anAgentRun();
            SpendSnapshot taken = guard.snapshot(SESSION);

            guard.wrap(SESSION, "worker", () -> GuardedResult.of("more", TokenUsage.of(5, 5)));

            assertThat(taken.total()).isEqualTo(Money.of("22.00", "USD"));
            assertThat(taken.calls()).hasSize(4);
            assertThat(guard.snapshot(SESSION).total()).isEqualTo(Money.of("32.00", "USD"));
        }
    }

    @Nested
    class Rounding {

        @Test
        void accumulatedTotalsAreNeverPreRounded() {
            // three calls of a third of a cent each: rounding per call would report $0.00 or
            // $0.03, and the truth is a hair under a cent
            PricingCatalog catalog = StaticPricingCatalog.withSingleModel(
                    "micro", ModelPricing.perMillionTokens("USD", 1_000, 1_000));
            BudgetGuard guard = BudgetGuard.builder()
                    .limit(Money.of("1.00", "USD"))
                    .pricingCatalog(catalog)
                    .build();

            for (int i = 0; i < 3; i++) {
                guard.wrap(SESSION, "micro", CallId.of("call-" + i),
                        () -> GuardedResult.of("x", TokenUsage.of(3, 0))); // 3 tokens @ $0.001
            }

            SpendSnapshot snapshot = guard.snapshot(SESSION);
            assertThat(snapshot.total().amount().doubleValue()).isEqualTo(0.009d);
            assertThat(snapshot.total().roundedForDisplay()).isEqualTo(Money.of("0.01", "USD"));
        }

        @Test
        void roundsToTheCurrencysUsualPlacesOnlyWhenAsked() {
            assertThat(Money.of("1.005", "USD").roundedForDisplay()).isEqualTo(Money.of("1.01", "USD"));
            assertThat(Money.of("1.004", "USD").roundedForDisplay()).isEqualTo(Money.of("1.00", "USD"));
            // JPY has no minor unit
            assertThat(Money.of("1234.56", "JPY").roundedForDisplay())
                    .isEqualTo(Money.of("1235", "JPY"));
        }
    }

    @Nested
    class Resetting {

        @Test
        void clearsTheSessionsLedgerWithoutDisturbingOthers() {
            BudgetGuard guard = guard();
            guard.wrap("session-a", "worker", () -> GuardedResult.of("a", TokenUsage.of(2, 2)));
            guard.wrap("session-b", "worker", () -> GuardedResult.of("b", TokenUsage.of(2, 2)));

            guard.resetSession("session-a");

            assertThat(guard.spend("session-a")).isEqualTo(Money.zero(USD));
            assertThat(guard.snapshot("session-a").calls()).isEmpty();
            assertThat(guard.snapshot("session-a").perModel()).isEmpty();
            assertThat(guard.remaining("session-a")).isEqualTo(Money.of("100.00", "USD"));

            assertThat(guard.spend("session-b")).isEqualTo(Money.of("4.00", "USD"));
        }

        @Test
        void lettsALongLivedProcessStartAFreshBudgetUnderTheSameId() {
            BudgetGuard guard = guard();
            for (int request = 0; request < 3; request++) {
                guard.resetSession(SESSION);
                guard.wrap(SESSION, "worker", () -> GuardedResult.of("work", TokenUsage.of(2, 2)));
                assertThat(guard.spend(SESSION)).isEqualTo(Money.of("4.00", "USD"));
            }
        }

        @Test
        void aSessionThatNeverRanReportsAnEmptySnapshot() {
            SpendSnapshot snapshot = guard().snapshot("never-seen");

            assertThat(snapshot.total()).isEqualTo(Money.zero(USD));
            assertThat(snapshot.remaining()).isEqualTo(Money.of("100.00", "USD"));
            assertThat(snapshot.calls()).isEmpty();
            assertThat(snapshot.perModel()).isEmpty();
            assertThat(snapshot.fractionUsed()).isZero();
        }
    }

    @Test
    void aSnapshotOfAnOverspentSessionFloorsRemainingAtZero() {
        BudgetGuard guard = BudgetGuard.builder()
                .limit(Money.of("5.00", "USD"))
                .pricingCatalog(StaticPricingCatalog.withSingleModel(
                        "worker", ModelPricing.perMillionTokens("USD", 1_000_000, 1_000_000)))
                .build();
        // one call blows straight past the limit
        guard.wrap(SESSION, "worker", () -> GuardedResult.of("big", TokenUsage.of(10, 10)));

        SpendSnapshot snapshot = guard.snapshot(SESSION);
        assertThat(snapshot.total()).isEqualTo(Money.of("20.00", "USD"));
        assertThat(snapshot.remaining()).isEqualTo(Money.zero(USD));
        assertThat(snapshot.fractionUsed()).isEqualTo(4.0d);
    }

    @Test
    void streamingCallsAppearInTheBreakdownAlongsideWrappedOnes() {
        BudgetGuard guard = guard();
        guard.wrap(SESSION, "planner", () -> GuardedResult.of("plan", TokenUsage.of(1, 1))); // $4

        ChunkInspector<FakeStreamingLlmClient.StreamChunk> inspector =
                ChunkInspector.of(FakeStreamingLlmClient.StreamChunk::textDelta,
                        FakeStreamingLlmClient.StreamChunk::usageFrame);
        FakeStreamingLlmClient client = new FakeStreamingLlmClient()
                .thenText("streamed")
                .thenUsageFrame(2, 2); // 4 tokens @ $1 = $4
        try (StreamSession<FakeStreamingLlmClient.StreamChunk> session =
                     guard.openStream(SESSION, "worker", inspector)) {
            for (FakeStreamingLlmClient.StreamChunk chunk : client.stream("go")) {
                session.observe(chunk);
            }
        }

        SpendSnapshot snapshot = guard.snapshot(SESSION);
        assertThat(snapshot.total()).isEqualTo(Money.of("8.00", "USD"));
        assertThat(snapshot.calls()).extracting(CallRecord::model).containsExactly("planner", "worker");
        assertThat(List.copyOf(snapshot.perModel().keySet())).containsExactly("planner", "worker");
    }
}
