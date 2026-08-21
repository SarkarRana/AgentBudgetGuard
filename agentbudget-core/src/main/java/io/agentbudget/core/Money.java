package io.agentbudget.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Currency;
import java.util.Objects;

/**
 * An exact decimal amount in a specific currency. All arithmetic is performed with
 * {@link BigDecimal}; nothing in this library represents money as a floating point value.
 */
public final class Money implements Comparable<Money> {

    private final BigDecimal amount;
    private final Currency currency;

    private Money(BigDecimal amount, Currency currency) {
        this.amount = Objects.requireNonNull(amount, "amount");
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money of(double amount, String currencyCode) {
        return new Money(BigDecimal.valueOf(amount), Currency.getInstance(currencyCode));
    }

    /**
     * Parses a human-written amount — the form a limit takes in a configuration file or an
     * annotation attribute.
     *
     * <p>Accepts a leading currency symbol ({@code "$2.00"}), an ISO code on either side
     * ({@code "USD 2.00"}, {@code "2.00 USD"}), or a bare amount, which is read as USD. Grouping
     * separators are tolerated: {@code "$1,250.00"} parses.
     *
     * @throws IllegalArgumentException with the offending text, on anything else
     */
    public static Money parse(String text) {
        Objects.requireNonNull(text, "text");
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Cannot parse a money amount from an empty string");
        }

        Matcher matcher = MONEY.matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Cannot parse '%s' as a money amount; expected something like \"$2.00\", \"USD 2.00\" or \"2.00\""
                            .formatted(text));
        }

        String symbol = matcher.group("symbol");
        String leadingCode = matcher.group("leadingCode");
        String trailingCode = matcher.group("trailingCode");
        if (leadingCode != null && trailingCode != null) {
            throw new IllegalArgumentException(
                    "Cannot parse '%s' as a money amount; it names a currency twice".formatted(text));
        }

        Currency currency = resolveCurrency(text, symbol, leadingCode != null ? leadingCode : trailingCode);
        BigDecimal amount = new BigDecimal(matcher.group("amount").replace(",", ""));
        return new Money(amount, currency);
    }

    private static Currency resolveCurrency(String text, String symbol, String code) {
        if (symbol != null) {
            Currency bySymbol = SYMBOLS.get(symbol);
            if (bySymbol == null) {
                throw new IllegalArgumentException(
                        "Cannot parse '%s' as a money amount; '%s' is not a currency symbol this library knows. "
                                .formatted(text, symbol) + "Use an ISO code instead, such as \"2.00 USD\"");
            }
            return bySymbol;
        }
        if (code == null) {
            return DEFAULT_CURRENCY;
        }
        try {
            return Currency.getInstance(code.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Cannot parse '%s' as a money amount; '%s' is not an ISO currency code".formatted(text, code), e);
        }
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    private static final Currency DEFAULT_CURRENCY = Currency.getInstance("USD");

    private static final Pattern MONEY = Pattern.compile(
            "^(?:(?<symbol>[^\\p{Alnum}\\s.,+-])\\s*|(?<leadingCode>[A-Za-z]{3})\\s+)?"
                    + "(?<amount>[-+]?[0-9][0-9,]*(?:\\.[0-9]+)?)"
                    + "(?:\\s*(?<trailingCode>[A-Za-z]{3}))?$");

    /**
     * The handful of symbols worth recognising. Deliberately short: a symbol is ambiguous across
     * currencies ({@code $} alone is used by a dozen of them), so anything beyond the obvious
     * cases should be written as an ISO code rather than guessed at here.
     */
    private static final Map<String, Currency> SYMBOLS = new LinkedHashMap<>();

    static {
        SYMBOLS.put("$", Currency.getInstance("USD"));
        SYMBOLS.put("€", Currency.getInstance("EUR"));
        SYMBOLS.put("£", Currency.getInstance("GBP"));
        SYMBOLS.put("¥", Currency.getInstance("JPY"));
    }

    public BigDecimal amount() {
        return amount;
    }

    public Currency currency() {
        return currency;
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money multiply(BigDecimal factor) {
        return new Money(amount.multiply(factor), currency);
    }

    /**
     * This amount rounded to its currency's usual number of decimal places, half up.
     *
     * <p>The only rounding in the library, and it exists for presentation. Totals accumulate at
     * full {@link BigDecimal} precision — rounding each call's cost before adding it would drift
     * a long session's total away from what the provider actually charged, in a direction nobody
     * can predict.
     */
    public Money roundedForDisplay() {
        int scale = Math.max(currency.getDefaultFractionDigits(), 0);
        return new Money(amount.setScale(scale, RoundingMode.HALF_UP), currency);
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + currency.getCurrencyCode() + " vs " + other.currency.getCurrencyCode());
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money other)) {
            return false;
        }
        return currency.equals(other.currency) && amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
