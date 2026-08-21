package io.agentbudget.core;

import java.util.Objects;

/**
 * What the caller can tell the guard about a call <em>before</em> making it, so pre-flight
 * estimation has something to work from. The guard wraps an opaque supplier and cannot read the
 * prompt out of it, so this is how the prompt gets in.
 *
 * <p>Give it the prompt text and the guard's configured {@link TokenEstimator} sizes it, or give
 * it a token count directly if you have already tokenised with something better. An
 * expected-output allowance can be set per call when this one is unusual; otherwise the guard's
 * configured default applies.
 */
public final class CallEstimate {

    private final String prompt;
    private final long inputTokens;
    private final long cachedInputTokens;
    private final Long expectedOutputTokens;

    private CallEstimate(String prompt, long inputTokens, long cachedInputTokens, Long expectedOutputTokens) {
        requireNotNegative(inputTokens, "inputTokens");
        requireNotNegative(cachedInputTokens, "cachedInputTokens");
        if (expectedOutputTokens != null) {
            requireNotNegative(expectedOutputTokens, "expectedOutputTokens");
        }
        this.prompt = prompt;
        this.inputTokens = inputTokens;
        this.cachedInputTokens = cachedInputTokens;
        this.expectedOutputTokens = expectedOutputTokens;
    }

    /**
     * A prompt, sized at dispatch by the guard's configured estimator.
     */
    public static CallEstimate ofPrompt(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        return new CallEstimate(prompt, 0, 0, null);
    }

    /**
     * An input token count the caller has already worked out — from a real tokeniser, say.
     */
    public static CallEstimate ofInputTokens(long inputTokens) {
        return new CallEstimate(null, inputTokens, 0, null);
    }

    /**
     * Overrides the guard's default output allowance for this call alone.
     */
    public CallEstimate withExpectedOutputTokens(long expectedOutputTokens) {
        return new CallEstimate(prompt, inputTokens, cachedInputTokens, expectedOutputTokens);
    }

    /**
     * Marks part of the input as cache hits, which price at their own lower rate.
     */
    public CallEstimate withCachedInputTokens(long cachedInputTokens) {
        return new CallEstimate(prompt, inputTokens, cachedInputTokens, expectedOutputTokens);
    }

    /**
     * The usage this call is projected to consume. {@code defaultExpectedOutputTokens} applies
     * unless this estimate named its own allowance.
     */
    TokenUsage projectedUsage(long defaultExpectedOutputTokens, TokenEstimator estimator) {
        long input = prompt != null ? estimator.estimateTokens(prompt.length()) : inputTokens;
        long output = expectedOutputTokens != null ? expectedOutputTokens : defaultExpectedOutputTokens;
        return TokenUsage.of(input, cachedInputTokens, output);
    }

    /**
     * The usage this call is projected to consume with no output allowance at all — the prompt
     * alone. What a projection is compared against when the caller has turned the allowance off.
     */
    TokenUsage projectedInputUsage(TokenEstimator estimator) {
        return projectedUsage(0, estimator).withOutputTokens(0);
    }

    private static void requireNotNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative: " + value);
        }
    }
}
