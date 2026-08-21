package io.agentbudget.spring;

import io.agentbudget.core.BudgetExceededException;
import io.agentbudget.core.BudgetGuard;
import io.agentbudget.core.Money;
import io.agentbudget.core.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves interception actually happens — that the advice runs, enforces, and charges — rather
 * than merely that the beans exist.
 */
@SpringJUnitConfig(classes = {
        BudgetedConfiguration.class,
        BudgetedTestSupport.GuardConfig.class,
        DefaultSummariserService.class})
class BudgetedAnnotationContextTest {

    @Autowired
    private SummariserService service;

    @Autowired
    private BudgetGuard guard;

    @BeforeEach
    void resetSessions() {
        guard.resetSession("tenant-a");
        guard.resetSession("tenant-b");
        guard.resetSession("the-fixed-session");
        service.executed().clear();
    }

    @Test
    void theBeanIsActuallyProxied() {
        assertThat(AopUtils.isAopProxy(service)).isTrue();
        assertThat(AopUtils.isJdkDynamicProxy(service))
                .as("interface-based JDK proxy, not a class proxy")
                .isTrue();
    }

    @Test
    void aBudgetedCallIsChargedToItsSession() {
        service.summarise("tenant-a", 3);

        assertThat(guard.spend("tenant-a")).isEqualTo(Money.of("3.00", "USD"));
        assertThat(service.executed()).containsExactly("summarise");
    }

    @Test
    void anExhaustedSessionIsRefusedAndTheMethodBodyNeverRuns() {
        service.summarise("tenant-a", 4); // exactly the $4.00 annotation limit

        assertThatThrownBy(() -> service.summarise("tenant-a", 1))
                .isInstanceOf(BudgetExceededException.class);

        // the method body ran once, not twice
        assertThat(service.executed()).containsExactly("summarise");
        assertThat(guard.spend("tenant-a")).isEqualTo(Money.of("4.00", "USD"));
    }

    @Test
    void eachSessionIsTrackedIndependently() {
        service.summarise("tenant-a", 4);
        assertThatCode(() -> service.summarise("tenant-b", 3)).doesNotThrowAnyException();

        assertThat(guard.spend("tenant-a")).isEqualTo(Money.of("4.00", "USD"));
        assertThat(guard.spend("tenant-b")).isEqualTo(Money.of("3.00", "USD"));
    }

    @Test
    void anUnannotatedMethodIsNotIntercepted() {
        service.unbudgeted(50);

        assertThat(guard.spend("tenant-a")).isEqualTo(Money.zero(guard.limit().currency()));
        assertThat(service.executed()).containsExactly("unbudgeted");
    }

    @Test
    void severalReportsInOneMethodAccumulate() {
        service.summariseTwice("tenant-a", 2, 3);

        assertThat(guard.spend("tenant-a")).isEqualTo(Money.of("5.00", "USD"));
    }

    @Test
    void aMethodReturningAGuardedResultIsChargedFromItAndStillReturnsIt() {
        var result = service.summariseReturningUsage("tenant-a", TokenUsage.of(3, 0));

        assertThat(guard.spend("tenant-a")).isEqualTo(Money.of("3.00", "USD"));
        assertThat(result.output()).isEqualTo("summary");
        assertThat(result.usage()).isEqualTo(TokenUsage.of(3, 0));
    }

    @Test
    void aCheckedExceptionReachesTheCallerUnchanged() {
        assertThatThrownBy(() -> service.throwsChecked("tenant-a"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("the provider is unreachable");
    }

    @Test
    void reportingOutsideABudgetedMethodIsHarmless() {
        assertThatCode(() -> BudgetedUsage.report(5, 5)).doesNotThrowAnyException();
        assertThat(BudgetedUsage.isReporting()).isFalse();
    }

    @Nested
    class TheAnnotationsLimit {

        @Test
        void isParsedFromAReadableString() {
            // the annotation says $4.00, overriding the guard bean's own $10.00
            service.summarise("tenant-a", 4);
            assertThatThrownBy(() -> service.summarise("tenant-a", 1))
                    .isInstanceOf(BudgetExceededException.class)
                    .satisfies(e -> assertThat(((BudgetExceededException) e).limit())
                            .isEqualTo(Money.of("4.00", "USD")));
        }

        @Test
        void appliesToTheSameSessionLedgerAsEveryOtherLimit() {
            // $4.00 method and $10.00 method, one session, one running total
            service.summarise("tenant-a", 3);
            service.summariseTwice("tenant-a", 1, 1);

            assertThat(guard.spend("tenant-a")).isEqualTo(Money.of("5.00", "USD"));
        }
    }

    @Nested
    class SessionIdResolution {

        @Test
        void readsTheParameterAnnotatedWithSessionId() {
            service.summarise("tenant-a", 2);
            assertThat(guard.spend("tenant-a")).isEqualTo(Money.of("2.00", "USD"));
        }

        @Test
        void fallsBackToTheAnnotationsOwnLiteral() {
            service.summariseFixedSession(2);
            assertThat(guard.spend("the-fixed-session")).isEqualTo(Money.of("2.00", "USD"));
        }

        @Test
        void refusesTheCallWhenThereIsNothingToResolveFrom() {
            assertThatThrownBy(() -> service.summariseWithoutSession(1))
                    .isInstanceOf(SessionIdUnresolvableException.class)
                    .hasMessageContaining("@SessionId");

            // refused rather than pooled into some shared default session
            assertThat(service.executed()).isEmpty();
        }

        @Test
        void refusesTheCallWhenTheSessionIdIsNull() {
            assertThatThrownBy(() -> service.summarise(null, 1))
                    .isInstanceOf(SessionIdUnresolvableException.class)
                    .hasMessageContaining("null or blank");
        }
    }

    @Nested
    class SelfInvocation {

        @Test
        void isNotInterceptedAndTheModuleDocumentsWhy() {
            service.selfInvoking("tenant-a", 3);

            // both method bodies ran...
            assertThat(service.executed()).containsExactly("selfInvoking", "summarise");

            // ...but only one interception happened, so the ledger records one call, not two.
            // The inner summarise() went through `this` rather than the proxy, so its own
            // @Budgeted was never consulted. This is Spring AOP's documented limitation, and the
            // reason it is called out in the module README.
            assertThat(guard.snapshot("tenant-a").calls())
                    .as("the inner call bypassed the proxy, so it was never separately charged")
                    .hasSize(1);
            assertThat(guard.spend("tenant-a")).isEqualTo(Money.of("3.00", "USD"));
        }
    }
}
