package io.agentbudget.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Observation mode and early warning, driven end to end. $1 per token throughout, so a call of
 * {@code n} tokens costs {@code $n} and every figure below reads off the token counts.
 */
class BudgetGuardWarnAndThresholdTest {

    private static final String MODEL = "fake-model";
    private static final String SESSION = "session-1";

    private final List<BudgetEvent> events = new ArrayList<>();

    private BudgetGuard.Builder guardBuilder() {
        return BudgetGuard.builder()
                .limit(Money.of("10.00", "USD"))
                .pricingCatalog(StaticPricingCatalog.withSingleModel(
                        MODEL, ModelPricing.perMillionTokens("USD", 1_000_000, 1_000_000)))
                .onBudgetEvent(events::add);
    }

    /** A call costing exactly {@code dollars}. */
    private void spend(BudgetGuard guard, int dollars) {
        guard.wrap(SESSION, MODEL, () -> GuardedResult.of("ok", TokenUsage.of(dollars, 0)));
    }

    @Nested
    class TheWarnPolicy {

        @Test
        void logsTheBreachAndLetsTheCallProceed() {
            BudgetGuard guard = guardBuilder().onExceed(ExceedPolicy.WARN).build();
            spend(guard, 10); // exactly at the limit

            // STOP would refuse here; WARN lets it through
            assertThatCode(() -> spend(guard, 5)).doesNotThrowAnyException();
            assertThat(guard.spend(SESSION)).isEqualTo(Money.of("15.00", "USD"));
        }

        @Test
        void keepsAccountingAccuratelyPastTheLimit() {
            BudgetGuard guard = guardBuilder().onExceed(ExceedPolicy.WARN).build();
            for (int i = 0; i < 5; i++) {
                spend(guard, 4);
            }

            assertThat(guard.spend(SESSION)).isEqualTo(Money.of("20.00", "USD"));
            assertThat(guard.remaining(SESSION)).isEqualTo(Money.zero(guard.limit().currency()));
            assertThat(guard.snapshot(SESSION).calls()).hasSize(5);
        }

        @Test
        void stopIsStillTheDefault() {
            BudgetGuard guard = guardBuilder().build();
            spend(guard, 10);

            assertThatThrownBy(() -> spend(guard, 1)).isInstanceOf(BudgetExceededException.class);
        }
    }

    @Nested
    class TheWarningThreshold {

        @Test
        void firesAsAFractionOfTheLimit() {
            BudgetGuard guard = guardBuilder().warnAt(BudgetThreshold.ofFraction(0.8)).build();
            assertThat(guard.warningThreshold()).isEqualTo(Money.of("8.00", "USD"));

            spend(guard, 7);
            assertThat(events).isEmpty();

            spend(guard, 1); // now at $8.00 exactly
            assertThat(events).hasSize(1);
            assertThat(events.get(0).boundary()).isEqualTo(BudgetBoundary.WARNING_THRESHOLD);
        }

        @Test
        void firesAsAnAbsoluteAmount() {
            BudgetGuard guard = guardBuilder().warnAt(BudgetThreshold.ofAmount(Money.of("6.00", "USD"))).build();
            assertThat(guard.warningThreshold()).isEqualTo(Money.of("6.00", "USD"));

            spend(guard, 5);
            assertThat(events).isEmpty();
            spend(guard, 2); // $7.00, past the $6.00 line
            assertThat(events).extracting(BudgetEvent::boundary)
                    .containsExactly(BudgetBoundary.WARNING_THRESHOLD);
        }

        @Test
        void firesExactlyOncePerCrossingRatherThanOnEverySubsequentCall() {
            BudgetGuard guard = guardBuilder()
                    .onExceed(ExceedPolicy.WARN)
                    .warnAt(BudgetThreshold.ofFraction(0.8))
                    .build();

            spend(guard, 9); // crosses $8.00
            for (int i = 0; i < 10; i++) {
                spend(guard, 0); // zero-cost calls change nothing
            }
            assertThat(events).extracting(BudgetEvent::boundary)
                    .containsExactly(BudgetBoundary.WARNING_THRESHOLD);

            spend(guard, 5); // crosses $10.00
            assertThat(events).extracting(BudgetEvent::boundary)
                    .containsExactly(BudgetBoundary.WARNING_THRESHOLD, BudgetBoundary.LIMIT);

            // still spending, but there is nothing left to cross
            for (int i = 0; i < 10; i++) {
                spend(guard, 3);
            }
            assertThat(events).hasSize(2);
        }

        @Test
        void reportsBothBoundariesWhenOneCallVaultsPastThemTogether() {
            BudgetGuard guard = guardBuilder().warnAt(BudgetThreshold.ofFraction(0.8)).build();

            spend(guard, 50); // straight past $8.00 and $10.00 in one call

            assertThat(events).extracting(BudgetEvent::boundary)
                    .containsExactly(BudgetBoundary.WARNING_THRESHOLD, BudgetBoundary.LIMIT);
        }

        @Test
        void firesTheLimitBoundaryEvenWithNoThresholdConfigured() {
            BudgetGuard guard = guardBuilder().build();
            assertThat(guard.warningThreshold()).isNull();

            spend(guard, 12);

            assertThat(events).extracting(BudgetEvent::boundary).containsExactly(BudgetBoundary.LIMIT);
        }

        @Test
        void firesAgainAfterASessionIsReset() {
            BudgetGuard guard = guardBuilder().warnAt(BudgetThreshold.ofFraction(0.8)).build();
            spend(guard, 9);
            assertThat(events).hasSize(1);

            guard.resetSession(SESSION);
            spend(guard, 9); // a fresh budget crosses the same line again

            assertThat(events).hasSize(2);
        }

