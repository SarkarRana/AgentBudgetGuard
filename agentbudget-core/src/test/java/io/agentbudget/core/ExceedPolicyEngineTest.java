package io.agentbudget.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The policy engine is a pure function, so every policy is exercised at the three places that
 * matter: just under the limit, exactly on it, and just over it.
 */
class ExceedPolicyEngineTest {

    private static final Money LIMIT = Money.of("10.00", "USD");

    private final ExceedPolicyEngine engine = new ExceedPolicyEngine();

    private PolicyDecision decisionAt(String spend, ExceedPolicy policy) {
        return engine.evaluate(Money.of(spend, "USD"), LIMIT, policy);
    }

    @ParameterizedTest
    @EnumSource(ExceedPolicy.class)
    void everyPolicyAllowsSpendJustUnderTheLimit(ExceedPolicy policy) {
        assertThat(decisionAt("9.99", policy)).isEqualTo(PolicyDecision.ALLOW);
        assertThat(decisionAt("0.00", policy)).isEqualTo(PolicyDecision.ALLOW);
    }

    @ParameterizedTest
    @EnumSource(ExceedPolicy.class)
    void noPolicyStillAllowsNormallyOnceTheLimitIsReached(ExceedPolicy policy) {
        // reaching the limit exactly means there is nothing left, so it is a breach either way —
        // what differs is only what each policy does about it
        assertThat(decisionAt("10.00", policy)).isNotEqualTo(PolicyDecision.ALLOW);
        assertThat(decisionAt("10.01", policy)).isNotEqualTo(PolicyDecision.ALLOW);
    }

    @Nested
    class UnderStop {

        @Test
        void allowsJustUnderTheLimit() {
            assertThat(decisionAt("9.99", ExceedPolicy.STOP)).isEqualTo(PolicyDecision.ALLOW);
        }

        @Test
        void stopsExactlyOnTheLimit() {
            assertThat(decisionAt("10.00", ExceedPolicy.STOP)).isEqualTo(PolicyDecision.STOP);
        }

        @Test
        void stopsJustOverTheLimit() {
            assertThat(decisionAt("10.01", ExceedPolicy.STOP)).isEqualTo(PolicyDecision.STOP);
        }
    }

    @Nested
    class UnderWarn {

        @Test
        void allowsJustUnderTheLimit() {
            assertThat(decisionAt("9.99", ExceedPolicy.WARN)).isEqualTo(PolicyDecision.ALLOW);
        }

        @Test
        void warnsButProceedsExactlyOnTheLimit() {
            PolicyDecision decision = decisionAt("10.00", ExceedPolicy.WARN);
            assertThat(decision).isEqualTo(PolicyDecision.WARN);
            assertThat(decision.isAllowed()).isTrue();
        }

        @Test
        void warnsButProceedsJustOverTheLimit() {
            PolicyDecision decision = decisionAt("10.01", ExceedPolicy.WARN);
            assertThat(decision).isEqualTo(PolicyDecision.WARN);
            assertThat(decision.isAllowed()).isTrue();
        }
    }

    @Test
    void onlyStopBlocksTheCall() {
        assertThat(PolicyDecision.ALLOW.isAllowed()).isTrue();
        assertThat(PolicyDecision.WARN.isAllowed()).isTrue();
        assertThat(PolicyDecision.STOP.isAllowed()).isFalse();
    }

    @Test
    void rejectsMissingArguments() {
        assertThatThrownBy(() -> engine.evaluate(null, LIMIT, ExceedPolicy.STOP))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> engine.evaluate(Money.zero(LIMIT.currency()), null, ExceedPolicy.STOP))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> engine.evaluate(Money.zero(LIMIT.currency()), LIMIT, null))
                .isInstanceOf(NullPointerException.class);
    }
}
