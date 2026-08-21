package io.agentbudget.springboot;

import io.agentbudget.core.BudgetGuard;
import io.agentbudget.core.BuiltInPricingCatalog;
import io.agentbudget.core.Money;
import io.agentbudget.core.PricingCatalog;
import io.agentbudget.core.StaticPricingCatalog;
import io.agentbudget.spring.BudgetedAdvisor;
import io.agentbudget.spring.BudgetedMethodInterceptor;
import io.agentbudget.spring.DefaultSessionIdResolver;
import io.agentbudget.spring.SessionIdResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registers a default {@code BudgetGuard} and everything {@code @Budgeted} needs when the
 * application supplies nothing of its own.
 */
class AutoConfigDefaultsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AgentBudgetAutoConfiguration.class, BudgetedSupportAutoConfiguration.class));

    @Test
    void withNoLimitConfiguredNoDefaultGuardIsCreated() {
        runner.run(context -> assertThat(context).doesNotHaveBean(BudgetGuard.class));
    }

    @Test
    void registersADefaultGuardFromProperties() {
        runner.withPropertyValues("agentbudget.limit=$5.00")
                .run(context -> {
                    assertThat(context).hasSingleBean(BudgetGuard.class);
                    BudgetGuard guard = context.getBean(BudgetGuard.class);
                    assertThat(guard.limit()).isEqualTo(Money.of("5.00", "USD"));
                });
    }

    @Test
    void registersABuiltInPricingCatalogByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(PricingCatalog.class));
    }

    @Test
    void registersEverythingBudgetedNeeds() {
        runner.withPropertyValues("agentbudget.limit=$5.00")
                .run((AssertableApplicationContext context) -> {
                    assertThat(context).hasSingleBean(SessionIdResolver.class);
                    assertThat(context).hasSingleBean(BudgetedMethodInterceptor.class);
                    assertThat(context).hasSingleBean(BudgetedAdvisor.class);
                    assertThat(context.getBean(SessionIdResolver.class))
                            .isInstanceOf(WindowAwareSessionIdResolver.class);
                });
    }

    @Test
    void backsOffOnAUserDefinedBudgetGuard() {
        runner.withUserConfiguration(CustomGuardConfig.class)
                .withPropertyValues("agentbudget.limit=$5.00") // present, but must be ignored
                .run(context -> {
                    assertThat(context).hasSingleBean(BudgetGuard.class);
                    assertThat(context.getBean(BudgetGuard.class).limit()).isEqualTo(Money.of("99.00", "USD"));
                });
    }

    @Test
    void backsOffOnAUserDefinedPricingCatalog() {
        runner.withUserConfiguration(CustomPricingConfig.class)
                .run(context -> assertThat(context.getBean(PricingCatalog.class))
                        .isSameAs(CustomPricingConfig.CATALOG));
    }

    @Test
    void backsOffOnAUserDefinedSessionIdResolver() {
        runner.withUserConfiguration(CustomResolverConfig.class)
                .withPropertyValues("agentbudget.limit=$5.00")
                .run(context -> {
                    assertThat(context).hasSingleBean(SessionIdResolver.class);
                    assertThat(context.getBean(SessionIdResolver.class))
                            .isNotInstanceOf(WindowAwareSessionIdResolver.class)
                            .isNotInstanceOf(DefaultSessionIdResolver.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomGuardConfig {
        @Bean
        BudgetGuard budgetGuard(PricingCatalog catalog) {
            return BudgetGuard.builder()
                    .limit(Money.of("99.00", "USD"))
                    .pricingCatalog(catalog)
                    .build();
        }

        @Bean
        PricingCatalog pricingCatalog() {
            return BuiltInPricingCatalog.catalog();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomPricingConfig {
        static final PricingCatalog CATALOG = StaticPricingCatalog.builder().build();

        @Bean
        PricingCatalog pricingCatalog() {
            return CATALOG;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomResolverConfig {
        @Bean
        SessionIdResolver sessionIdResolver() {
            return invocation -> "always-this-session";
        }
    }

}
