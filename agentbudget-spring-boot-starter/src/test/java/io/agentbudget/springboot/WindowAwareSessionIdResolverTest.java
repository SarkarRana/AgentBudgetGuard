package io.agentbudget.springboot;

import io.agentbudget.spring.Budgeted;
import io.agentbudget.spring.BudgetedInvocation;
import io.agentbudget.spring.SessionIdResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three windows on their own, isolated from Spring context wiring — proves what each one
 * actually does to the session id the delegate resolves.
 */
class WindowAwareSessionIdResolverTest {

    private static final SessionIdResolver DELEGATE = invocation -> "tenant-a";

    private final WindowAwareSessionIdResolver resolver = new WindowAwareSessionIdResolver(DELEGATE);

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @SuppressWarnings("unused")
    static class Methods {
        @Budgeted
        void defaultWindow() {
        }

        @Budgeted(window = "session")
        void explicitSession() {
        }

        @Budgeted(window = "request")
        void requestWindow() {
        }

        @Budgeted(window = "org-daily-budget")
        void namedScope() {
        }
    }

    private BudgetedInvocation invocationOf(String methodName) {
        Method method = List.of(Methods.class.getDeclaredMethods()).stream()
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return new BudgetedInvocation(method, List.of(), method.getAnnotation(Budgeted.class), new Methods());
    }

    @Nested
    class TheSessionWindow {

        @Test
        void isTheDefaultAndLeavesTheDelegatesIdUnchanged() {
            assertThat(resolver.resolveSessionId(invocationOf("defaultWindow"))).isEqualTo("tenant-a");
        }

        @Test
        void canAlsoBeSpelledOutExplicitly() {
            assertThat(resolver.resolveSessionId(invocationOf("explicitSession"))).isEqualTo("tenant-a");
        }
    }

    @Nested
    class TheRequestWindow {

        @Test
        void scopesTheDelegatesIdToTheCurrentRequest() {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

            String sessionId = resolver.resolveSessionId(invocationOf("requestWindow"));

            assertThat(sessionId).startsWith("tenant-a:");
        }

        @Test
        void returnsTheSameIdForTwoCallsInTheSameRequest() {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

            String first = resolver.resolveSessionId(invocationOf("requestWindow"));
            String second = resolver.resolveSessionId(invocationOf("requestWindow"));

            assertThat(first).isEqualTo(second);
        }

        @Test
        void returnsADifferentIdForADifferentRequest() {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
            String first = resolver.resolveSessionId(invocationOf("requestWindow"));
            RequestContextHolder.resetRequestAttributes();

            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
            String second = resolver.resolveSessionId(invocationOf("requestWindow"));

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        void failsClearlyOutsideAnActiveRequest() {
            assertThatThrownBy(() -> resolver.resolveSessionId(invocationOf("requestWindow")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("active HTTP request");
        }
    }

    @Nested
    class ANamedScope {

        @Test
        void becomesTheWholeSessionIdIgnoringTheDelegate() {
            assertThat(resolver.resolveSessionId(invocationOf("namedScope"))).isEqualTo("org-daily-budget");
        }

        @Test
        void isSharedByEveryCallerRegardlessOfWhatTheDelegateWouldHaveResolved() {
            SessionIdResolver differentDelegate = invocation -> "tenant-b";
            WindowAwareSessionIdResolver other = new WindowAwareSessionIdResolver(differentDelegate);

            assertThat(resolver.resolveSessionId(invocationOf("namedScope")))
                    .isEqualTo(other.resolveSessionId(invocationOf("namedScope")));
        }
    }
}
