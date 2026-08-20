package io.agentbudget.core;

/**
 * Base type for every exception this library throws. Catch this to handle the library's
 * failures as a family without enumerating each subtype.
 */
public abstract class AgentBudgetException extends RuntimeException {

    protected AgentBudgetException(String message) {
        super(message);
    }

    protected AgentBudgetException(String message, Throwable cause) {
        super(message, cause);
    }
}
