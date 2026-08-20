package io.agentbudget.core;

import java.math.BigDecimal;

/**
 * Per-token input and output rates for one model, in one currency.
 */
public record ModelPricing(Money pricePerInputToken, Money pricePerOutputToken) {

    public ModelPricing {
        if (pricePerInputToken.isNegative() || pricePerOutputToken.isNegative()) {
            throw new IllegalArgumentException("Per-token prices must not be negative");
        }
        if (!pricePerInputToken.currency().equals(pricePerOutputToken.currency())) {
            throw new IllegalArgumentException("Input and output rates must share a currency");
        }
    }

    /**
     * Convenience factory for the common case of provider list prices, quoted per million tokens.
     */
    public static ModelPricing perMillionTokens(String currencyCode, double inputPerMillion, double outputPerMillion) {
        BigDecimal perMillion = BigDecimal.valueOf(1_000_000);
        Money input = Money.of(BigDecimal.valueOf(inputPerMillion).divide(perMillion), java.util.Currency.getInstance(currencyCode));
        Money output = Money.of(BigDecimal.valueOf(outputPerMillion).divide(perMillion), java.util.Currency.getInstance(currencyCode));
        return new ModelPricing(input, output);
    }
}
