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
    void pricesZeroUsageAtZero() {
        ModelPricing pricing = ModelPricing.perMillionTokens("USD", 3.00, 15.00);
        PricingCatalog catalog = StaticPricingCatalog.withSingleModel("fake-model", pricing);

        Money cost = catalog.price("fake-model", TokenUsage.ZERO);

        assertThat(cost).isEqualTo(Money.zero(java.util.Currency.getInstance("USD")));
    }

    @Test
    void rejectsAnUnknownModel() {
        PricingCatalog catalog = StaticPricingCatalog.builder().build();

        assertThatThrownBy(() -> catalog.price("nonexistent-model", TokenUsage.ZERO))
                .isInstanceOf(UnknownModelException.class);
    }
}
