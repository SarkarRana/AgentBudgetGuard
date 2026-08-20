package io.agentbudget.core;

import java.util.Objects;
import java.util.UUID;

/**
 * The library's entry point. Wraps a call to any LLM client, prices what it consumed against a
 * {@link PricingCatalog}, and enforces a spend limit for the session.
 */
public final class BudgetGuard {

    private final String sessionId;
    private final Money limit;
    private final ExceedPolicy policy;
    private final PricingCatalog pricingCatalog;
    private final UsageLedger ledger;
    private final ExceedPolicyEngine policyEngine;

    private BudgetGuard(String sessionId, Money limit, ExceedPolicy policy, PricingCatalog pricingCatalog) {
        this.sessionId = sessionId;
        this.limit = limit;
        this.policy = policy;
        this.pricingCatalog = pricingCatalog;
        this.ledger = new UsageLedger(limit.currency());
        this.policyEngine = new ExceedPolicyEngine();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String sessionId() {
        return sessionId;
    }

    public Money limit() {
        return limit;
    }

    public Money spend() {
        return ledger.total();
    }

    public Money remaining() {
        Money spent = ledger.total();
        Money left = limit.minus(spent);
        return left.isNegative() ? Money.zero(limit.currency()) : left;
    }

    /**
     * Runs {@code call} against this session's budget. Throws {@link BudgetExceededException}
     * before invoking {@code call} if the session has already reached its limit.
     */
    public <T> T wrap(String model, GuardedCall<T> call) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(call, "call");

        Money currentSpend = ledger.total();
        if (policyEngine.evaluate(currentSpend, limit, policy) == PolicyDecision.STOP) {
            throw new BudgetExceededException(sessionId, limit, currentSpend);
        }

        try {
            GuardedResult<T> result = call.call();
            Money cost = pricingCatalog.price(model, result.usage());
            ledger.record(cost);
            return result.output();
        } catch (PartialUsageException e) {
            Money cost = pricingCatalog.price(model, e.usage());
            ledger.record(cost);
            throw e;
        }
    }

    public static final class Builder {
        private Money limit;
        private ExceedPolicy policy = ExceedPolicy.STOP;
        private PricingCatalog pricingCatalog;
        private String sessionId;

        public Builder limit(Money limit) {
            this.limit = limit;
            return this;
        }

        public Builder onExceed(ExceedPolicy policy) {
            this.policy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        public Builder pricingCatalog(PricingCatalog pricingCatalog) {
            this.pricingCatalog = pricingCatalog;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public BudgetGuard build() {
            if (limit == null) {
                throw new IllegalArgumentException("limit must be set");
            }
            if (limit.amount().signum() <= 0) {
                throw new IllegalArgumentException("limit must be a positive amount, got " + limit);
            }
            if (pricingCatalog == null) {
                throw new IllegalArgumentException("pricingCatalog must be set");
            }
            String resolvedSessionId = sessionId != null ? sessionId : UUID.randomUUID().toString();
            return new BudgetGuard(resolvedSessionId, limit, policy, pricingCatalog);
        }
    }
}
