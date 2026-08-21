package io.agentbudget.spring;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The default resolver on its own, with no proxy and no context — which is the point of
 * {@link BudgetedInvocation} being a plain value type.
 */
class DefaultSessionIdResolverTest {

    private final DefaultSessionIdResolver resolver = new DefaultSessionIdResolver();

    @SuppressWarnings("unused")
    static class Methods {

        @Budgeted(limit = "$1.00")
        void annotatedParameter(String ignored, @SessionId String sessionId) {
        }

        @Budgeted(limit = "$1.00", session = "the-literal")
        void literalSession(String ignored) {
        }

        @Budgeted(limit = "$1.00", session = "the-literal")
        void bothPresent(@SessionId String sessionId) {
        }

        @Budgeted(limit = "$1.00")
        void neither(String ignored) {
        }

        @Budgeted(limit = "$1.00")
        void nonStringSessionId(@SessionId Long tenantId) {
        }
    }

    private BudgetedInvocation invocationOf(String methodName, Object... arguments) {
        Method method = Arrays.stream(Methods.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no such method: " + methodName));
        // Arrays.asList, not List.of: a real method can be called with a null argument, and the
        // interceptor passes those through, so the fixture has to be able to express one too.
        return new BudgetedInvocation(method, Arrays.asList(arguments), method.getAnnotation(Budgeted.class),
                new Methods());
    }

    @Test
    void readsTheAnnotatedParameterWhicheverPositionItIsIn() {
        assertThat(resolver.resolveSessionId(invocationOf("annotatedParameter", "noise", "tenant-a")))
                .isEqualTo("tenant-a");
    }

    @Test
    void fallsBackToTheAnnotationsLiteral() {
        assertThat(resolver.resolveSessionId(invocationOf("literalSession", "noise")))
                .isEqualTo("the-literal");
    }

    @Test
    void prefersTheParameterOverTheLiteral() {
        assertThat(resolver.resolveSessionId(invocationOf("bothPresent", "tenant-a")))
                .isEqualTo("tenant-a");
    }

    @Test
    void acceptsAnyTypeThatCanNameASession() {
        assertThat(resolver.resolveSessionId(invocationOf("nonStringSessionId", 42L))).isEqualTo("42");
    }

    @Test
    void refusesRatherThanPoolingIntoASharedDefault() {
        assertThatThrownBy(() -> resolver.resolveSessionId(invocationOf("neither", "noise")))
                .isInstanceOf(SessionIdUnresolvableException.class)
                .hasMessageContaining("@SessionId")
                .hasMessageContaining("SessionIdResolver")
                .hasMessageContaining("neither()");
    }

    @Test
    void refusesABlankOrAbsentSessionId() {
        for (Object value : new Object[]{null, "", "   "}) {
            assertThatThrownBy(() -> resolver.resolveSessionId(invocationOf("bothPresent", value)))
                    .as("session id %s", value)
                    .isInstanceOf(SessionIdUnresolvableException.class)
                    .hasMessageContaining("null or blank");
        }
    }

    @Test
    void resolvesTheSameMethodRepeatedlyWithoutRescanningIt() {
        for (int i = 0; i < 100; i++) {
            assertThat(resolver.resolveSessionId(invocationOf("annotatedParameter", "noise", "tenant-" + i)))
                    .isEqualTo("tenant-" + i);
        }
    }
}
