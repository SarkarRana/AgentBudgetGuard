package io.agentbudget.core;

import java.util.Objects;

/**
 * A pure function from spend, limit, and configured policy to a decision. No state, no side
 * effects — callbacks and logging belong to the facade, not here.
 *
 * <p>The boundary is inclusive: a session whose spend has reached its limit exactly has no budget
 * left, so it is treated as breached rather than as having one last call in hand.
 */
public final class ExceedPolicyEngine {

    public PolicyDecision evaluate(Money currentSpend, Money limit, ExceedPolicy policy) {
        return evaluate(currentSpend, limit, null, policy);
    }

    /**
     * As above, with the ceiling at which {@link ExceedPolicy#SWITCH_MODEL} gives up and stops.
     *
     * <p>The hard limit is what keeps degradation from being an unbounded licence to keep
     * spending: a cheaper model is still not a free one, so a session that exhausts its budget at
     * the fallback's rate stops there.
     *
     * @param hardLimit the ceiling above which even the fallback is refused; only consulted for
     *                  {@code SWITCH_MODEL}
     */
    public PolicyDecision evaluate(Money currentSpend, Money limit, Money hardLimit, ExceedPolicy policy) {
        Objects.requireNonNull(currentSpend, "currentSpend");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(policy, "policy");

        boolean atOrOverLimit = currentSpend.compareTo(limit) >= 0;
        if (!atOrOverLimit) {
            return PolicyDecision.ALLOW;
        }

        return switch (policy) {
            case STOP -> PolicyDecision.STOP;
            case WARN -> PolicyDecision.WARN;
            case SWITCH_MODEL -> hardLimit != null && currentSpend.compareTo(hardLimit) >= 0
                    ? PolicyDecision.STOP
                    : PolicyDecision.SWITCH;
        };
    }
}
