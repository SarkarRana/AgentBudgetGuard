package io.agentbudget.spring;

import org.aopalliance.aop.Advice;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractPointcutAdvisor;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;

import java.util.Objects;

/**
 * Pairs the {@link Budgeted} pointcut with {@link BudgetedMethodInterceptor}. Registering one of
 * these as a bean is all it takes for a proxy creator to start advising annotated methods.
 *
 * <p>The pointcut is Spring's own {@link AnnotationMatchingPointcut}, matching the annotation on
 * a method or on its declaring class. No {@code @Aspect}, no pointcut expression language, and
 * so no {@code aspectjweaver} on the classpath — which is what lets this module claim a
 * dependency tree with no bytecode manipulation in it.
 */
public final class BudgetedAdvisor extends AbstractPointcutAdvisor {

    private final Pointcut pointcut = new ComposablePointcut(
            AnnotationMatchingPointcut.forMethodAnnotation(Budgeted.class))
            .union(AnnotationMatchingPointcut.forClassAnnotation(Budgeted.class));

    private final Advice advice;

    public BudgetedAdvisor(BudgetedMethodInterceptor interceptor) {
        this.advice = Objects.requireNonNull(interceptor, "interceptor");
    }

    @Override
    public Pointcut getPointcut() {
        return pointcut;
    }

    @Override
    public Advice getAdvice() {
        return advice;
    }
}
