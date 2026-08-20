package io.agentbudget.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExceedPolicyEngineTest {

    private final ExceedPolicyEngine engine = new ExceedPolicyEngine();

    @Test
    void allowsUnderTheLimit() {
        PolicyDecision decision = engine.evaluate(Money.of("4.99", "USD"), Money.of("5.00", "USD"), ExceedPolicy.STOP);
        assertThat(decision).isEqualTo(PolicyDecision.ALLOW);
    }

    @Test
    void stopsExactlyAtTheLimit() {
        PolicyDecision decision = engine.evaluate(Money.of("5.00", "USD"), Money.of("5.00", "USD"), ExceedPolicy.STOP);
        assertThat(decision).isEqualTo(PolicyDecision.STOP);
    }

    @Test
    void stopsOverTheLimit() {
        PolicyDecision decision = engine.evaluate(Money.of("5.01", "USD"), Money.of("5.00", "USD"), ExceedPolicy.STOP);
        assertThat(decision).isEqualTo(PolicyDecision.STOP);
    }

    @Test
    void allowsAtZeroSpendAgainstAnyPositiveLimit() {
        PolicyDecision decision = engine.evaluate(Money.zero(java.util.Currency.getInstance("USD")), Money.of("0.01", "USD"), ExceedPolicy.STOP);
        assertThat(decision).isEqualTo(PolicyDecision.ALLOW);
    }
}
