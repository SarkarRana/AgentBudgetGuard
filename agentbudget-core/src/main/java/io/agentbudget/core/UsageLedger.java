package io.agentbudget.core;

import java.util.Currency;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Accumulates priced usage into a running session total. Recording is a compare-and-swap loop
 * over an immutable {@link Money}, so concurrent recording against one session never loses or
 * double-counts an update.
 */
public final class UsageLedger {

    private final AtomicReference<Money> total;

    public UsageLedger(Currency currency) {
        this.total = new AtomicReference<>(Money.zero(currency));
    }

    /**
     * Adds {@code cost} to the running total and returns the new total.
     */
    public Money record(Money cost) {
        return total.updateAndGet(current -> current.plus(cost));
    }

    public Money total() {
        return total.get();
    }
}
