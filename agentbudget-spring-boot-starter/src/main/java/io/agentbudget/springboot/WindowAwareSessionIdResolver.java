package io.agentbudget.springboot;

import io.agentbudget.spring.Budgeted;
import io.agentbudget.spring.BudgetedInvocation;
import io.agentbudget.spring.SessionIdResolver;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Objects;
import java.util.UUID;

/**
 * Decorates a {@link SessionIdResolver} with {@link Budgeted#window()}: whatever the delegate
 * resolves is the session identity, and the window decides what budget boundary that identity is
 * placed inside.
 *
 * <ul>
 *   <li>{@code "session"} (the default) — the delegate's id, unchanged.</li>
 *   <li>{@code "request"} — the delegate's id, scoped to the current HTTP request. Two calls in
 *       the same request share a budget; the next request starts a fresh one.</li>
 *   <li>anything else — a <strong>named scope</strong>: that literal string <em>becomes</em> the
 *       session id, ignoring the delegate entirely. Every caller, regardless of tenant or user,
 *       shares one budget under that name — for an org-wide or job-wide ceiling.</li>
 * </ul>
 *
 * <p>Registered only when {@code spring-web} is on the classpath (see
 * {@link AgentBudgetAutoConfiguration}), because {@code "request"} needs
 * {@link RequestContextHolder}. {@code "session"} and a named scope need it too, structurally,
 * since they still route through this class — but neither actually touches the request, so both
 * work in a Boot application with no active HTTP request, such as a scheduled job.
 */
public final class WindowAwareSessionIdResolver implements SessionIdResolver {

    private static final String REQUEST_SCOPE_ID_ATTRIBUTE = WindowAwareSessionIdResolver.class.getName() + ".id";

    private final SessionIdResolver delegate;

    public WindowAwareSessionIdResolver(SessionIdResolver delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public String resolveSessionId(BudgetedInvocation invocation) {
        String window = invocation.budgeted().window();

        if (window.isBlank() || "session".equalsIgnoreCase(window)) {
            return delegate.resolveSessionId(invocation);
        }
        if ("request".equalsIgnoreCase(window)) {
            return delegate.resolveSessionId(invocation) + ":" + currentRequestScopeId();
        }
        // a named scope: the literal itself is the whole session id, so every caller shares it
        return window;
    }

    private static String currentRequestScopeId() {
        RequestAttributes attributes;
        try {
            attributes = RequestContextHolder.currentRequestAttributes();
        } catch (IllegalStateException e) {
            throw new IllegalStateException(
                    "@Budgeted(window = \"request\") requires an active HTTP request, but this call had none. "
                            + "Use window = \"session\" (the default) for a method that can run outside a request.",
                    e);
        }

        Object existing = attributes.getAttribute(REQUEST_SCOPE_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (existing != null) {
            return existing.toString();
        }
        String generated = UUID.randomUUID().toString();
        attributes.setAttribute(REQUEST_SCOPE_ID_ATTRIBUTE, generated, RequestAttributes.SCOPE_REQUEST);
        return generated;
    }
}
