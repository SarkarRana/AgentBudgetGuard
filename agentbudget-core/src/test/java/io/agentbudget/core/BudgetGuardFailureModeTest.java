package io.agentbudget.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A budget library must never become the cause of an outage. These drive deliberately broken
 * collaborators — a store that throws, a pricing source that throws — through both failure modes.
 */
class BudgetGuardFailureModeTest {

    private static final String MODEL = "fake-model";
    private static final String SESSION = "session-1";

    /** A pricing catalog that blows up on every lookup. */
    private static final PricingCatalog BROKEN_PRICING = (model, usage) -> {
        throw new IllegalStateException("the pricing service is down");
    };

    /** A session store that blows up on every lookup. */
    private static final SessionStore BROKEN_STORE = (sessionId, factory) -> {
        throw new IllegalStateException("the session store is down");
    };

    private static final PricingCatalog WORKING_PRICING = StaticPricingCatalog.withSingleModel(
            MODEL, ModelPricing.perMillionTokens("USD", 1_000_000, 1_000_000));

    private BudgetGuard.Builder guardBuilder() {
        return BudgetGuard.builder()
                .limit(Money.of("10.00", "USD"))
                .pricingCatalog(WORKING_PRICING);
    }

    @Nested
    class FailingOpen {

        @Test
        void isTheDefault() {
            BudgetGuard guard = guardBuilder().pricingCatalog(BROKEN_PRICING).build();

            assertThatCode(() -> guard.wrap(SESSION, MODEL, () -> GuardedResult.of("ok", TokenUsage.of(1, 1))))
                    .doesNotThrowAnyException();
        }

        @Test
        void aBrokenPricingSourceLetsTheCallThroughUnmetered() {
            BudgetGuard guard = guardBuilder()
                    .pricingCatalog(BROKEN_PRICING)
                    .onFailure(FailureMode.FAIL_OPEN)
                    .build();
            FakeLlmClient client = new FakeLlmClient().thenReply("the answer", 1, 1);

            String result = guard.wrap(SESSION, MODEL, () -> client.chat("hello"));

            assertThat(result).isEqualTo("the answer");
            assertThat(client.callCount()).isEqualTo(1);
            assertThat(guard.spend(SESSION)).isEqualTo(Money.zero(guard.limit().currency()));
        }

        @Test
        void aBrokenStoreLetsTheCallThroughUnmetered() {
            BudgetGuard guard = guardBuilder()
                    .sessionStore(BROKEN_STORE)
                    .onFailure(FailureMode.FAIL_OPEN)
                    .build();
            FakeLlmClient client = new FakeLlmClient().thenReply("the answer", 1, 1);

            assertThat(guard.wrap(SESSION, MODEL, () -> client.chat("hello"))).isEqualTo("the answer");
            assertThat(client.callCount()).isEqualTo(1);
        }

        @Test
        void anUnknownModelIsAnAccountingFailureRatherThanARefusal() {
            BudgetGuard guard = guardBuilder()
                    .pricingCatalog(StaticPricingCatalog.builder().build())
                    .onFailure(FailureMode.FAIL_OPEN)
                    .build();

            assertThatCode(() -> guard.wrap(SESSION, "never-registered",
                    () -> GuardedResult.of("ok", TokenUsage.of(1, 1)))).doesNotThrowAnyException();
        }

        @Test
        void aBrokenStreamingCallStillReturnsTheCallersStream() {
            BudgetGuard guard = guardBuilder()
                    .pricingCatalog(BROKEN_PRICING)
                    .onFailure(FailureMode.FAIL_OPEN)
                    .build();
            ChunkInspector<FakeStreamingLlmClient.StreamChunk> inspector =
                    ChunkInspector.of(FakeStreamingLlmClient.StreamChunk::textDelta,
                            FakeStreamingLlmClient.StreamChunk::usageFrame);
            FakeStreamingLlmClient client = new FakeStreamingLlmClient()
                    .thenText("hello")
                    .thenUsageFrame(1, 1);

            assertThatCode(() -> {
                try (StreamSession<FakeStreamingLlmClient.StreamChunk> session =
                             guard.openStream(SESSION, MODEL, inspector)) {
                    for (FakeStreamingLlmClient.StreamChunk chunk : client.stream("go")) {
                        session.observe(chunk);
                    }
                }
            }).doesNotThrowAnyException();
        }
    }

    @Nested
    class FailingClosed {

        @Test
        void aBrokenPricingSourceBlocksTheCall() {
            BudgetGuard guard = guardBuilder()
                    .pricingCatalog(BROKEN_PRICING)
                    .onFailure(FailureMode.FAIL_CLOSED)
                    .build();
            FakeLlmClient client = new FakeLlmClient().thenReply("the answer", 1, 1);

            assertThatThrownBy(() -> guard.wrap(SESSION, MODEL, () -> client.chat("hello")))
                    .isInstanceOf(GuardFailureException.class)
                    .hasMessageContaining("fail-closed")
                    .hasRootCauseMessage("the pricing service is down")
                    .satisfies(e -> assertThat(((GuardFailureException) e).operation()).isNotBlank());
        }

        @Test
        void aBrokenStoreBlocksTheCallBeforeItIsEverDispatched() {
            BudgetGuard guard = guardBuilder()
                    .sessionStore(BROKEN_STORE)
                    .onFailure(FailureMode.FAIL_CLOSED)
                    .build();
            FakeLlmClient client = new FakeLlmClient().thenReply("never asked", 1, 1);

            assertThatThrownBy(() -> guard.wrap(SESSION, MODEL, () -> client.chat("hello")))
                    .isInstanceOf(GuardFailureException.class)
                    .hasRootCauseMessage("the session store is down");

            // the store failed before dispatch, so the provider was never called
            assertThat(client.callCount()).isZero();
        }

