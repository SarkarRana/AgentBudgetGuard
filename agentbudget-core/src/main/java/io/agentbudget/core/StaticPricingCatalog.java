package io.agentbudget.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link PricingCatalog} backed by a fixed, in-memory table. The built-in OpenAI/Anthropic
 * tables and custom registration land in a later slice; for the walking skeleton this holds
 * whatever models the caller registers.
 */
public final class StaticPricingCatalog implements PricingCatalog {

    private final Map<String, ModelPricing> pricing;

    private StaticPricingCatalog(Map<String, ModelPricing> pricing) {
        this.pricing = pricing;
    }

    public static StaticPricingCatalog withSingleModel(String model, ModelPricing pricing) {
        return builder().register(model, pricing).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Money price(String model, TokenUsage usage) {
        ModelPricing rate = pricing.get(model);
        if (rate == null) {
            throw new UnknownModelException(model);
        }
        Money inputCost = rate.pricePerInputToken().multiply(java.math.BigDecimal.valueOf(usage.inputTokens()));
        Money outputCost = rate.pricePerOutputToken().multiply(java.math.BigDecimal.valueOf(usage.outputTokens()));
        return inputCost.plus(outputCost);
    }

    public static final class Builder {
        private final Map<String, ModelPricing> pricing = new LinkedHashMap<>();

        public Builder register(String model, ModelPricing modelPricing) {
            pricing.put(Objects.requireNonNull(model, "model"), Objects.requireNonNull(modelPricing, "pricing"));
            return this;
        }

        public StaticPricingCatalog build() {
            return new StaticPricingCatalog(Map.copyOf(pricing));
        }
    }
}
