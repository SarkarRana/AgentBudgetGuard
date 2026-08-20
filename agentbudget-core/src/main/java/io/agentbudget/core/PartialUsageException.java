package io.agentbudget.core;

import java.util.Objects;

/**
 * Thrown from inside a {@link GuardedCall} to signal that the call failed after the provider
 * had already generated tokens. The guard prices and records {@link #usage()} before the
 * failure propagates, so a call that fails mid-generation is never charged as free.
 */
public final class PartialUsageException extends AgentBudgetException {

    private final TokenUsage usage;

    public PartialUsageException(TokenUsage usage, Throwable cause) {
        super("Call failed after generating " + usage, cause);
        this.usage = Objects.requireNonNull(usage, "usage");
    }

    public TokenUsage usage() {
        return usage;
    }
}
