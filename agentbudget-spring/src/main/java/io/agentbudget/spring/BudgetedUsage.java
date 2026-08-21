package io.agentbudget.spring;

import io.agentbudget.core.TokenUsage;

import java.util.Objects;

/**
 * How a {@link Budgeted} method reports what its call consumed.
 *
 * <pre>{@code
 * @Budgeted(limit = "$2.00", model = "gpt-4o")
 * public String summarise(@SessionId String tenantId, String document) {
 *     ChatResponse response = chatClient.call(document);
 *     BudgetedUsage.report(response.inputTokens(), response.outputTokens());
 *     return response.text();
 * }
 * }</pre>
 *
 * <p>The advice sits outside the method and cannot see the provider call inside it, so this is
 * how usage gets out. It is bound to the current invocation for the duration of the method, and
 * unbound when the method returns — reporting outside a {@code @Budgeted} method is a no-op
 * rather than an error, so a service that is sometimes called unannotated still works.
 *
 * <p>The binding is a thread-local, which is worth being explicit about: it carries usage
 * <em>out</em> of a method whose boundaries the advice already knows. It is not used to invent
 * session scoping — that stays explicit, through {@link SessionId}.
 *
 * <p>Reporting more than once in a method accumulates, so a method making several provider calls
 * reports each and is charged for the total.
 */
public final class BudgetedUsage {

    private static final ThreadLocal<UsageCollector> CURRENT = new ThreadLocal<>();

    private BudgetedUsage() {
    }

    /**
     * Reports usage against the enclosing method's configured model.
     */
    public static void report(TokenUsage usage) {
        report(null, usage);
    }

    /**
     * Reports usage against a named model, for a method that calls more than one.
     */
    public static void report(String model, TokenUsage usage) {
        Objects.requireNonNull(usage, "usage");
        UsageCollector collector = CURRENT.get();
        if (collector != null) {
            collector.add(model, usage);
        }
    }

    /**
     * Reports input and output token counts against the enclosing method's configured model.
     */
    public static void report(long inputTokens, long outputTokens) {
        report(TokenUsage.of(inputTokens, outputTokens));
    }

    /**
     * Whether the calling code is inside a {@code @Budgeted} method whose usage is being
     * collected. Rarely needed; useful for a client wrapper deciding whether to bother.
     */
    public static boolean isReporting() {
        return CURRENT.get() != null;
    }

    static UsageCollector bind() {
        UsageCollector collector = new UsageCollector(CURRENT.get());
        CURRENT.set(collector);
        return collector;
    }

    static void unbind(UsageCollector collector) {
        if (collector.enclosing == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(collector.enclosing);
        }
    }

    /**
     * Accumulates what one intercepted call reported. Nested {@code @Budgeted} methods each get
     * their own, restored on the way out, so an inner method's usage is charged to the inner
     * method rather than leaking into its caller's total.
     */
    static final class UsageCollector {

        private final UsageCollector enclosing;
        private TokenUsage usage = TokenUsage.ZERO;
        private String model;

        private UsageCollector(UsageCollector enclosing) {
            this.enclosing = enclosing;
        }

        private synchronized void add(String reportedModel, TokenUsage reported) {
            if (reportedModel != null) {
                this.model = reportedModel;
            }
            this.usage = new TokenUsage(
                    usage.inputTokens() + reported.inputTokens(),
                    usage.cachedInputTokens() + reported.cachedInputTokens(),
                    usage.outputTokens() + reported.outputTokens());
        }

        synchronized TokenUsage usage() {
            return usage;
        }

        synchronized String model() {
            return model;
        }

        synchronized boolean reported() {
            return usage.inputTokens() > 0 || usage.cachedInputTokens() > 0 || usage.outputTokens() > 0;
        }
    }
}
