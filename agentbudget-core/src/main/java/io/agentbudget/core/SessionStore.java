package io.agentbudget.core;

import java.util.function.Supplier;

/**
 * Holds per-session {@link UsageLedger} state behind a swappable interface. The in-memory
 * default ships with this library; a persistent store (e.g. JDBC-backed) can implement this
 * interface later without any change to {@link BudgetGuard} or calling code.
 */
public interface SessionStore {

    /**
     * Returns the ledger for {@code sessionId}, creating one with {@code ledgerFactory} if this
     * is the session's first call. Implementations must make get-or-create atomic: two threads
     * racing on the same new session id must observe the same ledger instance.
     */
    UsageLedger ledgerFor(String sessionId, Supplier<UsageLedger> ledgerFactory);
}
