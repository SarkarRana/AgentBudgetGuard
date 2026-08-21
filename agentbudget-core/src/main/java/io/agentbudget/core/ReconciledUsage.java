package io.agentbudget.core;

import java.util.Objects;

/**
 * What a stream actually consumed, together with how confident we are in that number.
 *
 * <p>{@code estimated} is false only when the whole record came from the provider's authoritative
 * usage frames. It is true both when no frame arrived at all and when a frame arrived but text
 * followed it — a partly-authoritative total topped up with an estimate is still an estimate, and
 * calling it authoritative would overstate what we know.
 *
 * <p>Callers that care about accuracy — a billing reconciliation job, the accuracy suite in slice
 * 06 — can tell the two apart; the ledger charges for both, because a streaming call is never
 * silently free.
 */
public record ReconciledUsage(TokenUsage usage, boolean estimated) {

    public ReconciledUsage {
        Objects.requireNonNull(usage, "usage");
    }

    /** Every token in this record was reported by the provider. */
    public static ReconciledUsage authoritative(TokenUsage usage) {
        return new ReconciledUsage(usage, false);
    }

    /** At least part of this record was inferred from the text observed. */
    public static ReconciledUsage estimated(TokenUsage usage) {
        return new ReconciledUsage(usage, true);
    }
}
