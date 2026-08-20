package io.agentbudget.core;

/**
 * What a {@link BudgetGuard} does once a session has reached its limit. {@code WARN} and
 * {@code SWITCH_MODEL} land in a later slice — {@code STOP} is the only policy the walking
 * skeleton needs to enforce.
 */
public enum ExceedPolicy {
    STOP
}
