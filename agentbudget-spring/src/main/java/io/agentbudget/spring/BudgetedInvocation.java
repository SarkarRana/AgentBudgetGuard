package io.agentbudget.spring;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/**
 * One intercepted call, as a {@link SessionIdResolver} sees it. A small value type rather than
 * Spring's {@code MethodInvocation}, so implementing a resolver does not mean learning the AOP
 * API — and so a resolver can be unit-tested without a proxy.
 *
 * @param method    the method being called, as declared on the target class
 * @param arguments the arguments it was called with, in declaration order
 * @param budgeted  the annotation that matched
 * @param target    the bean instance the call is headed for
 */
public record BudgetedInvocation(Method method, List<Object> arguments, Budgeted budgeted, Object target) {

    public BudgetedInvocation {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(budgeted, "budgeted");
        arguments = arguments == null ? List.of() : java.util.Collections.unmodifiableList(arguments);
    }

    /**
     * The argument at {@code index}, or {@code null} if there is none there.
     */
    public Object argument(int index) {
        return index >= 0 && index < arguments.size() ? arguments.get(index) : null;
    }
}
