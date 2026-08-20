package io.agentbudget.core;

import java.util.Objects;

/**
 * A pure function from spend, limit, and configured policy to a decision. No state, no side
 * effects — callbacks and logging belong to the facade, not here.
 */
public final class ExceedPolicyEngine {

    public PolicyDecision evaluate(Money currentSpend, Money limit, ExceedPolicy policy) {
        Objects.requireNonNull(currentSpend, "currentSpend");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(policy, "policy");

        boolean atOrOverLimit = currentSpend.compareTo(limit) >= 0;

        return switch (policy) {
            case STOP -> atOrOverLimit ? PolicyDecision.STOP : PolicyDecision.ALLOW;
        };
    }
}