        @Test
        void keepsTheUnderlyingCauseForDiagnosis() {
            BudgetGuard guard = guardBuilder()
                    .pricingCatalog(BROKEN_PRICING)
                    .onFailure(FailureMode.FAIL_CLOSED)
                    .build();

            assertThatThrownBy(() -> guard.wrap(SESSION, MODEL, () -> GuardedResult.of("x", TokenUsage.of(1, 1))))
                    .isInstanceOf(AgentBudgetException.class)
                    .cause().isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class DeliberateRefusalsAreNotFailures {

        private BudgetGuard guardFailing(FailureMode mode) {
            return guardBuilder().onFailure(mode).build();
        }

        @Test
        void aBudgetBreachStillThrowsUnderBothModes() {
            for (FailureMode mode : FailureMode.values()) {
                BudgetGuard guard = guardFailing(mode);
                guard.wrap(SESSION, MODEL, () -> GuardedResult.of("spend it", TokenUsage.of(10, 0)));

                assertThatThrownBy(() -> guard.wrap(SESSION, MODEL, () -> GuardedResult.of("x", TokenUsage.ZERO)))
                        .as("under %s", mode)
                        .isInstanceOf(BudgetExceededException.class)
                        .isNotInstanceOf(GuardFailureException.class);
            }
        }

        @Test
        void aBreakerTripStillThrowsUnderBothModes() {
            for (FailureMode mode : FailureMode.values()) {
                MutableClock clock = MutableClock.startingAtEpoch();
                BudgetGuard guard = guardBuilder()
                        .onFailure(mode)
                        .callRateBreaker(CallRateBreaker.builder()
                                .maxCalls(1).window(Duration.ofSeconds(10)).clock(clock).build())
                        .build();
                guard.wrap(SESSION, MODEL, () -> GuardedResult.of("one", TokenUsage.of(1, 0)));

                assertThatThrownBy(() -> guard.wrap(SESSION, MODEL, () -> GuardedResult.of("two", TokenUsage.ZERO)))
                        .as("under %s", mode)
                        .isInstanceOf(CallRateExceededException.class)
                        .isNotInstanceOf(GuardFailureException.class);
            }
        }

        @Test
        void aProjectedBreachStillThrowsUnderBothModes() {
            for (FailureMode mode : FailureMode.values()) {
                BudgetGuard guard = guardBuilder()
                        .onFailure(mode)
                        .tokenEstimator(characterCount -> characterCount)
                        .preflight(Preflight.promptOnly())
                        .build();

                assertThatThrownBy(() -> guard.wrap(SESSION, MODEL, CallEstimate.ofPrompt("x".repeat(50)),
                        () -> GuardedResult.of("never", TokenUsage.ZERO)))
                        .as("under %s", mode)
                        .isInstanceOf(ProjectedBudgetExceededException.class)
                        .isNotInstanceOf(GuardFailureException.class);
            }
        }

        @Test
        void everyRefusalSharesOneBaseTypeDistinctFromAFailure() {
            assertThat(BudgetDecisionException.class).isAssignableFrom(BudgetExceededException.class);
            assertThat(BudgetDecisionException.class).isAssignableFrom(CallRateExceededException.class);
            assertThat(BudgetDecisionException.class).isAssignableFrom(ProjectedBudgetExceededException.class);
            assertThat(BudgetDecisionException.class.isAssignableFrom(GuardFailureException.class)).isFalse();
            // and everything in the library still shares one root
            assertThat(AgentBudgetException.class).isAssignableFrom(BudgetDecisionException.class);
            assertThat(AgentBudgetException.class).isAssignableFrom(GuardFailureException.class);
        }
    }

    @Nested
    class AFailingUserCallback {

        private final Supplier<BudgetEventListener> exploding = () -> event -> {
            throw new IllegalStateException("the metrics backend is down");
        };

        @Test
        void doesNotBreakTheCallUnderEitherMode() {
            for (FailureMode mode : FailureMode.values()) {
                BudgetGuard guard = guardBuilder()
                        .onFailure(mode)
                        .warnAt(BudgetThreshold.ofFraction(0.5))
                        .onBudgetEvent(exploding.get())
                        .build();

                assertThatCode(() -> guard.wrap(SESSION, MODEL,
                        () -> GuardedResult.of("ok", TokenUsage.of(8, 0))))
                        .as("under %s", mode)
                        .doesNotThrowAnyException();

                // and the spend was still recorded, callback or no callback
                assertThat(guard.spend(SESSION)).isEqualTo(Money.of("8.00", "USD"));
            }
        }
    }

    @Test
    void aCallersOwnFailureIsNeverTreatedAsTheGuardsFailure() {
        BudgetGuard guard = guardBuilder().onFailure(FailureMode.FAIL_CLOSED).build();
        FakeLlmClient client = new FakeLlmClient().thenFailBeforeReachingProvider();

        // the caller's transport error is theirs; the guard must not dress it up as its own
        assertThatThrownBy(() -> guard.wrap(SESSION, MODEL, () -> client.chat("hello")))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(AgentBudgetException.class)
                .hasMessage("connection refused");
    }

    @Test
    void aPartialUsageFailureStillPropagatesAndIsStillCharged() {
        BudgetGuard guard = guardBuilder().onFailure(FailureMode.FAIL_CLOSED).build();
        FakeLlmClient client = new FakeLlmClient().thenFailAfterGeneratingTokens(2, 0);

        assertThatThrownBy(() -> guard.wrap(SESSION, MODEL, () -> client.chat("hello")))
                .isInstanceOf(PartialUsageException.class)
                .isNotInstanceOf(GuardFailureException.class);

        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("2.00", "USD"));
    }
}
