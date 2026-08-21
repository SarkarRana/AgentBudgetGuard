package io.agentbudget.core;

import java.util.Objects;

/**
 * Consumes what a stream produced, incrementally, and produces one reconciled token usage record.
 *
 * <p>It owns the precedence rule that decides what a streaming call cost, in three parts:
 *
 * <ol>
 *   <li><b>An authoritative frame beats an estimate.</b> Until the provider sends usage counts,
 *       the running total is inferred from the number of characters seen; once counts arrive,
 *       the inference they cover is discarded rather than added to.</li>
 *   <li><b>A later frame supersedes an earlier one field by field.</b> Providers that restate a
 *       running total reconcile to their last word, not to a sum of restatements — but a frame
 *       that omits a field (Anthropic's closing frame carries output tokens and leaves input at
 *       zero) does not erase what an earlier frame reported for it.</li>
 *   <li><b>Text that arrives after the last frame is still charged.</b> A frame accounts for the
 *       stream up to the moment it was sent, so text generated afterwards is topped up with an
 *       estimate and the result is marked estimated. Without this, a provider that opens with a
 *       usage frame — as Anthropic does — would give away every token after it for free.</li>
 * </ol>
 *
 * <p>{@link #reconcile()} is meaningful at any point, which is what makes an aborted or errored
 * stream chargeable for what it consumed: reconciling is just asking the question earlier than
 * usual. It never throws and never returns nothing, because the rule the library rests on is that
 * a streaming call is never silently free.
 *
 * <p>State is a character count and at most one merged usage frame — never the chunks — so a
 * stream of any length costs the same fixed memory. This class does no I/O, knows nothing about
 * {@link BudgetGuard}, and is not thread-safe; {@link StreamSession} owns the synchronization.
 *
 * <p>See ADR 0002 for why reconciliation takes this shape.
 */
public final class StreamingUsageAggregator {

    private final TokenEstimator tokenEstimator;

    /** Characters seen since the last usage frame — the part no frame has accounted for yet. */
    private long unaccountedCharacters;

    /** Every frame seen so far, merged field by field. Null until the provider sends one. */
    private TokenUsage authoritativeUsage;

    public StreamingUsageAggregator(TokenEstimator tokenEstimator) {
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
    }

    /**
     * Records text a chunk contributed. Null and empty are accepted and contribute nothing —
     * plenty of real chunks carry no text.
     */
    public void observeText(String textDelta) {
        if (textDelta != null) {
            unaccountedCharacters += textDelta.length();
        }
    }

    /**
     * Records the provider's authoritative counts, merged over any earlier frame: a non-zero
     * value supersedes what was reported before, a zero leaves it standing.
     *
     * <p>The frame is taken to account for all text observed before this call, including text
     * carried by the very chunk the frame arrived on — so a caller feeding a chunk that has both
     * must observe the text first, as {@link StreamSession#observe} does. Text observed after
     * this call is not covered and is estimated on top.
     */
    public void observeUsageFrame(TokenUsage usage) {
        Objects.requireNonNull(usage, "usage");
        authoritativeUsage = authoritativeUsage == null ? usage : mergeOver(authoritativeUsage, usage);
        unaccountedCharacters = 0;
    }

    /**
     * The best answer available right now. Safe to call mid-stream — that is what makes an
     * abandoned or errored stream chargeable for what it consumed — and safe to call repeatedly:
     * it reports on the observations so far without consuming them.
     */
    public ReconciledUsage reconcile() {
        long estimatedOutputTokens = estimateUnaccountedOutput();

        if (authoritativeUsage == null) {
            // no frame ever arrived: the whole total is inferred, and zero is a legitimate answer
            return ReconciledUsage.estimated(TokenUsage.of(0, 0, estimatedOutputTokens));
        }
        if (estimatedOutputTokens == 0) {
            // the frame covers everything observed — the ordinary end-of-stream case
            return ReconciledUsage.authoritative(authoritativeUsage);
        }
        return ReconciledUsage.estimated(new TokenUsage(
                authoritativeUsage.inputTokens(),
                authoritativeUsage.cachedInputTokens(),
                saturatingSum(authoritativeUsage.outputTokens(), estimatedOutputTokens)));
    }

    /**
     * A later frame's non-zero counts win; the fields it leaves at zero keep what was already
     * reported. Providers differ on whether a closing frame restates the full picture or only the
     * part that changed, and reading "absent" as "zero" would silently drop input tokens.
     */
    private static TokenUsage mergeOver(TokenUsage earlier, TokenUsage later) {
        return new TokenUsage(
                later.inputTokens() > 0 ? later.inputTokens() : earlier.inputTokens(),
                later.cachedInputTokens() > 0 ? later.cachedInputTokens() : earlier.cachedInputTokens(),
                later.outputTokens() > 0 ? later.outputTokens() : earlier.outputTokens());
    }

    /**
     * A custom estimator is caller code and may return nonsense; a negative count is floored at
     * zero rather than allowed to throw out of {@link #reconcile()}, because an exception here
     * would land in {@code close()} and lose the charge entirely.
     */
    private long estimateUnaccountedOutput() {
        if (unaccountedCharacters == 0) {
            return 0;
        }
        return Math.max(0, tokenEstimator.estimateOutputTokens(unaccountedCharacters));
    }

    /** Both operands are non-negative, so a negative sum can only mean overflow. */
    private static long saturatingSum(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }
}
