package io.agentbudget.demo;

import io.agentbudget.core.BuiltInPricingCatalog;
import io.agentbudget.core.Money;
import io.agentbudget.core.ModelPricing;
import io.agentbudget.core.PricingCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers pricing for "demo-llm", the model {@link FakeLlmClient} pretends to call, on top of
 * the library's built-in OpenAI and Anthropic rates. This is what custom model pricing
 * registration looks like for a self-hosted or otherwise unlisted model -- see the root
 * README's "Custom model pricing" section.
 *
 * <p>Defining this bean is also what makes it visible: {@code AgentBudgetAutoConfiguration}'s
 * own {@code PricingCatalog} bean is {@code @ConditionalOnMissingBean}, so it backs off in
 * favour of this one with no other configuration.
 *
 * <p>The rate is deliberately unrealistic -- $0.01 per input token, $0.02 per output token --
 * so that {@link FakeLlmClient}'s fixed usage per call costs a clean $2.00, and a handful of
 * requests against the demo's $3.00 limit is enough to see it hit.
 */
@Configuration
public class DemoPricingConfiguration {

    @Bean
    public PricingCatalog pricingCatalog() {
        return BuiltInPricingCatalog.withCustomRegistration()
                .register("demo-llm", new ModelPricing(
                        Money.of("0.01", "USD"),
                        Money.of("0.01", "USD"),
                        Money.of("0.02", "USD")))
                .build();
    }
}
