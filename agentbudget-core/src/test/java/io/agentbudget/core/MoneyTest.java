package io.agentbudget.core;

import org.junit.jupiter.api.Test;

import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void addsExactlyAcrossManyFractionalCalls() {
        Money total = Money.zero(USD);
        for (int i = 0; i < 10; i++) {
            total = total.plus(Money.of("0.1", "USD"));
        }
        assertThat(total).isEqualTo(Money.of("1.0", "USD"));
    }

    @Test
    void subtractsExactly() {
        Money result = Money.of("5.00", "USD").minus(Money.of("1.23", "USD"));
        assertThat(result).isEqualTo(Money.of("3.77", "USD"));
    }

    @Test
    void rejectsArithmeticAcrossDifferentCurrencies() {
        Money usd = Money.of("1.00", "USD");
        Money eur = Money.of("1.00", "EUR");
        assertThatThrownBy(() -> usd.plus(eur)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalityIgnoresTrailingZeroScale() {
        assertThat(Money.of("1.50", "USD")).isEqualTo(Money.of("1.5000", "USD"));
    }

    @Test
    void comparesByAmountWithinTheSameCurrency() {
        assertThat(Money.of("2.00", "USD")).isGreaterThan(Money.of("1.00", "USD"));
    }
}
