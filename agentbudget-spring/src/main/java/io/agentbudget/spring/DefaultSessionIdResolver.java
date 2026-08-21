package io.agentbudget.spring;

import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.util.StringValueResolver;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The default {@link SessionIdResolver}, in two steps and no magic:
 *
 * <ol>
 *   <li>the argument of the parameter annotated {@link SessionId}, as its {@code toString()};</li>
 *   <li>failing that, the annotation's own {@link Budgeted#session()} literal.</li>
 * </ol>
 *
 * <p>If neither is present it throws {@link SessionIdUnresolvableException} and the call is
 * refused. That is deliberate. The tempting alternative — falling back to a shared default
 * session — pools every user's spend into one budget, which behaves correctly in a single-user
 * test and disastrously in production the first time one user exhausts everyone else's
 * allowance. Refusing is loud, immediate, and impossible to ship by accident. See ADR 0005.
 *
 * <p>Nothing here reads a thread-local, a security context, or a request attribute. Applications
 * that want that behaviour write a {@link SessionIdResolver} that does it explicitly.
 *
 * <p>The {@code session} literal accepts a {@code ${property.name}} placeholder, resolved the
 * same way {@code @Value} is. Unset outside a bean factory — a resolver built by hand in a test,
 * say — in which case the literal is used as written.
 */
public final class DefaultSessionIdResolver implements SessionIdResolver, EmbeddedValueResolverAware {

    /** Resolved parameter indexes, cached per method — reflection on every call is wasteful. */
    private final Map<Method, Integer> sessionIdParameterIndexes = new ConcurrentHashMap<>();

    private StringValueResolver placeholders;

    @Override
    public void setEmbeddedValueResolver(StringValueResolver resolver) {
        this.placeholders = resolver;
    }

    @Override
    public String resolveSessionId(BudgetedInvocation invocation) {
        int index = sessionIdParameterIndexes.computeIfAbsent(invocation.method(),
                DefaultSessionIdResolver::findSessionIdParameter);
        if (index >= 0) {
            Object argument = invocation.argument(index);
            if (argument != null) {
                String sessionId = argument.toString();
                if (!sessionId.isBlank()) {
                    return sessionId;
                }
            }
            throw new SessionIdUnresolvableException(
                    "The @SessionId parameter of %s was null or blank, so there is no session to charge"
                            .formatted(describe(invocation.method())));
        }

        String declared = invocation.budgeted().session();
        if (!declared.isBlank()) {
            return placeholders != null ? placeholders.resolveStringValue(declared) : declared;
        }

        throw new SessionIdUnresolvableException(
                ("Cannot resolve a session id for %s. Annotate a parameter with @SessionId, set "
                        + "@Budgeted(session = \"...\"), or define your own SessionIdResolver bean.")
                        .formatted(describe(invocation.method())));
    }

    private static int findSessionIdParameter(Method method) {
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            for (Annotation annotation : parameters[i].getAnnotations()) {
                if (annotation instanceof SessionId) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String describe(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName() + "()";
    }
}
