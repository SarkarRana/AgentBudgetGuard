package io.agentbudget.springboot;

import io.agentbudget.core.BudgetExceededException;
import io.agentbudget.core.BudgetGuard;
import io.agentbudget.core.CallRateExceededException;
import io.agentbudget.core.GuardFailureException;
import io.agentbudget.core.GuardedResult;
import io.agentbudget.core.PricingCatalog;
import io.agentbudget.core.StaticPricingCatalog;
import io.agentbudget.core.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Everything the issue names as operator-tunable, proven by observing what the built guard
 * actually does — {@code BudgetGuard} exposes no getters for its policy or failure mode, so
 * behaviour is the only honest way to assert the property was read.
 */
class AutoConfigPropertiesBindingTest {

    private static final String MODEL = "fake-model";
    private static final String SESSION = "session-1";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AgentBudgetAutoConfiguration.class, BudgetedSupportAutoConfiguration.class));

    @Configuration(proxyBeanMethods = false)
    static class PricingConfig {
        @Bean
        PricingCatalog pricingCatalog() {
            return StaticPricingCatalog.withSingleModel(MODEL,
                    io.agentbudget.core.ModelPricing.perMillionTokens("USD", 1_000_000, 1_000_000));
        }
    }

    @Test
    void warnLetsTheCallThroughInsteadOfStopping() {
        runner.withUserConfiguration(PricingConfig.class)
                .withPropertyValues("agentbudget.limit=$2.00", "agentbudget.on-exceed=WARN")
                .run(context -> {
                    BudgetGuard guard = context.getBean(BudgetGuard.class);
                    guard.wrap(SESSION, MODEL, () -> GuardedResult.of("a", TokenUsage.of(2, 0)));

                    assertThatCode(() -> guard.wrap(SESSION, MODEL,
                            () -> GuardedResult.of("b", TokenUsage.of(1, 0))))
                            .doesNotThrowAnyException();
                });
    }

    @Test
    void stopIsTheDefaultPolicy() {
        runner.withUserConfiguration(PricingConfig.class)
                .withPropertyValues("agentbudget.limit=$2.00")
                .run(context -> {
                    BudgetGuard guard = context.getBean(BudgetGuard.class);
                    guard.wrap(SESSION, MODEL, () -> GuardedResult.of("a", TokenUsage.of(2, 0)));

                    assertThatThrownBy(() -> guard.wrap(SESSION, MODEL,
                            () -> GuardedResult.of("b", TokenUsage.of(1, 0))))
                            .isInstanceOf(BudgetExceededException.class);
                });
    }

    @Test
    void aWarningThresholdAsAPercentageIsHonoured() {
        runner.withPropertyValues("agentbudget.limit=$10.00", "agentbudget.warn-at=80%")
                .run(context -> assertThat(context.getBean(BudgetGuard.class).warningThreshold())
                        .isEqualTo(io.agentbudget.core.Money.of("8.00", "USD")));
    }

    @Test
    void aWarningThresholdAsAnAmountIsHonoured() {
        runner.withPropertyValues("agentbudget.limit=$10.00", "agentbudget.warn-at=$4.00")
                .run(context -> assertThat(context.getBean(BudgetGuard.class).warningThreshold())
                        .isEqualTo(io.agentbudget.core.Money.of("4.00", "USD")));
    }

    @Test
    void withNoWarnAtThereIsNoThreshold() {
        runner.withPropertyValues("agentbudget.limit=$10.00")
                .run(context -> assertThat(context.getBean(BudgetGuard.class).warningThreshold()).isNull());
    }

    @Test
    void anUnparseableWarningThresholdFailsClearlyAtStartup() {
        runner.withPropertyValues("agentbudget.limit=$10.00", "agentbudget.warn-at=eighty percent")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failOpenIsTheDefaultFailureMode() {
        runner.withUserConfiguration(BrokenPricingConfig.class)
                .withPropertyValues("agentbudget.limit=$10.00")
                .run(context -> {
                    BudgetGuard guard = context.getBean(BudgetGuard.class);
                    assertThatCode(() -> guard.wrap(SESSION, "anything",
                            () -> GuardedResult.of("ok", TokenUsage.of(1, 1))))
                            .doesNotThrowAnyException();
                });
    }

    @Test
    void failClosedIsHonoured() {
        runner.withUserConfiguration(BrokenPricingConfig.class)
                .withPropertyValues("agentbudget.limit=$10.00", "agentbudget.on-failure=FAIL_CLOSED")
                .run(context -> {
                    BudgetGuard guard = context.getBean(BudgetGuard.class);
                    assertThatThrownBy(() -> guard.wrap(SESSION, "anything",
                            () -> GuardedResult.of("ok", TokenUsage.of(1, 1))))
                            .isInstanceOf(GuardFailureException.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class BrokenPricingConfig {
        @Bean
        PricingCatalog brokenPricingCatalog() {
            return (model, usage) -> {
                throw new IllegalStateException("boom");
            };
        }
    }

    @Test
    void theBreakerIsOffByDefault() {
        runner.withPropertyValues("agentbudget.limit=$1000.00")
                .run(context -> assertThatThrownBy(() -> context.getBean(BudgetGuard.class).breakerStatus(SESSION))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("No call-rate breaker"));
    }

    @Test
    void theBreakerTripsAtTheConfiguredRateWhenEnabled() {
        runner.withUserConfiguration(PricingConfig.class)
                .withPropertyValues(
                        "agentbudget.limit=$1000.00",
                        "agentbudget.breaker.enabled=true",
                        "agentbudget.breaker.max-calls=2",
                        "agentbudget.breaker.window=10s")
                .run(context -> {
                    BudgetGuard guard = context.getBean(BudgetGuard.class);
                    guard.wrap(SESSION, MODEL, () -> GuardedResult.of("a", TokenUsage.of(1, 0)));
                    guard.wrap(SESSION, MODEL, () -> GuardedResult.of("b", TokenUsage.of(1, 0)));

                    assertThatThrownBy(() -> guard.wrap(SESSION, MODEL,
                            () -> GuardedResult.of("c", TokenUsage.of(1, 0))))
                            .isInstanceOf(CallRateExceededException.class);
                });
    }

    @Test
    void switchModelIsWiredFromFallbackProperties() {
        runner.withUserConfiguration(PricingConfig.class)
                .withPropertyValues(
                        "agentbudget.limit=$10.00",
                        "agentbudget.on-exceed=SWITCH_MODEL",
                        "agentbudget.fallback.model=" + MODEL,
                        "agentbudget.fallback.hard-limit=$20.00")
                .run(context -> {
                    BudgetGuard guard = context.getBean(BudgetGuard.class);
                    assertThat(guard.fallbackModel()).isEqualTo(MODEL);
                    assertThat(guard.hardLimit()).isEqualTo(io.agentbudget.core.Money.of("20.00", "USD"));
                });
    }

    @Test
    void switchModelWithoutAFallbackFailsAtStartupRatherThanAtTheBreach() {
        runner.withPropertyValues("agentbudget.limit=$10.00", "agentbudget.on-exceed=SWITCH_MODEL")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aMalformedLimitFailsClearlyAtStartup() {
        runner.withPropertyValues("agentbudget.limit=not a real amount")
                .run(context -> assertThat(context).getFailure()
                        .hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .hasStackTraceContaining("agentbudget.limit"));
    }
}
