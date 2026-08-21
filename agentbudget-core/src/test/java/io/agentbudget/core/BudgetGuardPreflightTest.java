package io.agentbudget.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pre-flight estimation: refusing the one enormous prompt that post-hoc accounting can only
 * notice after the money is gone.
 *
 * <p>One token per character and $1 per token throughout, so a prompt of {@code n} characters
 * costs {@code $n} and every figure below is readable straight off the string literals.
 */
class BudgetGuardPreflightTest {

    private static final String MODEL = "fake-model";
    private static final String SESSION = "session-1";

    private BudgetGuard.Builder guardBuilder(String limit) {
        return BudgetGuard.builder()
                .limit(Money.of(limit, "USD"))
                .pricingCatalog(StaticPricingCatalog.withSingleModel(
                        MODEL, ModelPricing.perMillionTokens("USD", 1_000_000, 1_000_000)))
                .tokenEstimator(characterCount -> characterCount);
    }

    private static String promptOf(int characters) {
        return "x".repeat(characters);
    }

    @Nested
    class WhenEstimationIsOff {

        @Test
        void isTheDefault() {
            BudgetGuard guard = guardBuilder("10.00").build();
            assertThatThrownBy(() -> guard.projectedCost(MODEL, CallEstimate.ofPrompt("anything")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not enabled");
        }

        @Test
        void anEnormousPromptGoesOutAndOvershootsExactlyAsBefore() {
            BudgetGuard guard = guardBuilder("10.00").build();
            FakeLlmClient client = new FakeLlmClient().thenReply("done", 500, 0);

            guard.wrap(SESSION, MODEL, CallEstimate.ofPrompt(promptOf(500)), () -> client.chat("huge"));

            assertThat(client.callCount()).isEqualTo(1);
            assertThat(guard.spend(SESSION)).isEqualTo(Money.of("500.00", "USD"));
        }

        @Test
        void passingAnEstimateChangesNothingAtAll() {
            BudgetGuard withEstimate = guardBuilder("10.00").build();
            BudgetGuard without = guardBuilder("10.00").build();

            withEstimate.wrap(SESSION, MODEL, CallEstimate.ofPrompt(promptOf(4)),
                    () -> GuardedResult.of("ok", TokenUsage.of(4, 0)));
            without.wrap(SESSION, MODEL, () -> GuardedResult.of("ok", TokenUsage.of(4, 0)));

            assertThat(withEstimate.spend(SESSION)).isEqualTo(without.spend(SESSION));
            assertThat(withEstimate.snapshot(SESSION).calls()).hasSize(1);
        }
    }

    @Nested
    class WhenEstimationIsOn {

        /** A $100 limit, and every call assumed to generate 10 tokens of output. */
        private BudgetGuard guard() {
            return guardBuilder("100.00").preflight(Preflight.withExpectedOutputTokens(10)).build();
        }

        @Test
        void aPromptThatFitsIsDispatched() {
            BudgetGuard guard = guard();
            FakeLlmClient client = new FakeLlmClient().thenReply("done", 20, 5);

            // 20 prompt + 10 allowance = $30 projected, comfortably inside $100
            assertThatCode(() -> guard.wrap(SESSION, MODEL, CallEstimate.ofPrompt(promptOf(20)),
                    () -> client.chat("fine"))).doesNotThrowAnyException();

            assertThat(client.callCount()).isEqualTo(1);
            assertThat(guard.spend(SESSION)).isEqualTo(Money.of("25.00", "USD")); // what it really cost
        }

        @Test
        void aPromptThatDoesNotFitIsRefusedBeforeDispatch() {
            BudgetGuard guard = guard();
            FakeLlmClient client = new FakeLlmClient().thenReply("never asked", 500, 0);

            assertThatThrownBy(() -> guard.wrap(SESSION, MODEL, CallEstimate.ofPrompt(promptOf(500)),
                    () -> client.chat("huge")))
                    .isInstanceOf(ProjectedBudgetExceededException.class);

            // the call never went out, and nothing was charged
            assertThat(client.callCount()).isZero();
            assertThat(guard.spend(SESSION)).isEqualTo(Money.zero(guard.limit().currency()));
        }

        @Test
        void aPromptThatFitsOnlyWithoutTheOutputAllowanceIsStillRefused() {
            BudgetGuard guard = guard();
            FakeLlmClient client = new FakeLlmClient().thenReply("never asked", 95, 0);

            // 95 characters fits inside $100 on its own; add the 10-token allowance and it does not.
            // This is the case the allowance exists for.
            assertThatThrownBy(() -> guard.wrap(SESSION, MODEL, CallEstimate.ofPrompt(promptOf(95)),
                    () -> client.chat("just about")))
                    .isInstanceOf(ProjectedBudgetExceededException.class)
                    .satisfies(thrown -> {
                        ProjectedBudgetExceededException e = (ProjectedBudgetExceededException) thrown;
                        assertThat(e.projectedCost()).isEqualTo(Money.of("105.00", "USD"));
                        assertThat(e.projectedUsage()).isEqualTo(TokenUsage.of(95, 0, 10));
                    });
            assertThat(client.callCount()).isZero();

            // with the allowance turned off, the same prompt goes out
            BudgetGuard promptOnly = guardBuilder("100.00").preflight(Preflight.promptOnly()).build();
            assertThatCode(() -> promptOnly.wrap(SESSION, MODEL, CallEstimate.ofPrompt(promptOf(95)),
                    () -> GuardedResult.of("ok", TokenUsage.of(95, 0)))).doesNotThrowAnyException();
        }

        @Test
        void theProjectionAccountsForSpendAlreadyOnTheSession() {
            BudgetGuard guard = guard();
            guard.wrap(SESSION, MODEL, () -> GuardedResult.of("first", TokenUsage.of(80, 0))); // $80 spent

            // a $30 projection that would have fitted an empty session no longer fits
            assertThatThrownBy(() -> guard.wrap(SESSION, MODEL, CallEstimate.ofPrompt(promptOf(20)),
                    () -> GuardedResult.of("second", TokenUsage.of(20, 0))))
                    .isInstanceOf(ProjectedBudgetExceededException.class)
                    .satisfies(thrown -> {
                        ProjectedBudgetExceededException e = (ProjectedBudgetExceededException) thrown;
                        assertThat(e.currentSpend()).isEqualTo(Money.of("80.00", "USD"));
                        assertThat(e.projectedTotal()).isEqualTo(Money.of("110.00", "USD"));
                        assertThat(e.limit()).isEqualTo(Money.of("100.00", "USD"));
                    });
        }

        @Test
        void aProjectionLandingExactlyOnTheLimitIsAllowed() {
            BudgetGuard guard = guard();

            // 90 prompt + 10 allowance = exactly $100
            assertThatCode(() -> guard.wrap(SESSION, MODEL, CallEstimate.ofPrompt(promptOf(90)),
                    () -> GuardedResult.of("ok", TokenUsage.of(90, 0)))).doesNotThrowAnyException();
        }

        @Test
        void refusalIsDistinguishableFromAnActualBreach() {
            BudgetGuard guard = guard();

            assertThatThrownBy(() -> guard.wrap(SESSION, MODEL, CallEstimate.ofPrompt(promptOf(500)),
                    () -> GuardedResult.of("never", TokenUsage.ZERO)))
                    .isInstanceOf(ProjectedBudgetExceededException.class)
                    .isInstanceOf(AgentBudgetException.class)
                    .isNotInstanceOf(BudgetExceededException.class)
                    .hasMessageContaining("projected");

            // and the real thing is still the real thing
            guard.wrap(SESSION, MODEL, () -> GuardedResult.of("spend it all", TokenUsage.of(200, 0)));
            assertThatThrownBy(() -> guard.wrap(SESSION, MODEL, () -> GuardedResult.of("x", TokenUsage.ZERO)))
                    .isInstanceOf(BudgetExceededException.class)
                    .isNotInstanceOf(ProjectedBudgetExceededException.class);
        }

        @Test
        void aCallWithoutAnEstimateIsNotProjectedAtAll() {
            BudgetGuard guard = guard();
            FakeLlmClient client = new FakeLlmClient().thenReply("done", 500, 0);

            // no estimate supplied, so there is nothing to project: the call goes out
            assertThatCode(() -> guard.wrap(SESSION, MODEL, () -> client.chat("huge")))
                    .doesNotThrowAnyException();
            assertThat(guard.spend(SESSION)).isEqualTo(Money.of("500.00", "USD"));
        }
    }

    @Nested
    class TheEstimate {

        private BudgetGuard guard() {
            return guardBuilder("100.00").preflight(Preflight.withExpectedOutputTokens(10)).build();
        }

        @Test
        void sizesAPromptWithTheConfiguredEstimator() {
            assertThat(guard().projectedCost(MODEL, CallEstimate.ofPrompt(promptOf(20))))
                    .isEqualTo(Money.of("30.00", "USD")); // 20 prompt + 10 allowance
        }

        @Test
        void acceptsATokenCountTheCallerHasAlreadyWorkedOut() {
            assertThat(guard().projectedCost(MODEL, CallEstimate.ofInputTokens(20)))
                    .isEqualTo(Money.of("30.00", "USD"));
        }

        @Test
        void letsOneCallOverrideTheOutputAllowance() {
            assertThat(guard().projectedCost(MODEL, CallEstimate.ofPrompt(promptOf(20))
                    .withExpectedOutputTokens(50)))
                    .isEqualTo(Money.of("70.00", "USD"));
        }

        @Test
        void pricesCachedInputAtItsOwnRate() {
            // cached input at a tenth of the normal rate
            PricingCatalog catalog = StaticPricingCatalog.withSingleModel("cached",
                    ModelPricing.perMillionTokens("USD", 1_000_000, 100_000, 1_000_000));
            BudgetGuard guard = BudgetGuard.builder()
                    .limit(Money.of("100.00", "USD"))
                    .pricingCatalog(catalog)
                    .tokenEstimator(characterCount -> characterCount)
                    .preflight(Preflight.promptOnly())
                    .build();

            assertThat(guard.projectedCost("cached", CallEstimate.ofInputTokens(10).withCachedInputTokens(10)))
                    .isEqualTo(Money.of("11.00", "USD")); // 10 fresh @ $1 + 10 cached @ $0.10
        }

        @Test
        void rejectsNegativeCounts() {
            assertThatThrownBy(() -> CallEstimate.ofInputTokens(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative");
            assertThatThrownBy(() -> CallEstimate.ofInputTokens(1).withExpectedOutputTokens(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative");
            assertThatThrownBy(() -> Preflight.withExpectedOutputTokens(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative");
        }
    }
}