        @Test
        void rejectsAThresholdAboveTheLimit() {
            assertThatThrownBy(() -> guardBuilder()
                    .warnAt(BudgetThreshold.ofAmount(Money.of("20.00", "USD"))).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("above the limit");
        }

        @Test
        void rejectsANonsensicalFraction() {
            assertThatThrownBy(() -> BudgetThreshold.ofFraction(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("above 0");
            assertThatThrownBy(() -> BudgetThreshold.ofFraction(1.5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at most 1");
        }

        @Test
        void rejectsAThresholdInADifferentCurrencyToTheLimit() {
            assertThatThrownBy(() -> guardBuilder()
                    .warnAt(BudgetThreshold.ofAmount(Money.of("5.00", "EUR"))).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not match");
        }
    }

    @Nested
    class TheCallback {

        @Test
        void receivesTheSpendSnapshotTheLimitAndTheBoundaryCrossed() {
            BudgetGuard guard = guardBuilder().warnAt(BudgetThreshold.ofFraction(0.8)).build();
            spend(guard, 9);

            BudgetEvent event = events.get(0);
            assertThat(event.sessionId()).isEqualTo(SESSION);
            assertThat(event.boundary()).isEqualTo(BudgetBoundary.WARNING_THRESHOLD);
            assertThat(event.amount()).isEqualTo(Money.of("8.00", "USD"));
            assertThat(event.limit()).isEqualTo(Money.of("10.00", "USD"));
            assertThat(event.currentSpend()).isEqualTo(Money.of("9.00", "USD"));
            assertThat(event.snapshot().calls()).hasSize(1);
            assertThat(event.snapshot().spentOn(MODEL)).isEqualTo(Money.of("9.00", "USD"));
        }

        @Test
        void seesASnapshotThatDoesNotChangeAfterwards() {
            BudgetGuard guard = guardBuilder().warnAt(BudgetThreshold.ofFraction(0.8)).build();
            spend(guard, 9);
            SpendSnapshot atCrossing = events.get(0).snapshot();

            spend(guard, 3);

            assertThat(atCrossing.total()).isEqualTo(Money.of("9.00", "USD"));
            assertThat(atCrossing.calls()).hasSize(1);
        }

        @Test
        void aCallbackThatThrowsDoesNotBreakTheCallInFlight() {
            AtomicInteger invocations = new AtomicInteger();
            BudgetGuard guard = guardBuilder()
                    .warnAt(BudgetThreshold.ofFraction(0.8))
                    .onBudgetEvent(event -> {
                        invocations.incrementAndGet();
                        throw new IllegalStateException("the metrics backend is down");
                    })
                    .build();

            assertThatCode(() -> spend(guard, 12)).doesNotThrowAnyException();

            // both boundaries were still attempted, and the spend was still recorded
            assertThat(invocations).hasValue(2);
            assertThat(guard.spend(SESSION)).isEqualTo(Money.of("12.00", "USD"));
        }

        @Test
        void aCallbackThatThrowsDoesNotStopTheCallersResultComingBack() {
            BudgetGuard guard = guardBuilder()
                    .onBudgetEvent(event -> {
                        throw new IllegalStateException("boom");
                    })
                    .build();

            String result = guard.wrap(SESSION, MODEL,
                    () -> GuardedResult.of("the answer", TokenUsage.of(12, 0)));

            assertThat(result).isEqualTo("the answer");
        }

        @Test
        void isNotFiredForADuplicateCallIdThatChangedNothing() {
            BudgetGuard guard = guardBuilder().warnAt(BudgetThreshold.ofFraction(0.8)).build();
            CallId callId = CallId.of("attempt-1");

            guard.wrap(SESSION, MODEL, callId, () -> GuardedResult.of("ok", TokenUsage.of(9, 0)));
            guard.wrap(SESSION, MODEL, callId, () -> GuardedResult.of("ok", TokenUsage.of(9, 0)));

            assertThat(events).hasSize(1);
        }

        @Test
        void firesForStreamingCallsToo() {
            BudgetGuard guard = guardBuilder().warnAt(BudgetThreshold.ofFraction(0.8)).build();
            ChunkInspector<FakeStreamingLlmClient.StreamChunk> inspector =
                    ChunkInspector.of(FakeStreamingLlmClient.StreamChunk::textDelta,
                            FakeStreamingLlmClient.StreamChunk::usageFrame);
            FakeStreamingLlmClient client = new FakeStreamingLlmClient()
                    .thenText("hello")
                    .thenUsageFrame(9, 0);

            try (StreamSession<FakeStreamingLlmClient.StreamChunk> session =
                         guard.openStream(SESSION, MODEL, inspector)) {
                for (FakeStreamingLlmClient.StreamChunk chunk : client.stream("go")) {
                    session.observe(chunk);
                }
            }

            assertThat(events).extracting(BudgetEvent::boundary)
                    .containsExactly(BudgetBoundary.WARNING_THRESHOLD);
        }

        @Test
        void tracksEachSessionsCrossingsIndependently() {
            BudgetGuard guard = guardBuilder().warnAt(BudgetThreshold.ofFraction(0.8)).build();

            guard.wrap("a", MODEL, () -> GuardedResult.of("ok", TokenUsage.of(9, 0)));
            guard.wrap("b", MODEL, () -> GuardedResult.of("ok", TokenUsage.of(9, 0)));

            assertThat(events).extracting(BudgetEvent::sessionId).containsExactly("a", "b");
        }
    }
}
