package io.agentbudget.core;

/**
 * Input and output token counts for a single completed (or partially completed) call.
 * Cached input tokens are a distinct field that price at their own (typically lower) rate.
 */
public record TokenUsage(long inputTokens, long cachedInputTokens, long outputTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0);

    public TokenUsage {
        if (inputTokens < 0) {
            throw new IllegalArgumentException("inputTokens must not be negative: " + inputTokens);
        }
        if (cachedInputTokens < 0) {
            throw new IllegalArgumentException("cachedInputTokens must not be negative: " + cachedInputTokens);
        }
        if (outputTokens < 0) {
            throw new IllegalArgumentException("outputTokens must not be negative: " + outputTokens);
        }
    }

    public static TokenUsage of(long inputTokens, long cachedInputTokens, long outputTokens) {
        return new TokenUsage(inputTokens, cachedInputTokens, outputTokens);
    }

    public static TokenUsage of(long inputTokens, long outputTokens) {
        return new TokenUsage(inputTokens, 0, outputTokens);
    }
}
