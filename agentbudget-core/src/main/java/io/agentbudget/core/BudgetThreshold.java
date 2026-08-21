package io.agentbudget.core;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * An early-warning line below the hard limit — "tell me at eighty percent", or "tell me at eight
 * dollars". Expressed either way, because which one is natural depends on whether the limit is
 * itself configured per environment.
 *
 * <p>A fractional threshold resolves against whatever limit the guard is built with, so it stays
 * correct when the limit is tuned; an absolute one is fixed regardless of the limit.
 */
public sealed interface BudgetThreshold {

    /**
     * The spend at which this threshold is crossed, for a guard limited to {@code limit}.
     */
    Money resolveAgainst(Money limit);

    /**
     * A threshold at a fraction of the limit — {@code of(0.8)} for eighty percent.
     *
     * @throws IllegalArgumentException unless the fraction is above zero and at most one
     */
    static BudgetThreshold ofFraction(double fraction) {
        return new Fraction(BigDecimal.valueOf(fraction));
    }

    /**
     * A threshold at a fixed amount, regardless of the limit.
     */
    static BudgetThreshold ofAmount(Money amount) {
        return new Amount(amount);
    }

    record Fraction(BigDecimal fraction) implements BudgetThreshold {

        public Fraction {
            Objects.requireNonNull(fraction, "fraction");
            if (fraction.signum() <= 0 || fraction.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException(
                        "threshold fraction must be above 0 and at most 1, got " + fraction);
            }
        }

        @Override
        public Money resolveAgainst(Money limit) {
            Objects.requireNonNull(limit, "limit");
            return limit.multiply(fraction);
        }
    }

    record Amount(Money amount) implements BudgetThreshold {

        public Amount {
            Objects.requireNonNull(amount, "amount");
            if (amount.amount().signum() <= 0) {
                throw new IllegalArgumentException("threshold amount must be positive, got " + amount);
            }
        }

        @Override
        public Money resolveAgainst(Money limit) {
            Objects.requireNonNull(limit, "limit");
            if (!amount.currency().equals(limit.currency())) {
                throw new IllegalArgumentException("Threshold currency %s does not match the limit's %s"
                        .formatted(amount.currency().getCurrencyCode(), limit.currency().getCurrencyCode()));
            }
            return amount;
        }
    }
}
