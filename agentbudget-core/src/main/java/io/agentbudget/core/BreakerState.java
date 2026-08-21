package io.agentbudget.core;

/**
 * Whether a session's {@link CallRateBreaker} is currently letting calls through.
 */
public enum BreakerState {

    /** Calls are flowing. The session is under its configured rate. */
    CLOSED,

    /** The session exceeded its rate and calls are being rejected until the cool-off elapses. */
    OPEN
}
