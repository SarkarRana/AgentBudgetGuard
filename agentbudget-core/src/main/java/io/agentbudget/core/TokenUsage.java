package io.agentbudget.core;

/**
 * Input and output token counts for a single completed (or partially completed) call.
 */
public record TokenUsage(long inputTokens, long outputTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0);

    public TokenUsage {
        if (inputTokens < 0) {
            throw new IllegalArgumentException("inputTokens must not be negative: " + inputTokens);
        }
        if (outputTokens < 0) {
            throw new IllegalArgumentException("outputTokens must not be negative: " + outputTokens);
        }
    }

    public static TokenUsage of(long inputTokens, long outputTokens) {
        return new TokenUsage(inputTokens, outputTokens);
    }
}
