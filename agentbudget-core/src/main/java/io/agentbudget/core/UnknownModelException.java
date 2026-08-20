package io.agentbudget.core;

/**
 * Thrown by a {@link PricingCatalog} when asked to price a model it has no rate for.
 */
public final class UnknownModelException extends AgentBudgetException {

    private final String model;

    public UnknownModelException(String model) {
        super("No pricing registered for model '" + model + "'");
        this.model = model;
    }

    public String model() {
        return model;
    }
}
