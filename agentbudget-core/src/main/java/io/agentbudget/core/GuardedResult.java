package io.agentbudget.core;

import java.util.Objects;

/**
 * The output of a call made through {@link BudgetGuard#wrap}, paired with the token usage
 * it consumed so the guard can price and record it.
 */
public record GuardedResult<T>(T output, TokenUsage usage) {

    public GuardedResult {
        Objects.requireNonNull(usage, "usage");
    }

    public static <T> GuardedResult<T> of(T output, TokenUsage usage) {
        return new GuardedResult<>(output, usage);
    }
}
