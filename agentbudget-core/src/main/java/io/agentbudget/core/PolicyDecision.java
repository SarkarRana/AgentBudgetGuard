package io.agentbudget.core;

/**
 * What {@link ExceedPolicyEngine} decides to do about a call, given a session's current spend.
 */
public enum PolicyDecision {
    ALLOW,
    STOP
}
