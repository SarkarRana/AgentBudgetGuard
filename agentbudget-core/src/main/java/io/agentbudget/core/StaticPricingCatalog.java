package io.agentbudget.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link PricingCatalog} backed by a fixed, in-memory table. Supports custom registration
 * and optional fallback behavior for unknown models.
 */
public final class StaticPricingCatalog implements PricingCatalog {

    private final Map<String, ModelPricing> pricing;
    private final UnknownModelBehavior unknownModelBehavior;

    private StaticPricingCatalog(Map<String, ModelPricing> pricing, UnknownModelBehavior unknownModelBehavior) {
        this.pricing = pricing;
        this.unknownModelBehavior = unknownModelBehavior;
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
            rate = unknownModelBehavior.resolve(model);
        }
        Money inputCost = rate.pricePerInputToken().multiply(java.math.BigDecimal.valueOf(usage.inputTokens()));
        Money cachedInputCost = rate.pricePerCachedInputToken().multiply(java.math.BigDecimal.valueOf(usage.cachedInputTokens()));
        Money outputCost = rate.pricePerOutputToken().multiply(java.math.BigDecimal.valueOf(usage.outputTokens()));
        return inputCost.plus(cachedInputCost).plus(outputCost);
    }

    public static final class Builder {
        private final Map<String, ModelPricing> pricing = new LinkedHashMap<>();
        private UnknownModelBehavior unknownModelBehavior = UnknownModelBehavior.THROW_EXCEPTION;

        public Builder register(String model, ModelPricing modelPricing) {
            pricing.put(Objects.requireNonNull(model, "model"), Objects.requireNonNull(modelPricing, "pricing"));
            return this;
        }

        public Builder onUnknownModel(UnknownModelBehavior behavior) {
            this.unknownModelBehavior = Objects.requireNonNull(behavior, "behavior");
            return this;
        }

        public StaticPricingCatalog build() {
            return new StaticPricingCatalog(Map.copyOf(pricing), unknownModelBehavior);
        }
    }

    public interface UnknownModelBehavior {
        ModelPricing resolve(String model);

        UnknownModelBehavior THROW_EXCEPTION = model -> {
            throw new UnknownModelException(model);
        };

        static UnknownModelBehavior withDefault(ModelPricing defaultPricing) {
            return model -> defaultPricing;
        }
    }
}
