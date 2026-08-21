package io.agentbudget.spring;

import io.agentbudget.core.BudgetGuard;
import io.agentbudget.core.ExceedPolicy;
import io.agentbudget.core.Money;
import io.agentbudget.core.ModelPricing;
import io.agentbudget.core.PricingCatalog;
import io.agentbudget.core.StaticPricingCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared wiring for the context tests. $1 per token, so a call of {@code n} tokens costs
 * {@code $n} and the assertions read straight off the token counts.
 */
final class BudgetedTestSupport {

    static final String MODEL = "fake-model";

    private BudgetedTestSupport() {
    }

    static PricingCatalog catalog() {
        return StaticPricingCatalog.withSingleModel(MODEL, ModelPricing.perMillionTokens("USD", 1_000_000, 1_000_000));
    }

    @Configuration(proxyBeanMethods = false)
    static class GuardConfig {

        @Bean
        BudgetGuard budgetGuard() {
            return BudgetGuard.builder()
                    .limit(Money.of("10.00", "USD"))
                    .onExceed(ExceedPolicy.STOP)
                    .pricingCatalog(catalog())
                    .build();
        }
    }
}
