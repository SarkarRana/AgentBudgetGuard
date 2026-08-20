package io.agentbudget.core;

/**
 * Maps a model identifier and a token usage record to a priced {@link Money} amount. The real
 * built-in catalog (OpenAI, Anthropic, custom registration) lands in a later slice; for now this
 * is the seam the rest of the library is built against.
 */
public interface PricingCatalog {

    /**
     * @throws UnknownModelException if no pricing is registered for {@code model}
     */
    Money price(String model, TokenUsage usage);
}
