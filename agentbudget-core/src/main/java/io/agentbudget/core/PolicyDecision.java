package io.agentbudget.core;

/**
 * What {@link ExceedPolicyEngine} decides to do about a call, given a session's current spend.
 */
public enum PolicyDecision {

    /** Under the limit. Dispatch it. */
    ALLOW,

    /** Over the limit, but the configured policy lets it through. Dispatch it, and say so. */
    WARN,

    /** Over the limit. Send it to the nominated fallback model instead of refusing it. */
    SWITCH,

    /** Over the limit. Refuse it before dispatch. */
    STOP;

    /** Whether the call goes out. */
    public boolean isAllowed() {
        return this != STOP;
    }
}
