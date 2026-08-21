package io.agentbudget.spring;

import io.agentbudget.core.BudgetGuard;
import io.agentbudget.core.Money;

import java.util.Currency;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands out a {@link BudgetGuard} per distinct {@link Budgeted#limit()}, all derived from the
 * application's own guard and therefore all sharing its session store.
 *
 * <p>Sharing the store is the point. A guard's limit is fixed when it is built, so per-method
 * limits need a guard each — but they must charge the same session ledgers, or two annotated
 * methods called in one session would each track their own separate spend and neither would see
 * the whole picture. Derived this way, one session has one running total and each method enforces
 * its own ceiling against it.
 */
public final class BudgetGuardRegistry {

    private final BudgetGuard defaultGuard;
    private final Map<String, BudgetGuard> byLimit = new ConcurrentHashMap<>();

    public BudgetGuardRegistry(BudgetGuard defaultGuard) {
        this.defaultGuard = Objects.requireNonNull(defaultGuard, "defaultGuard");
    }

    /**
     * The guard enforcing {@code limit}, or the application's own guard when the annotation did
     * not set one.
     *
     * @throws IllegalArgumentException naming the offending text, if {@code limit} is malformed
     */
    public BudgetGuard guardFor(String limit) {
        if (limit == null || limit.isBlank()) {
            return defaultGuard;
        }
        return byLimit.computeIfAbsent(limit.trim(), this::deriveGuard);
    }

    public BudgetGuard defaultGuard() {
        return defaultGuard;
    }

    private BudgetGuard deriveGuard(String limit) {
        Money parsed;
        try {
            parsed = Money.parse(limit);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "@Budgeted(limit = \"%s\") is not a valid amount. %s".formatted(limit, e.getMessage()), e);
        }

        // Derived guards share the default guard's session store, so they share its ledgers - and
        // a ledger holds one currency. A limit in another currency would either be compared
        // against a total it cannot be compared with, or quietly create a ledger in the wrong
        // currency for a session that had not been seen yet. Neither is worth allowing: the first
        // is an error the fail-open mode would swallow, and the second under-meters silently.
        Currency expected = defaultGuard.limit().currency();
        if (!parsed.currency().equals(expected)) {
            throw new IllegalArgumentException(
                    ("@Budgeted(limit = \"%s\") is in %s, but this application's budget is tracked in %s. "
                            + "A session has one running total in one currency, so every limit applied to it must "
                            + "use that currency.")
                            .formatted(limit, parsed.currency().getCurrencyCode(), expected.getCurrencyCode()));
        }
        return defaultGuard.toBuilder().limit(parsed).build();
    }
}
