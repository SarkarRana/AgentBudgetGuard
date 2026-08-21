package io.agentbudget.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the {@link BudgetGuard} facade against a hand-written fake client, the way a user
 * would wire it. This is the walking skeleton's tracer bullet: a session hits its limit and the
 * guard throws before the next call goes out.
 */
class BudgetGuardIntegrationTest {

    private static final String MODEL = "fake-model";
    private static final String SESSION = "session-1";

    private BudgetGuard guardWithLimit(String limit) {
        ModelPricing pricing = ModelPricing.perMillionTokens("USD", 1_000_000, 1_000_000); // $1 per token, for round numbers
        PricingCatalog catalog = StaticPricingCatalog.withSingleModel(MODEL, pricing);
        return BudgetGuard.builder()
                .limit(Money.of(limit, "USD"))
                .onExceed(ExceedPolicy.STOP)
                .pricingCatalog(catalog)
                .build();
    }

    @Test
    void tracksSpendAcrossCallsAndStopsOnceTheLimitIsCrossed() {
        BudgetGuard guard = guardWithLimit("2.00");
        FakeLlmClient client = new FakeLlmClient()
                .thenReply("hi", 1, 1) // costs $2, exactly hits the limit
                .thenReply("hi again", 1, 0); // would cost $1 more, but never runs

        String first = guard.wrap(SESSION, MODEL, () -> client.chat("hello"));
        assertThat(first).isEqualTo("hi");
        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("2.00", "USD"));

        assertThatThrownBy(() -> guard.wrap(SESSION, MODEL, () -> client.chat("hello again")))
                .isInstanceOf(BudgetExceededException.class)
                .satisfies(e -> {
                    BudgetExceededException ex = (BudgetExceededException) e;
                    assertThat(ex.sessionId()).isEqualTo(SESSION);
                    assertThat(ex.limit()).isEqualTo(Money.of("2.00", "USD"));
                    assertThat(ex.currentSpend()).isEqualTo(Money.of("2.00", "USD"));
                });

        // the second call never reached the fake client
        assertThat(client.callCount()).isEqualTo(1);
    }

    @Test
    void aCallThatFailsBeforeReachingTheProviderRecordsNothing() {
        BudgetGuard guard = guardWithLimit("5.00");
        FakeLlmClient client = new FakeLlmClient().thenFailBeforeReachingProvider();

        assertThatThrownBy(() -> guard.wrap(SESSION, MODEL, () -> client.chat("hello")))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(AgentBudgetException.class);

        assertThat(guard.spend(SESSION)).isEqualTo(Money.zero(java.util.Currency.getInstance("USD")));
    }

    @Test
    void aCallThatFailsAfterGeneratingTokensStillRecordsThem() {
        BudgetGuard guard = guardWithLimit("5.00");
        FakeLlmClient client = new FakeLlmClient().thenFailAfterGeneratingTokens(1, 1); // costs $2

        assertThatThrownBy(() -> guard.wrap(SESSION, MODEL, () -> client.chat("hello")))
                .isInstanceOf(PartialUsageException.class);

        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("2.00", "USD"));
    }

    @Test
    void remainingReflectsSpendAcrossASequenceOfCalls() {
        BudgetGuard guard = guardWithLimit("5.00");
        FakeLlmClient client = new FakeLlmClient()
                .thenReply("a", 1, 0)
                .thenReply("b", 1, 0);

        guard.wrap(SESSION, MODEL, () -> client.chat("first"));
        assertThat(guard.remaining(SESSION)).isEqualTo(Money.of("4.00", "USD"));

        guard.wrap(SESSION, MODEL, () -> client.chat("second"));
        assertThat(guard.remaining(SESSION)).isEqualTo(Money.of("3.00", "USD"));
    }

    @Test
    void builderRejectsANonPositiveLimit() {
        PricingCatalog catalog = StaticPricingCatalog.builder().build();

        assertThatThrownBy(() -> BudgetGuard.builder().limit(Money.of("0.00", "USD")).pricingCatalog(catalog).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");

        assertThatThrownBy(() -> BudgetGuard.builder().limit(Money.of("-1.00", "USD")).pricingCatalog(catalog).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void billingWorksWithMultipleDifferentModels() {
        // Two models with different pricing: expensiveModel ($2/token), cheapModel ($1/token)
        ModelPricing expensivePricing = ModelPricing.perMillionTokens("USD", 2_000_000, 2_000_000);
        ModelPricing cheapPricing = ModelPricing.perMillionTokens("USD", 1_000_000, 1_000_000);
        PricingCatalog catalog = StaticPricingCatalog.builder()
                .register("expensive-model", expensivePricing)
                .register("cheap-model", cheapPricing)
                .build();

        BudgetGuard guard = BudgetGuard.builder()
                .limit(Money.of("10.00", "USD"))
                .onExceed(ExceedPolicy.STOP)
                .pricingCatalog(catalog)
                .build();

        FakeLlmClient client = new FakeLlmClient()
                .thenReply("expensive", 1, 1) // expensive-model: 1 input + 1 output = $2 + $2 = $4
                .thenReply("cheap", 1, 1);     // cheap-model: 1 input + 1 output = $1 + $1 = $2

        String first = guard.wrap(SESSION, "expensive-model", () -> client.chat("hello"));
        assertThat(first).isEqualTo("expensive");
        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("4.00", "USD"));

        String second = guard.wrap(SESSION, "cheap-model", () -> client.chat("again"));
        assertThat(second).isEqualTo("cheap");
        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("6.00", "USD"));
    }
}
