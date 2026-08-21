package io.agentbudget.core;

/**
 * Estimates output tokens from the amount of text a stream produced, for the fallback path where
 * the provider sent no authoritative usage frame.
 *
 * <p>The input is a character count rather than the text itself, deliberately: it is what lets
 * {@link StreamingUsageAggregator} observe a stream of any length without ever retaining a chunk.
 * See ADR 0001.
 */
@FunctionalInterface
public interface TokenEstimator {

    /**
     * The industry rule of thumb for English text on byte-pair encodings: roughly four
     * characters per token, rounded up so any non-empty output estimates as at least one token.
     */
    TokenEstimator CHARACTERS_PER_TOKEN_HEURISTIC = characterCount -> (characterCount + 3) / 4;

    long estimateOutputTokens(long characterCount);

    /**
     * The same characters-to-tokens rule applied to text going the other way — a prompt about to
     * be sent, for {@link CallEstimate}. A synonym rather than a second knob: an estimator that
     * sizes a model's output well sizes its input well too.
     */
    default long estimateTokens(long characterCount) {
        return estimateOutputTokens(characterCount);
    }
}
