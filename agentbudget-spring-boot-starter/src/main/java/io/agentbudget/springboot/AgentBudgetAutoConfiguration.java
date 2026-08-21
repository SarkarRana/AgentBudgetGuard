package io.agentbudget.springboot;

import io.agentbudget.core.BudgetGuard;
import io.agentbudget.core.BudgetThreshold;
import io.agentbudget.core.BuiltInPricingCatalog;
import io.agentbudget.core.CallRateBreaker;
import io.agentbudget.core.Money;
import io.agentbudget.core.PricingCatalog;
import io.agentbudget.spring.BudgetedConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Wires {@code agentbudget.*} properties into a default {@link BudgetGuard}, and everything
 * {@link BudgetedConfiguration} needs to make {@code @Budgeted} work, into one auto-configuration
 * a Boot application gets for free by adding this module as a dependency.
 *
 * <p>Every bean here is {@link ConditionalOnMissingBean}: define your own {@code BudgetGuard},
 * {@code PricingCatalog}, or {@code SessionIdResolver}, and this backs off in favour of it with
 * no other configuration. That includes the {@code agentbudget.limit} case — with no limit
 * configured and no guard bean of your own, no default {@code BudgetGuard} is created at all.
 *
 * <p>{@code @Budgeted}'s supporting beans — the interceptor, the advisor, the proxy creator —
 * come from {@link BudgetedConfiguration}, registered by {@link BudgetedSupportAutoConfiguration}
 * only once a {@code BudgetGuard} bean actually exists, whether that is the default one below or
 * the application's own. Without that guard, a Boot application that has this starter on its
 * classpath purely as a transitive dependency, and never configures a limit or uses
 * {@code @Budgeted} at all, must still start cleanly. That check is a separate, ordered
 * auto-configuration class rather than more conditions here, because {@code @ConditionalOnBean}
 * needs the bean definition it is checking for to already be registered when it runs — Boot
 * guarantees that ordering across auto-configuration classes sequenced with
 * {@code @AutoConfigureAfter}, but not between an outer configuration class and its own nested
 * ones, which are processed first.
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentBudgetProperties.class)
public class AgentBudgetAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PricingCatalog pricingCatalog() {
        return BuiltInPricingCatalog.catalog();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agentbudget", name = "limit")
    public BudgetGuard budgetGuard(AgentBudgetProperties properties, PricingCatalog pricingCatalog) {
        Money limit = parseLimit(properties.getLimit());

        BudgetGuard.Builder builder = BudgetGuard.builder()
                .limit(limit)
                .pricingCatalog(pricingCatalog)
                .onExceed(properties.getOnExceed())
                .onFailure(properties.getOnFailure());

        if (properties.getWarnAt() != null && !properties.getWarnAt().isBlank()) {
            builder.warnAt(parseThreshold(properties.getWarnAt()));
        }

        AgentBudgetProperties.Breaker breaker = properties.getBreaker();
        if (breaker.isEnabled()) {
            builder.callRateBreaker(CallRateBreaker.builder()
                    .maxCalls(breaker.getMaxCalls())
                    .window(breaker.getWindow())
                    .coolOff(breaker.getCoolOff() != null ? breaker.getCoolOff() : breaker.getWindow())
                    .build());
        }

        AgentBudgetProperties.Fallback fallback = properties.getFallback();
        if (fallback.getModel() != null && !fallback.getModel().isBlank()) {
            builder.fallbackModel(fallback.getModel());
        }
        if (fallback.getHardLimit() != null && !fallback.getHardLimit().isBlank()) {
            builder.hardLimit(parseLimit(fallback.getHardLimit()));
        }

        return builder.build();
    }

    /**
     * Maps this library's exceptions to HTTP statuses for a servlet web application. Independent
     * of whether a {@code BudgetGuard} bean exists — these exception types can be thrown by code
     * using core directly, with no Spring wiring at all — and skipped entirely for a non-web
     * application, or one that supplies its own handling.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(RequestContextHolder.class)
    public AgentBudgetExceptionHandler agentBudgetExceptionHandler() {
        return new AgentBudgetExceptionHandler();
    }

    static Money parseLimit(String text) {
        try {
            return Money.parse(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "agentbudget.limit (or agentbudget.fallback.hard-limit) is not a valid amount. "
                            + e.getMessage(), e);
        }
    }

    /**
     * {@code "80%"} as a fraction of the limit, or anything else as a fixed amount — the same two
     * forms {@link BudgetThreshold} itself offers, spelled the way a properties file writes them.
     */
    private static BudgetThreshold parseThreshold(String text) {
        String trimmed = text.trim();
        if (trimmed.endsWith("%")) {
            String number = trimmed.substring(0, trimmed.length() - 1).trim();
            double fraction;
            try {
                fraction = Double.parseDouble(number) / 100.0;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "agentbudget.warn-at '%s' is not a valid percentage".formatted(text), e);
            }
            return BudgetThreshold.ofFraction(fraction);
        }
        try {
            return BudgetThreshold.ofAmount(Money.parse(trimmed));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "agentbudget.warn-at '%s' is neither a percentage like \"80%%\" nor an amount like \"$4.00\". %s"
                            .formatted(text, e.getMessage()), e);
        }
    }
}
