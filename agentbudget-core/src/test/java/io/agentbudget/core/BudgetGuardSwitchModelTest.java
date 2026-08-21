package io.agentbudget.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Degrading to a cheaper model rather than dying. "expensive" costs $2/token and "cheap"
 * $1/token, so which model a call actually went to is visible in both the recorded model and the
 * amount charged.
 */
class BudgetGuardSwitchModelTest {

    private static final String EXPENSIVE = "expensive";
    private static final String CHEAP = "cheap";
    private static final String SESSION = "session-1";

    private static final PricingCatalog CATALOG = StaticPricingCatalog.builder()
            .register(EXPENSIVE, ModelPricing.perMillionTokens("USD", 2_000_000, 2_000_000))
            .register(CHEAP, ModelPricing.perMillionTokens("USD", 1_000_000, 1_000_000))
            .build();

    /** Switches to the cheap model past $10, and stops altogether past $20. */
    private BudgetGuard.Builder guardBuilder() {
        return BudgetGuard.builder()
                .limit(Money.of("10.00", "USD"))
                .pricingCatalog(CATALOG)
                .onExceed(ExceedPolicy.SWITCH_MODEL)
                .fallbackModel(CHEAP)
                .hardLimit(Money.of("20.00", "USD"));
    }

    /** Records which model each call was actually sent to. */
    private static final class RecordingClient {
        private final List<String> modelsCalled = new ArrayList<>();

        GuardedResult<String> chat(String model, long tokens) {
            modelsCalled.add(model);
            return GuardedResult.of("reply from " + model, TokenUsage.of(tokens, 0));
        }
    }

    @Test
    void routesTheNextCallToTheFallbackOnceTheLimitIsCrossed() {
        BudgetGuard guard = guardBuilder().build();
        RecordingClient client = new RecordingClient();

        // $12 on the expensive model: over the $10 limit
        guard.call(SESSION, EXPENSIVE, model -> client.chat(model, 6));
        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("12.00", "USD"));

        String second = guard.call(SESSION, EXPENSIVE, model -> client.chat(model, 3));

