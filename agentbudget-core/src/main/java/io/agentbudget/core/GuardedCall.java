package io.agentbudget.core;

/**
 * A call to an LLM client made through {@link BudgetGuard#wrap}. Implementations return the
 * call's output together with the tokens it consumed.
 *
 * <p>If the call fails after tokens were generated, throw {@link PartialUsageException} with
 * the usage consumed so far so the ledger still records it. Any other exception is treated as a
 * failure that never reached the provider, and nothing is recorded.
 */
@FunctionalInterface
public interface GuardedCall<T> {

    GuardedResult<T> call();
}
