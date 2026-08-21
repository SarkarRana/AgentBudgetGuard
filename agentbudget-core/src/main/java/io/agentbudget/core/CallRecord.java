package io.agentbudget.core;

import java.util.Objects;

/**
 * One priced provider attempt as the ledger holds it: which attempt, against which model, for
 * what tokens, at what cost. This is the row a per-call breakdown reports.
 *
 * @param callId the attempt this describes, unique within its session's remembered window
 * @param model  the model identifier the call was priced against
 * @param usage  the tokens the call consumed
 * @param cost   the priced cost, never rounded
 */
public record CallRecord(CallId callId, String model, TokenUsage usage, Money cost) {

    public CallRecord {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(cost, "cost");
    }
}
