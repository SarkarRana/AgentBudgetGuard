package io.agentbudget.spring;

import io.agentbudget.core.BudgetGuard;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;

/**
 * Wires {@link Budgeted} into an application context. Import it directly, or let the Boot starter
 * do it.
 *
 * <p>Plain Spring, no Boot: this module depends on {@code spring-context} and {@code spring-aop}
 * and nothing else, so it works in an application that is not a Boot application at all. Backing
 * off in favour of a user's own bean is therefore done with {@link ObjectProvider} rather than
 * {@code @ConditionalOnMissingBean}.
 *
 * <p>Proxying is left at Spring's default of interface-based JDK proxies. Nothing here sets
 * {@code proxyTargetClass}, so the module never asks for class proxying and never needs the
 * bytecode machinery that would require.
 */
@Configuration(proxyBeanMethods = false)
public class BudgetedConfiguration {

    /**
     * Note what is <em>not</em> here: no {@code SessionIdResolver} bean. Declaring one would
     * collide with an application that declares its own. Instead the interceptor takes whichever
     * one the context has, falling back to {@link DefaultSessionIdResolver} when there is none —
     * so a custom resolver bean replaces the default with no further configuration.
     */
    @Bean
    public BudgetedMethodInterceptor budgetedMethodInterceptor(BudgetGuardRegistry registry,
                                                              ObjectProvider<SessionIdResolver> resolvers) {
        return new BudgetedMethodInterceptor(registry, resolvers.getIfUnique(DefaultSessionIdResolver::new));
    }

    @Bean
    public BudgetGuardRegistry budgetGuardRegistry(BudgetGuard budgetGuard) {
        return new BudgetGuardRegistry(budgetGuard);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public BudgetedAdvisor budgetedAdvisor(BudgetedMethodInterceptor interceptor) {
        return new BudgetedAdvisor(interceptor);
    }

    /**
     * Proxies any bean matched by an advisor in the context. Plain Spring AOP — no
     * {@code @EnableAspectJAutoProxy}, which would bring in AspectJ's annotation and pointcut
     * parsing for a pointcut this module does not express that way.
     */
    // static: a BeanPostProcessor factory method must be static, so this bean can be created
    // before BudgetedConfiguration itself is fully initialized - otherwise Spring instantiates
    // the whole configuration class early to call this method, and warns that it missed the
    // chance to apply other post-processors (such as itself) to it.
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static DefaultAdvisorAutoProxyCreator budgetedAutoProxyCreator() {
        return new DefaultAdvisorAutoProxyCreator();
    }
}
