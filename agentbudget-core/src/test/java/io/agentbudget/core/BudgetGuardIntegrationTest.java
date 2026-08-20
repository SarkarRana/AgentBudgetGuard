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

        String first = guard.wrap(MODEL, () -> client.chat("hello"));
        assertThat(first).isEqualTo("hi");
        assertThat(guard.spend()).isEqualTo(Money.of("2.00", "USD"));

        assertThatThrownBy(() -> guard.wrap(MODEL, () -> client.chat("hello again")))
                .isInstanceOf(BudgetExceededException.class)
                .satisfies(e -> {
                    BudgetExceededException ex = (BudgetExceededException) e;
                    assertThat(ex.sessionId()).isEqualTo(guard.sessionId());
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

        assertThatThrownBy(() -> guard.wrap(MODEL, () -> client.chat("hello")))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(AgentBudgetException.class);

        assertThat(guard.spend()).isEqualTo(Money.zero(java.util.Currency.getInstance("USD")));
    }

    @Test
    void aCallThatFailsAfterGeneratingTokensStillRecordsThem() {
        BudgetGuard guard = guardWithLimit("5.00");
        FakeLlmClient client = new FakeLlmClient().thenFailAfterGeneratingTokens(1, 1); // costs $2

        assertThatThrownBy(() -> guard.wrap(MODEL, () -> client.chat("hello")))
                .isInstanceOf(PartialUsageException.class);

        assertThat(guard.spend()).isEqualTo(Money.of("2.00", "USD"));
    }

    @Test
    void remainingReflectsSpendAcrossASequenceOfCalls() {
        BudgetGuard guard = guardWithLimit("5.00");
        FakeLlmClient client = new FakeLlmClient()
                .thenReply("a", 1, 0)
                .thenReply("b", 1, 0);

        guard.wrap(MODEL, () -> client.chat("first"));
        assertThat(guard.remaining()).isEqualTo(Money.of("4.00", "USD"));

        guard.wrap(MODEL, () -> client.chat("second"));
        assertThat(guard.remaining()).isEqualTo(Money.of("3.00", "USD"));
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
}
