package io.agentbudget.spring;

import io.agentbudget.core.BudgetGuard;
import io.agentbudget.core.CallId;
import io.agentbudget.core.GuardedResult;
import io.agentbudget.core.TokenUsage;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringValueResolver;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The advice behind {@link Budgeted}: check the session's budget, run the method, charge what it
 * reported.
 *
 * <p>A plain {@code MethodInterceptor} rather than an {@code @Aspect}, which is what keeps
 * {@code aspectjweaver} out of the dependency tree — the pointcut is expressed in
 * {@link BudgetedAdvisor} with Spring's own matching instead.
 *
 * <p>{@code limit} and {@code model} accept a {@code ${property.name}} placeholder, resolved
 * against the application's environment the same way {@code @Value} is — through
 * {@link EmbeddedValueResolverAware}, which is core Spring rather than a Boot feature, so this
 * works whether or not the starter module is on the classpath. Outside a bean factory (a
 * hand-built interceptor in a test, say) the resolver is never set and values pass through
 * unresolved.
 */
public final class BudgetedMethodInterceptor implements MethodInterceptor, EmbeddedValueResolverAware {

    private final BudgetGuardRegistry guards;
    private final SessionIdResolver sessionIdResolver;
    private final Map<Method, Budgeted> annotations = new ConcurrentHashMap<>();

    private StringValueResolver placeholders;

    public BudgetedMethodInterceptor(BudgetGuardRegistry guards, SessionIdResolver sessionIdResolver) {
        this.guards = Objects.requireNonNull(guards, "guards");
        this.sessionIdResolver = Objects.requireNonNull(sessionIdResolver, "sessionIdResolver");
    }

    @Override
    public void setEmbeddedValueResolver(StringValueResolver resolver) {
        this.placeholders = resolver;
    }

    private String resolve(String value) {
        return placeholders != null ? placeholders.resolveStringValue(value) : value;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = targetMethod(invocation);
        Budgeted budgeted = annotations.computeIfAbsent(method, BudgetedMethodInterceptor::findAnnotation);
        if (budgeted == null) {
            return invocation.proceed();
        }

        List<Object> arguments = invocation.getArguments() == null
                ? List.of()
                : Arrays.asList(invocation.getArguments());
        String sessionId = sessionIdResolver.resolveSessionId(
                new BudgetedInvocation(method, arguments, budgeted, invocation.getThis()));

        BudgetGuard guard = guards.guardFor(resolve(budgeted.limit()));
        String model = resolve(budgeted.model());

        // The guard's own wrap() gives the budget check, the pricing, the recording, the
        // threshold callbacks and the failure mode for free. All the advice has to supply is what
        // the method turned out to consume — which is what the collector is for.
        BudgetedUsage.UsageCollector collector = BudgetedUsage.bind();
        try {
            return guard.wrap(sessionId, model, CallId.random(), () -> {
                Object output = proceed(invocation);
                // A method that returns a GuardedResult has told us its usage in its return value.
                // The envelope is handed straight back rather than unwrapped: the method declared
                // that return type, so its callers expect it.
                TokenUsage usage = output instanceof GuardedResult<?> guarded
                        ? guarded.usage()
                        : collector.usage();
                return GuardedResult.of(output, usage);
            });
        } catch (CheckedInvocationException e) {
            throw e.getCause();
        } finally {
            BudgetedUsage.unbind(collector);
        }
    }

    /**
     * Runs the intercepted method, unwrapping the checked exception the AOP API declares so the
     * caller sees exactly what their method threw.
     */
    private static Object proceed(MethodInvocation invocation) {
        try {
            return invocation.proceed();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new CheckedInvocationException(e);
        }
    }

    /**
     * Resolves the method as declared on the target class rather than on the proxied interface,
     * so a {@code @Budgeted} or {@code @SessionId} written on the implementation is still found.
     */
    private static Method targetMethod(MethodInvocation invocation) {
        Object target = invocation.getThis();
        if (target == null) {
            return invocation.getMethod();
        }
        return org.springframework.aop.support.AopUtils.getMostSpecificMethod(
                invocation.getMethod(), ClassUtils.getUserClass(target));
    }

    private static Budgeted findAnnotation(Method method) {
        Budgeted onMethod = AnnotatedElementUtils.findMergedAnnotation(method, Budgeted.class);
        return onMethod != null
                ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(), Budgeted.class);
    }

    /**
     * Carries a checked exception thrown by an intercepted method back out through the guard,
     * which deals in unchecked ones. Unwrapped again before it leaves {@link #invoke}, so the
     * caller sees exactly the exception their method threw and this type is never observable.
     */
    static final class CheckedInvocationException extends RuntimeException {
        CheckedInvocationException(Throwable cause) {
            super(cause);
        }
    }
}
