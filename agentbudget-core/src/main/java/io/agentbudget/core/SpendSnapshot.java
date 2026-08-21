package io.agentbudget.core;

import java.util.Collections;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable view of one session's spend, taken at an instant. Safe to hand to another thread,
 * serialise onto an admin endpoint, or hold onto while the session keeps spending — nothing here
 * changes underneath the holder.
 *
 * <p>Every amount is a {@link Money} carrying its currency explicitly, and none of them is
 * rounded: totals accumulate at full precision and rounding happens only where a figure is
 * displayed, via {@link Money#roundedForDisplay()}.
 *
 * @param sessionId the session described
 * @param total     everything the session has spent
 * @param limit     the budget it is spending against
 * @param remaining what is left, floored at zero
 * @param perModel  spend broken down by model identifier, covering the session's whole history,
 *                  in the order the models were first used
 * @param calls     the individual calls, oldest first, bounded by the session's remembered
 *                  window — see {@link UsageLedger#DEFAULT_RECORDED_CALL_HISTORY}
 */
public record SpendSnapshot(String sessionId,
                            Money total,
                            Money limit,
                            Money remaining,
                            Map<String, Money> perModel,
                            List<CallRecord> calls) {

    public SpendSnapshot {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(remaining, "remaining");
        // a defensive copy inside an unmodifiable wrapper rather than Map.copyOf, which is
        // immutable but drops iteration order — and a breakdown meant to show which step of an
        // agent is expensive reads far better in the order the steps actually ran.
        perModel = Collections.unmodifiableMap(new LinkedHashMap<>(perModel));
        calls = List.copyOf(calls);
    }

    public Currency currency() {
        return total.currency();
    }

    /**
     * Spend attributed to {@code model}, or zero in this session's currency if it never ran.
     */
    public Money spentOn(String model) {
        Objects.requireNonNull(model, "model");
        return perModel.getOrDefault(model, Money.zero(currency()));
    }

    /**
     * How much of the limit has been consumed, as a fraction. Zero when the limit is zero.
     */
    public double fractionUsed() {
        if (limit.isZero()) {
            return 0d;
        }
        return total.amount().doubleValue() / limit.amount().doubleValue();
    }
}
