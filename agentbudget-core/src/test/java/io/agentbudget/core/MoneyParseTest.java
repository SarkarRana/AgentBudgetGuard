package io.agentbudget.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing the human-written form a limit takes in an annotation attribute or a properties file.
 */
class MoneyParseTest {

    @ParameterizedTest
    @CsvSource({
            "$2.00,      2.00, USD",
            "$1250,      1250, USD",
            "'$1,250.00', 1250.00, USD",
            "2.00 USD,   2.00, USD",
            "USD 2.00,   2.00, USD",
            "usd 2.00,   2.00, USD",
            "2.00,       2.00, USD",
            "€1.50,      1.50, EUR",
            "£1.50,      1.50, GBP",
            "¥1200,      1200, JPY",
            "1200 JPY,   1200, JPY",
            "  $2.00  ,  2.00, USD",
    })
    void parsesTheFormsAHumanActuallyWrites(String text, String expectedAmount, String expectedCurrency) {
        assertThat(Money.parse(text)).isEqualTo(Money.of(expectedAmount, expectedCurrency));
    }

    @Test
    void readsABareAmountAsUsd() {
        assertThat(Money.parse("2.00").currency()).isEqualTo(java.util.Currency.getInstance("USD"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "two dollars", "$", "USD", "$2.00.00", "2,00 EUR extra", "£$2.00"})
    void rejectsWhatItCannotUnderstand(String text) {
        assertThatThrownBy(() -> Money.parse(text))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parse");
    }

    @Test
    void namesTheOffendingTextSoTheFailureIsActionable() {
        assertThatThrownBy(() -> Money.parse("about three quid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("about three quid")
                .hasMessageContaining("$2.00");
    }

    @Test
    void rejectsAnUnknownCurrencySymbolWithAUsefulSuggestion() {
        assertThatThrownBy(() -> Money.parse("₹200"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a currency symbol")
                .hasMessageContaining("ISO code");
    }

    @Test
    void rejectsAnUnknownIsoCode() {
        assertThatThrownBy(() -> Money.parse("2.00 ZZZ"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an ISO currency code");
    }

    @Test
    void rejectsAnAmountThatNamesItsCurrencyTwice() {
        assertThatThrownBy(() -> Money.parse("USD 2.00 EUR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names a currency twice");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> Money.parse(null)).isInstanceOf(NullPointerException.class);
    }
}
