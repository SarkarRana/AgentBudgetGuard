package io.agentbudget.spring;

import io.agentbudget.core.BudgetExceededException;
import io.agentbudget.core.BudgetGuard;
import io.agentbudget.core.ExceedPolicy;
import io.agentbudget.core.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The annotation's limit attribute: readable strings in, and a failure that names the offending
 * text when it is not one.
 */
@SpringJUnitConfig(classes = {
        BudgetedConfiguration.class,
        BudgetedTestSupport.GuardConfig.class,
        BudgetedLimitParsingTest.LimitService.class})
class BudgetedLimitParsingTest {

    interface Limits {
        String dollars(String sessionId);

        String euros(String sessionId);

        String inherited(String sessionId);

        String malformed(String sessionId);
    }

    @Service
    static class LimitService implements Limits {

        @Budgeted(limit = "$3.00", model = BudgetedTestSupport.MODEL)
        @Override
        public String dollars(@SessionId String sessionId) {
            BudgetedUsage.report(3, 0);
            return "ok";
        }

        @Budgeted(limit = "1.50 EUR", model = BudgetedTestSupport.MODEL)
        @Override
        public String euros(@SessionId String sessionId) {
            return "ok";
        }

        /** No limit attribute, so the application's own guard bean decides. */
        @Budgeted(model = BudgetedTestSupport.MODEL)
        @Override
        public String inherited(@SessionId String sessionId) {
            BudgetedUsage.report(10, 0);
            return "ok";
        }

        @Budgeted(limit = "about three quid", model = BudgetedTestSupport.MODEL)
        @Override
        public String malformed(@SessionId String sessionId) {
            return "never reached";
        }
    }

    @Autowired
    private Limits limits;

    @Autowired
    private BudgetGuard guard;

    @Test
    void parsesADollarAmount() {
        limits.dollars("tenant-a"); // spends exactly the $3.00 limit

        assertThatThrownBy(() -> limits.dollars("tenant-a"))
                .isInstanceOf(BudgetExceededException.class)
                .satisfies(e -> assertThat(((BudgetExceededException) e).limit())
                        .isEqualTo(Money.of("3.00", "USD")));
    }

    @Test
    void anAbsentLimitFallsBackToTheApplicationsOwnGuard() {
        limits.inherited("tenant-b"); // the guard bean's limit is $10.00

        assertThatThrownBy(() -> limits.inherited("tenant-b"))
                .isInstanceOf(BudgetExceededException.class)
                .satisfies(e -> assertThat(((BudgetExceededException) e).limit())
                        .isEqualTo(Money.of("10.00", "USD")));
    }

    @Test
    void aMalformedLimitFailsWithTheOffendingTextRatherThanSilently() {
        assertThatThrownBy(() -> limits.malformed("tenant-c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("about three quid")
                .hasMessageContaining("@Budgeted(limit");
    }

    @Test
    void aLimitInAnotherCurrencyFailsClearlyRatherThanMixingUnits() {
        // the guard's ledger is in USD; a EUR limit cannot be enforced against it, and saying so
        // beats quietly comparing 1.50 EUR to a dollar total
        assertThatThrownBy(() -> limits.euros("tenant-d"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EUR")
                .hasMessageContaining("USD")
                .hasMessageContaining("one running total in one currency");
    }
}
