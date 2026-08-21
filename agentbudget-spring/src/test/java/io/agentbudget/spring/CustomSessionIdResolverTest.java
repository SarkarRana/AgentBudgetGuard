package io.agentbudget.spring;

import io.agentbudget.core.BudgetGuard;
import io.agentbudget.core.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A custom strategy replaces the default by existing — one bean, no other configuration.
 */
@SpringJUnitConfig(classes = {
        BudgetedConfiguration.class,
        BudgetedTestSupport.GuardConfig.class,
        DefaultSummariserService.class,
        CustomSessionIdResolverTest.CustomResolverConfig.class})
class CustomSessionIdResolverTest {

    @Autowired
    private SummariserService service;

    @Autowired
    private BudgetGuard guard;

    @Autowired
    private SessionIdResolver resolver;

    @Configuration(proxyBeanMethods = false)
    static class CustomResolverConfig {

        /**
         * Charges everything to one tenant, derived from the method name — nonsense as a policy,
         * but it proves the resolver in use is this one and not the default, since the default
         * would have read the @SessionId parameter instead.
         */
        @Bean
        SessionIdResolver tenantResolver() {
            return invocation -> "resolved-by-" + invocation.method().getName();
        }
    }

    @Test
    void theCustomResolverIsTheOneInUse() {
        assertThat(resolver).isNotInstanceOf(DefaultSessionIdResolver.class);
    }

    @Test
    void itDecidesWhichSessionIsCharged() {
        // the @SessionId parameter says "tenant-a"; the custom resolver overrules it
        service.summarise("tenant-a", 3);

        assertThat(guard.spend("resolved-by-summarise")).isEqualTo(Money.of("3.00", "USD"));
        assertThat(guard.spend("tenant-a")).isEqualTo(Money.zero(guard.limit().currency()));
    }

    @Test
    void itAppliesToMethodsTheDefaultCouldNotHaveResolvedAtAll() {
        // no @SessionId parameter and no session literal: the default would have refused
        service.summariseWithoutSession(2);

        assertThat(guard.spend("resolved-by-summariseWithoutSession")).isEqualTo(Money.of("2.00", "USD"));
    }
}
