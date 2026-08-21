package io.agentbudget.core;

/**
 * Built-in pricing for current OpenAI and Anthropic models. Anthropic rates verified against
 * the Claude API's live current-models reference as of Aug 2026; OpenAI rates are carried over
 * from an earlier check and have not been independently re-verified against OpenAI's pricing
 * page at that same date -- treat gpt-4o/gpt-4-turbo/gpt-4 rates here as provisional pending
 * that check. Use {@link #catalog()} to get a replaceable catalog for testing, or
 * {@link #withCustomRegistration()} to add custom models.
 */
public final class BuiltInPricingCatalog {

    private BuiltInPricingCatalog() {
    }

    public static PricingCatalog catalog() {
        return StaticPricingCatalog.builder()
                .register("gpt-4o", gpt4o())
                .register("gpt-4-turbo", gpt4Turbo())
                .register("gpt-4", gpt4())
                .register("claude-sonnet-5", claudeSonnet5())
                .register("claude-opus-5", claudeOpus5())
                .register("claude-haiku-4-5", claudeHaiku45())
                .build();
    }

    public static StaticPricingCatalog.Builder withCustomRegistration() {
        return StaticPricingCatalog.builder()
                .register("gpt-4o", gpt4o())
                .register("gpt-4-turbo", gpt4Turbo())
                .register("gpt-4", gpt4())
                .register("claude-sonnet-5", claudeSonnet5())
                .register("claude-opus-5", claudeOpus5())
                .register("claude-haiku-4-5", claudeHaiku45());
    }

    // OpenAI pricing as of Feb 2025 -- not re-verified since; see class javadoc.
    // https://openai.com/pricing
    private static ModelPricing gpt4o() {
        return ModelPricing.perMillionTokens("USD", 2.50, 0.50, 10.00);
    }

    private static ModelPricing gpt4Turbo() {
        return ModelPricing.perMillionTokens("USD", 10.00, 5.00, 30.00);
    }

    private static ModelPricing gpt4() {
        return ModelPricing.perMillionTokens("USD", 30.00, 15.00, 60.00);
    }

    // Anthropic standard (non-introductory) API rates as of Aug 2026. Cached-input rate is
    // Anthropic's standard cache-read discount, ten percent of the input rate.
    private static ModelPricing claudeSonnet5() {
        return ModelPricing.perMillionTokens("USD", 3.00, 0.30, 15.00);
    }

    private static ModelPricing claudeOpus5() {
        return ModelPricing.perMillionTokens("USD", 5.00, 0.50, 25.00);
    }

    private static ModelPricing claudeHaiku45() {
        return ModelPricing.perMillionTokens("USD", 1.00, 0.10, 5.00);
    }
}
