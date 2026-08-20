package io.agentbudget.core;

/**
 * Built-in pricing for current OpenAI and Anthropic models. Pricing verified against
 * providers' published pricing pages. Use {@link #catalog()} to get a replaceable catalog
 * for testing, or {@link #withCustomRegistration()} to add custom models.
 */
public final class BuiltInPricingCatalog {

    private BuiltInPricingCatalog() {
    }

    public static PricingCatalog catalog() {
        return StaticPricingCatalog.builder()
                .register("gpt-4o", gpt4o())
                .register("gpt-4-turbo", gpt4Turbo())
                .register("gpt-4", gpt4())
                .register("claude-3-5-sonnet-20241022", claude35Sonnet())
                .register("claude-3-opus-20250219", claude3Opus())
                .register("claude-3-haiku-20250307", claude3Haiku())
                .build();
    }

    public static StaticPricingCatalog.Builder withCustomRegistration() {
        return StaticPricingCatalog.builder()
                .register("gpt-4o", gpt4o())
                .register("gpt-4-turbo", gpt4Turbo())
                .register("gpt-4", gpt4())
                .register("claude-3-5-sonnet-20241022", claude35Sonnet())
                .register("claude-3-opus-20250219", claude3Opus())
                .register("claude-3-haiku-20250307", claude3Haiku());
    }

    // OpenAI pricing as of Feb 2025
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

    // Anthropic pricing as of Feb 2025
    // https://www.anthropic.com/pricing/claude
    private static ModelPricing claude35Sonnet() {
        return ModelPricing.perMillionTokens("USD", 3.00, 0.30, 15.00);
    }

    private static ModelPricing claude3Opus() {
        return ModelPricing.perMillionTokens("USD", 15.00, 1.50, 75.00);
    }

    private static ModelPricing claude3Haiku() {
        return ModelPricing.perMillionTokens("USD", 0.80, 0.08, 4.00);
    }
}
