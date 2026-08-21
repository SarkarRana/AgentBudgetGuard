package io.agentbudget.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the breaker is wired into the guard and fires on its own terms — a session nowhere near
 * its spend limit is still stopped when it loops.
 */
class BudgetGuardCircuitBreakerTest {

    private static final String MODEL = "fake-model";
    private static final String SESSION = "session-1";

    private final MutableClock clock = MutableClock.startingAtEpoch();

    /** A generous $1000 limit against a model costing a millionth of a cent per token. */
    private BudgetGuard guardWithBreaker(CallRateBreaker breaker) {
        return BudgetGuard.builder()
                .limit(Money.of("1000.00", "USD"))
                .pricingCatalog(StaticPricingCatalog.withSingleModel(
                        MODEL, ModelPricing.perMillionTokens("USD", 1, 1)))
                .callRateBreaker(breaker)
                .build();
    }

    private CallRateBreaker breaker() {
        return CallRateBreaker.builder()
                .maxCalls(3)
                .window(Duration.ofSeconds(1))
                .coolOff(Duration.ofSeconds(30))
                .clock(clock)
                .build();
    }

    @Test
    void tripsOnASessionNowhereNearItsSpendLimit() {
        BudgetGuard guard = guardWithBreaker(breaker());
        FakeLlmClient client = new FakeLlmClient()
                .thenReply("a", 1, 1)
                .thenReply("b", 1, 1)
                .thenReply("c", 1, 1)
                .thenReply("never dispatched", 1, 1);

        for (int i = 0; i < 3; i++) {
            guard.wrap(SESSION, MODEL, () -> client.chat("loop"));
        }

        assertThatThrownBy(() -> guard.wrap(SESSION, MODEL, () -> client.chat("loop")))
                .isInstanceOf(CallRateExceededException.class)
                .isNotInstanceOf(BudgetExceededException.class);

        // the fourth call never reached the client
        assertThat(client.callCount()).isEqualTo(3);
        // and the session has spent essentially nothing against its $1000 limit
        assertThat(guard.spend(SESSION)).isLessThan(Money.of("0.01", "USD"));
    }

    @Test
    void aBudgetRefusalDoesNotCountTowardsTheRate() {
        // limit of $2, and each call costs exactly $2, so the second call is refused on budget
        BudgetGuard guard = BudgetGuard.builder()
                .limit(Money.of("2.00", "USD"))
                .pricingCatalog(StaticPricingCatalog.withSingleModel(
                        MODEL, ModelPricing.perMillionTokens("USD", 1_000_000, 1_000_000)))
                .callRateBreaker(breaker())
                .build();
        FakeLlmClient client = new FakeLlmClient().thenReply("a", 1, 1);

        guard.wrap(SESSION, MODEL, () -> client.chat("first"));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> guard.wrap(SESSION, MODEL, () -> client.chat("over budget")))
                    .isInstanceOf(BudgetExceededException.class);
        }

        // five refused calls never went out, so only the one dispatched call is counted
        assertThat(guard.breakerStatus(SESSION).callsInWindow()).isEqualTo(1);
    }

    @Test
    void recoversAfterTheCoolOffWithoutARestart() {
        BudgetGuard guard = guardWithBreaker(breaker());
        FakeLlmClient client = new FakeLlmClient()
                .thenReply("a", 1, 1)
                .thenReply("b", 1, 1)
                .thenReply("c", 1, 1)
                .thenReply("after cool-off", 1, 1);

        for (int i = 0; i < 3; i++) {
            guard.wrap(SESSION, MODEL, () -> client.chat("loop"));
        }
        assertThatThrownBy(() -> guard.wrap(SESSION, MODEL, () -> client.chat("loop")))
                .isInstanceOf(CallRateExceededException.class);

        clock.advance(Duration.ofSeconds(30));

        assertThatCode(() -> guard.wrap(SESSION, MODEL, () -> client.chat("loop")))
                .doesNotThrowAnyException();
    }

    @Test
    void guardsAStreamingCallOnTheSameRate() {
        BudgetGuard guard = guardWithBreaker(breaker());
        ChunkInspector<FakeStreamingLlmClient.StreamChunk> inspector =
                ChunkInspector.of(FakeStreamingLlmClient.StreamChunk::textDelta,
                        FakeStreamingLlmClient.StreamChunk::usageFrame);

        for (int i = 0; i < 3; i++) {
            guard.openStream(SESSION, MODEL, inspector).close();
        }

        assertThatThrownBy(() -> guard.openStream(SESSION, MODEL, inspector))
                .isInstanceOf(CallRateExceededException.class);
    }

    @Test
    void breakerStateIsReadableThroughTheGuardForAHealthEndpoint() {
        BudgetGuard guard = guardWithBreaker(breaker());
        FakeLlmClient client = new FakeLlmClient().thenReply("a", 1, 1);

        assertThat(guard.breakerStatus(SESSION).state()).isEqualTo(BreakerState.CLOSED);
        guard.wrap(SESSION, MODEL, () -> client.chat("one"));
        assertThat(guard.breakerStatus(SESSION).callsInWindow()).isEqualTo(1);
    }

    @Test
    void aGuardWithoutABreakerSaysSoRatherThanPretendingItIsClosed() {
        BudgetGuard guard = BudgetGuard.builder()
                .limit(Money.of("10.00", "USD"))
                .pricingCatalog(StaticPricingCatalog.withSingleModel(
                        MODEL, ModelPricing.perMillionTokens("USD", 1, 1)))
                .build();

        assertThatThrownBy(() -> guard.breakerStatus(SESSION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No call-rate breaker");
    }
}
