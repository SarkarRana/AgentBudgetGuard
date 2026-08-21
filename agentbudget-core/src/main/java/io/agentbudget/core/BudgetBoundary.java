package io.agentbudget.core;

/**
 * Which line a session's spend crossed.
 */
public enum BudgetBoundary {

    /** The early-warning line below the limit, configured with {@link BudgetThreshold}. */
    WARNING_THRESHOLD,

    /** The hard limit itself. */
    LIMIT
}
