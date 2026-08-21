package io.agentbudget.core;

/**
 * Pre-flight estimation settings. Off unless configured: estimation needs a tokenisation the
 * caller may not want to pay for on every call, and a projection is a guess — a library that
 * refused calls on a guess by default would be one people turned off.
 *
 * <p>The output allowance is the interesting number. A prompt that fits the remaining budget on
 * its own can still blow through it once the model answers, so a projection that counts only the
 * prompt would let exactly the call this feature exists to stop straight through. The default is
 * deliberately generous; set it to what your {@code max_tokens} actually is.
 *
 * @param expectedOutputTokens tokens to assume the model will generate, unless a
 *                             {@link CallEstimate} names its own allowance
 */
public record Preflight(long expectedOutputTokens) {

    /** A middle-of-the-road completion length, for callers who have not measured their own. */
    public static final long DEFAULT_EXPECTED_OUTPUT_TOKENS = 1_000;

    public Preflight {
        if (expectedOutputTokens < 0) {
            throw new IllegalArgumentException(
                    "expectedOutputTokens must not be negative: " + expectedOutputTokens);
        }
    }

    /** Estimation on, with the default output allowance. */
    public static Preflight enabled() {
        return new Preflight(DEFAULT_EXPECTED_OUTPUT_TOKENS);
    }

    /** Estimation on, assuming completions of at most {@code expectedOutputTokens}. */
    public static Preflight withExpectedOutputTokens(long expectedOutputTokens) {
        return new Preflight(expectedOutputTokens);
    }

    /**
     * Estimation on, counting the prompt alone. The projection then only catches a prompt that
     * cannot fit before the model has said a word.
     */
    public static Preflight promptOnly() {
        return new Preflight(0);
    }
}
