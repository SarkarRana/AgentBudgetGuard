package io.agentbudget.spring;

import io.agentbudget.core.AgentBudgetException;

/**
 * Thrown when a {@link SessionIdResolver} cannot work out which session an intercepted call
 * belongs to.
 *
 * <p>The call is refused rather than allowed. Charging an unknown session to some shared default
 * would silently pool every user's spend into one budget — which looks like it works right up
 * until one user exhausts everyone else's allowance.
 */
public final class SessionIdUnresolvableException extends AgentBudgetException {

    public SessionIdUnresolvableException(String message) {
        super(message);
    }
}
