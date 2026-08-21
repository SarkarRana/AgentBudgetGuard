package io.agentbudget.springboot;

import io.agentbudget.core.BudgetGuard;
import io.agentbudget.spring.BudgetedConfiguration;
import io.agentbudget.spring.DefaultSessionIdResolver;
import io.agentbudget.spring.SessionIdResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Everything {@code @Budgeted} needs to actually intercept a method — the interceptor, the
 * advisor, the proxy creator, from {@link BudgetedConfiguration} — registered only once a
 * {@code BudgetGuard} bean exists.
 *
 * <p>A separate, explicitly ordered auto-configuration class rather than more beans on
 * {@link AgentBudgetAutoConfiguration}, because {@link ConditionalOnBean} needs the
 * {@code BudgetGuard} bean <em>definition</em> already registered when it evaluates.
 * {@code @AutoConfigureAfter} is what gives that ordering guarantee — {@code AgentBudgetAutoConfiguration}
 * is fully processed, definitions and all, before this class's own condition is checked. A nested
 * configuration class inside {@code AgentBudgetAutoConfiguration} does not give the same
 * guarantee: Spring processes an enclosing class's nested member classes <em>before</em> its own
 * {@code @Bean} methods, which is the reverse of the order this needs.
 *
 * <p>Without a {@code BudgetGuard} bean — no {@code agentbudget.limit} configured and no guard
 * bean of the application's own — none of this activates, and a Boot application that has this
 * starter on its classpath purely as a transitive dependency, never touching {@code @Budgeted} at
 * all, still starts cleanly.
 */
@AutoConfiguration(after = AgentBudgetAutoConfiguration.class)
@ConditionalOnBean(BudgetGuard.class)
@Import(BudgetedConfiguration.class)
public class BudgetedSupportAutoConfiguration {

    /**
     * The default {@link SessionIdResolver}, decorated with {@code @Budgeted(window = ...)}
     * support. Backs off, like every bean here, on a resolver the application defines for itself
     * — which also means windowing is a property of the default chain only; a custom resolver
     * owns its own scoping entirely, matching ADR 0005.
     *
     * <p>Guarded on {@link RequestContextHolder} rather than assumed present: {@code spring-web}
     * is a required dependency of this module today, but the guard documents the real requirement
     * and keeps this safe if that ever changes to optional.
     */
    @Bean
    @ConditionalOnMissingBean(SessionIdResolver.class)
    @ConditionalOnClass(RequestContextHolder.class)
    public SessionIdResolver windowAwareSessionIdResolver() {
        return new WindowAwareSessionIdResolver(new DefaultSessionIdResolver());
    }
}
