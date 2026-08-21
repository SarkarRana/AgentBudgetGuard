package io.agentbudget.core;

import java.util.Objects;

/**
 * What one call to {@link UsageLedger#record} did. Carries the totals either side of the record
 * so a caller can tell not just where the session's spend landed but what it crossed on the way
 * — which is how threshold callbacks fire exactly once per crossing rather than on every call
 * after it.
 *
 * <p>The pair is captured under the ledger's lock, so the interval it describes is real even
 * when several threads record against one session at once.
 *
 * @param recorded      false when the call id had already been recorded, or the cost was zero
 * @param previousTotal the session total immediately before this record
 * @param newTotal      the session total immediately after; equal to {@code previousTotal} when
 *                      nothing was recorded
 */
public record RecordOutcome(boolean recorded, Money previousTotal, Money newTotal) {

    public RecordOutcome {
        Objects.requireNonNull(previousTotal, "previousTotal");
        Objects.requireNonNull(newTotal, "newTotal");
    }

    /**
     * Whether this record took the session's spend from below {@code boundary} to at or above it.
     * False when the boundary was already crossed before this record, which is what keeps a
     * crossing callback from firing again on every subsequent call.
     */
    public boolean crossed(Money boundary) {
        Objects.requireNonNull(boundary, "boundary");
        return previousTotal.compareTo(boundary) < 0 && newTotal.compareTo(boundary) >= 0;
    }
}
