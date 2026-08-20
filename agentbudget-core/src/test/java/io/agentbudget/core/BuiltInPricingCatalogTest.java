package io.agentbudget.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuiltInPricingCatalogTest {

    @Test
    void hasGpt4o() {
        PricingCatalog catalog = BuiltInPricingCatalog.catalog();

        Money cost = catalog.price("gpt-4o", TokenUsage.of(1_000_000, 1_000_000));

        assertThat(cost).isEqualTo(Money.of("12.50", "USD"));
    }

    @Test
    void hasClaude35Sonnet() {
        PricingCatalog catalog = BuiltInPricingCatalog.catalog();

        Money cost = catalog.price("claude-3-5-sonnet-20241022", TokenUsage.of(1_000_000, 1_000_000));

        assertThat(cost).isEqualTo(Money.of("18.00", "USD"));
    }

    @Test
    void supportsCachedInputTokens() {
        PricingCatalog catalog = BuiltInPricingCatalog.catalog();

        Money cost = catalog.price("gpt-4o", TokenUsage.of(1_000_000, 500_000, 1_000_000));

        assertThat(cost).isEqualTo(Money.of("12.75", "USD"));
    }

    @Test
    void throwsForUnknownModels() {
        PricingCatalog catalog = BuiltInPricingCatalog.catalog();

        assertThatThrownBy(() -> catalog.price("unknown-model", TokenUsage.ZERO))
                .isInstanceOf(UnknownModelException.class);
    }

    @Test
    void allowsCustomRegistration() {
        ModelPricing customPricing = ModelPricing.perMillionTokens("USD", 1.00, 2.00);
        PricingCatalog catalog = BuiltInPricingCatalog.withCustomRegistration()
                .register("custom-model", customPricing)
                .build();

        Money builtInCost = catalog.price("gpt-4o", TokenUsage.of(1_000_000, 1_000_000));
        Money customCost = catalog.price("custom-model", TokenUsage.of(1_000_000, 1_000_000));

        assertThat(builtInCost).isEqualTo(Money.of("12.50", "USD"));
        assertThat(customCost).isEqualTo(Money.of("3.00", "USD"));
    }

    @Test
    void customModelOverridesBuiltIn() {
        ModelPricing customPricing = ModelPricing.perMillionTokens("USD", 1.00, 2.00);
        PricingCatalog catalog = BuiltInPricingCatalog.withCustomRegistration()
                .register("gpt-4o", customPricing)
                .build();

        Money cost = catalog.price("gpt-4o", TokenUsage.of(1_000_000, 1_000_000));

        assertThat(cost).isEqualTo(Money.of("3.00", "USD"));
    }
}
