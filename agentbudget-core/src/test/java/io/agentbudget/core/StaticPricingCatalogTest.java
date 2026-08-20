package io.agentbudget.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaticPricingCatalogTest {

    @Test
    void pricesInputAndOutputTokensSeparately() {
        ModelPricing pricing = ModelPricing.perMillionTokens("USD", 3.00, 15.00);
        PricingCatalog catalog = StaticPricingCatalog.withSingleModel("fake-model", pricing);

        Money cost = catalog.price("fake-model", TokenUsage.of(1_000_000, 1_000_000));

        assertThat(cost).isEqualTo(Money.of("18.00", "USD"));
    }

    @Test
    void pricesCachedInputTokensSeparately() {
        ModelPricing pricing = ModelPricing.perMillionTokens("USD", 3.00, 0.30, 15.00);
        PricingCatalog catalog = StaticPricingCatalog.withSingleModel("fake-model", pricing);

        Money cost = catalog.price("fake-model", TokenUsage.of(1_000_000, 1_000_000, 1_000_000));

        assertThat(cost).isEqualTo(Money.of("18.30", "USD"));
    }

    @Test
    void pricesZeroUsageAtZero() {
        ModelPricing pricing = ModelPricing.perMillionTokens("USD", 3.00, 15.00);
        PricingCatalog catalog = StaticPricingCatalog.withSingleModel("fake-model", pricing);

        Money cost = catalog.price("fake-model", TokenUsage.ZERO);

        assertThat(cost).isEqualTo(Money.zero(java.util.Currency.getInstance("USD")));
    }

    @Test
    void rejectsAnUnknownModelByDefault() {
        PricingCatalog catalog = StaticPricingCatalog.builder().build();

        assertThatThrownBy(() -> catalog.price("nonexistent-model", TokenUsage.ZERO))
                .isInstanceOf(UnknownModelException.class);
    }

    @Test
    void usesDefaultPricingForUnknownModels() {
        ModelPricing defaultPricing = ModelPricing.perMillionTokens("USD", 1.00, 5.00);
        PricingCatalog catalog = StaticPricingCatalog.builder()
                .onUnknownModel(StaticPricingCatalog.UnknownModelBehavior.withDefault(defaultPricing))
                .build();

        Money cost = catalog.price("unknown-model", TokenUsage.of(1_000_000, 1_000_000));

        assertThat(cost).isEqualTo(Money.of("6.00", "USD"));
    }

    @Test
    void customModelOverridesBuiltIn() {
        ModelPricing builtIn = ModelPricing.perMillionTokens("USD", 3.00, 15.00);
        ModelPricing custom = ModelPricing.perMillionTokens("USD", 1.00, 5.00);
        PricingCatalog catalog = StaticPricingCatalog.builder()
                .register("model-a", builtIn)
                .register("model-a", custom)
                .build();

        Money cost = catalog.price("model-a", TokenUsage.of(1_000_000, 1_000_000));

        assertThat(cost).isEqualTo(Money.of("6.00", "USD"));
    }

    @Test
    void pricingCatalogIsReplaceable() {
        ModelPricing pricing = ModelPricing.perMillionTokens("USD", 10.00, 50.00);
        StaticPricingCatalog.Builder builder = StaticPricingCatalog.builder()
                .register("test-model", pricing);

        PricingCatalog testCatalog = builder.build();

        Money cost = testCatalog.price("test-model", TokenUsage.of(1_000_000, 1_000_000));
        assertThat(cost).isEqualTo(Money.of("60.00", "USD"));
    }

    @Test
    void supportsLocalModelsAtZeroCost() {
        ModelPricing zeroCost = ModelPricing.perMillionTokens("USD", 0.00, 0.00);
        PricingCatalog catalog = StaticPricingCatalog.withSingleModel("local-model", zeroCost);

        Money cost = catalog.price("local-model", TokenUsage.of(1_000_000, 1_000_000));

        assertThat(cost).isEqualTo(Money.zero(java.util.Currency.getInstance("USD")));
    }
}