        assertThat(client.modelsCalled).containsExactly(EXPENSIVE, CHEAP);
        assertThat(second).isEqualTo("reply from " + CHEAP);
    }

    @Test
    void pricesFallbackCallsAtTheFallbackModelsRate() {
        BudgetGuard guard = guardBuilder().build();
        RecordingClient client = new RecordingClient();

        guard.call(SESSION, EXPENSIVE, model -> client.chat(model, 6)); // $12
        guard.call(SESSION, EXPENSIVE, model -> client.chat(model, 3)); // 3 tokens on the cheap model

        // $3, not the $6 those tokens would have cost on the expensive model
        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("15.00", "USD"));
        assertThat(guard.snapshot(SESSION).spentOn(CHEAP)).isEqualTo(Money.of("3.00", "USD"));
        assertThat(guard.snapshot(SESSION).spentOn(EXPENSIVE)).isEqualTo(Money.of("12.00", "USD"));
    }

    @Test
    void attributesTheCallToTheModelThatActuallyRanIt() {
        BudgetGuard guard = guardBuilder().build();
        RecordingClient client = new RecordingClient();

        guard.call(SESSION, EXPENSIVE, model -> client.chat(model, 6));
        guard.call(SESSION, EXPENSIVE, model -> client.chat(model, 3));

        assertThat(guard.snapshot(SESSION).calls()).extracting(CallRecord::model)
                .containsExactly(EXPENSIVE, CHEAP);
    }

    @Test
    void exhaustingTheBudgetAtTheFallbackRateStillStops() {
        BudgetGuard guard = guardBuilder().build();
        RecordingClient client = new RecordingClient();

        guard.call(SESSION, EXPENSIVE, model -> client.chat(model, 6)); // $12, past the limit
        guard.call(SESSION, EXPENSIVE, model -> client.chat(model, 5)); // $5 cheap, now $17
        guard.call(SESSION, EXPENSIVE, model -> client.chat(model, 5)); // $5 cheap, now $22

        // past the $20 hard limit, so even the cheap model is refused
        assertThatThrownBy(() -> guard.call(SESSION, EXPENSIVE, model -> client.chat(model, 1)))
                .isInstanceOf(BudgetExceededException.class);

        assertThat(client.modelsCalled).containsExactly(EXPENSIVE, CHEAP, CHEAP);
        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("22.00", "USD"));
    }

    @Test
    void staysOnTheRequestedModelWhileUnderTheLimit() {
        BudgetGuard guard = guardBuilder().build();
        RecordingClient client = new RecordingClient();

        guard.call(SESSION, EXPENSIVE, model -> client.chat(model, 1)); // $2
        guard.call(SESSION, EXPENSIVE, model -> client.chat(model, 1)); // $4

        assertThat(client.modelsCalled).containsExactly(EXPENSIVE, EXPENSIVE);
    }

    @Test
    void behavesLikeAnOrdinaryCallUnderEveryOtherPolicy() {
        BudgetGuard guard = BudgetGuard.builder()
                .limit(Money.of("10.00", "USD"))
                .pricingCatalog(CATALOG)
                .onExceed(ExceedPolicy.STOP)
                .build();
        RecordingClient client = new RecordingClient();

        guard.call(SESSION, EXPENSIVE, model -> client.chat(model, 6)); // $12, over the limit

        assertThatThrownBy(() -> guard.call(SESSION, EXPENSIVE, model -> client.chat(model, 1)))
                .isInstanceOf(BudgetExceededException.class);
        assertThat(client.modelsCalled).containsExactly(EXPENSIVE);
    }

    @Nested
    class TheOpaqueWrapEntryPoint {

        @Test
        void refusesToSwitchRatherThanMisPriceTheCall() {
            BudgetGuard guard = guardBuilder().build();
            guard.call(SESSION, EXPENSIVE, model -> GuardedResult.of("x", TokenUsage.of(6, 0))); // $12

            // wrap() cannot re-point an opaque call, so it says so rather than sending the call to
            // the expensive model and charging it at the cheap model's rate
            assertThatThrownBy(() -> guard.wrap(SESSION, EXPENSIVE,
                    () -> GuardedResult.of("x", TokenUsage.of(1, 0))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("call(sessionId, model, m -> ...)");
        }

        @Test
        void isUnaffectedWhileTheSessionIsUnderItsLimit() {
            BudgetGuard guard = guardBuilder().build();

            assertThatCode(() -> guard.wrap(SESSION, EXPENSIVE,
                    () -> GuardedResult.of("x", TokenUsage.of(1, 0)))).doesNotThrowAnyException();
        }
    }

    @Nested
    class Streaming {

        private final ChunkInspector<FakeStreamingLlmClient.StreamChunk> inspector =
                ChunkInspector.of(FakeStreamingLlmClient.StreamChunk::textDelta,
                        FakeStreamingLlmClient.StreamChunk::usageFrame);

        @Test
        void nominatesTheFallbackModelOnTheSession() {
            BudgetGuard guard = guardBuilder().build();
            guard.call(SESSION, EXPENSIVE, model -> GuardedResult.of("x", TokenUsage.of(6, 0))); // $12

            try (StreamSession<FakeStreamingLlmClient.StreamChunk> session =
                         guard.openStream(SESSION, EXPENSIVE, inspector)) {
                assertThat(session.model()).isEqualTo(CHEAP);
            }
        }

        @Test
        void chargesTheStreamAtTheNominatedModelsRate() {
            BudgetGuard guard = guardBuilder().build();
            guard.call(SESSION, EXPENSIVE, model -> GuardedResult.of("x", TokenUsage.of(6, 0))); // $12

            FakeStreamingLlmClient client = new FakeStreamingLlmClient()
                    .thenText("hi")
                    .thenUsageFrame(4, 0);
            try (StreamSession<FakeStreamingLlmClient.StreamChunk> session =
                         guard.openStream(SESSION, EXPENSIVE, inspector)) {
                for (FakeStreamingLlmClient.StreamChunk chunk : client.stream("go")) {
                    session.observe(chunk);
                }
            }

            // 4 tokens at the cheap rate is $4, not the $8 the expensive model would have cost
            assertThat(guard.spend(SESSION)).isEqualTo(Money.of("16.00", "USD"));
            assertThat(guard.snapshot(SESSION).spentOn(CHEAP)).isEqualTo(Money.of("4.00", "USD"));
        }

        @Test
        void reportsTheRequestedModelWhileUnderTheLimit() {
            BudgetGuard guard = guardBuilder().build();

            try (StreamSession<FakeStreamingLlmClient.StreamChunk> session =
                         guard.openStream(SESSION, EXPENSIVE, inspector)) {
                assertThat(session.model()).isEqualTo(EXPENSIVE);
            }
        }
    }

    @Nested
    class Configuration {

        @Test
        void aGuardWithoutAFallbackModelFailsAtBuildTimeRatherThanAtTheBreach() {
            assertThatThrownBy(() -> BudgetGuard.builder()
                    .limit(Money.of("10.00", "USD"))
                    .pricingCatalog(CATALOG)
                    .onExceed(ExceedPolicy.SWITCH_MODEL)
                    .hardLimit(Money.of("20.00", "USD"))
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("fallbackModel");
        }

        @Test
        void aFallbackModelTheCatalogCannotPriceIsNotAUsableFallback() {
            assertThatThrownBy(() -> guardBuilder().fallbackModel("never-registered").build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a usable fallback")
                    .cause().isInstanceOf(UnknownModelException.class);
        }

        @Test
        void aGuardWithoutAHardLimitFailsAtBuildTime() {
            assertThatThrownBy(() -> BudgetGuard.builder()
                    .limit(Money.of("10.00", "USD"))
                    .pricingCatalog(CATALOG)
                    .onExceed(ExceedPolicy.SWITCH_MODEL)
                    .fallbackModel(CHEAP)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("hardLimit");
        }

        @Test
        void aHardLimitBelowTheLimitWouldMakeTheFallbackUnreachable() {
            assertThatThrownBy(() -> guardBuilder().hardLimit(Money.of("5.00", "USD")).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be above the limit");
        }

        @Test
        void exposesWhatItWillDegradeToAndWhereItWillStop() {
            BudgetGuard guard = guardBuilder().build();
            assertThat(guard.fallbackModel()).isEqualTo(CHEAP);
            assertThat(guard.hardLimit()).isEqualTo(Money.of("20.00", "USD"));
        }

        @Test
        void otherPoliciesDoNotRequireAFallbackAtAll() {
            assertThatCode(() -> BudgetGuard.builder()
                    .limit(Money.of("10.00", "USD"))
                    .pricingCatalog(CATALOG)
                    .onExceed(ExceedPolicy.WARN)
                    .build()).doesNotThrowAnyException();
        }
    }
}
