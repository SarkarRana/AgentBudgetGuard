package io.agentbudget.core;

import java.math.BigDecimal;

/**
 * Per-token input, cached-input, and output rates for one model, in one currency.
 * Cached input tokens typically price at a lower rate than fresh input tokens.
 */
public record ModelPricing(Money pricePerInputToken, Money pricePerCachedInputToken, Money pricePerOutputToken) {

    public ModelPricing {
        if (pricePerInputToken.isNegative() || pricePerCachedInputToken.isNegative() || pricePerOutputToken.isNegative()) {
            throw new IllegalArgumentException("Per-token prices must not be negative");
        }
        java.util.Currency currency = pricePerInputToken.currency();
        if (!pricePerCachedInputToken.currency().equals(currency) || !pricePerOutputToken.currency().equals(currency)) {
            throw new IllegalArgumentException("All rates must share a currency");
        }
    }

    /**
     * Convenience factory for the common case of provider list prices, quoted per million tokens.
     */
    public static ModelPricing perMillionTokens(String currencyCode, double inputPerMillion, double cachedInputPerMillion, double outputPerMillion) {
        BigDecimal perMillion = BigDecimal.valueOf(1_000_000);
        Money input = Money.of(BigDecimal.valueOf(inputPerMillion).divide(perMillion), java.util.Currency.getInstance(currencyCode));
        Money cachedInput = Money.of(BigDecimal.valueOf(cachedInputPerMillion).divide(perMillion), java.util.Currency.getInstance(currencyCode));
        Money output = Money.of(BigDecimal.valueOf(outputPerMillion).divide(perMillion), java.util.Currency.getInstance(currencyCode));
        return new ModelPricing(input, cachedInput, output);
    }

    /**
     * Convenience factory when cached input pricing is the same as regular input pricing.
     */
    public static ModelPricing perMillionTokens(String currencyCode, double inputPerMillion, double outputPerMillion) {
        return perMillionTokens(currencyCode, inputPerMillion, inputPerMillion, outputPerMillion);
    }
}
