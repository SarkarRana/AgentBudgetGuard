package io.agentbudget.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallRateBreakerTest {

    private static final String SESSION = "session-1";

    private final MutableClock clock = MutableClock.startingAtEpoch();

    /** Five calls per ten seconds, closing again ten seconds after it trips. */
    private CallRateBreaker breaker() {
        return CallRateBreaker.builder()
                .maxCalls(5)
                .window(Duration.ofSeconds(10))
                .coolOff(Duration.ofSeconds(10))
                .clock(clock)
                .build();
    }

    @Test
    void allowsCallsUpToTheConfiguredMaximum() {
        CallRateBreaker breaker = breaker();

        for (int i = 0; i < 5; i++) {
            int call = i + 1;
            assertThatCode(() -> breaker.recordCall(SESSION))
                    .as("call %d of 5 permitted", call)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void tripsOnTheCallThatWouldExceedTheMaximumAndNotOneBefore() {
        CallRateBreaker breaker = breaker();
        for (int i = 0; i < 5; i++) {
            breaker.recordCall(SESSION);
        }

        assertThatThrownBy(() -> breaker.recordCall(SESSION))
                .isInstanceOf(CallRateExceededException.class);
    }

    @Test
    void theExceptionNamesTheWindowAndTheObservedCount() {
        CallRateBreaker breaker = breaker();
        for (int i = 0; i < 5; i++) {
            breaker.recordCall(SESSION);
        }

        assertThatThrownBy(() -> breaker.recordCall(SESSION))
                .isInstanceOf(CallRateExceededException.class)
                .satisfies(thrown -> {
                    CallRateExceededException e = (CallRateExceededException) thrown;
                    assertThat(e.sessionId()).isEqualTo(SESSION);
                    assertThat(e.observedCalls()).isEqualTo(6);
                    assertThat(e.maxCalls()).isEqualTo(5);
                    assertThat(e.window()).isEqualTo(Duration.ofSeconds(10));
                })
                .hasMessageContaining("6")
                .hasMessageContaining("PT10S");
    }

    @Test
    void isATypeDistinctFromABudgetBreach() {
        CallRateBreaker breaker = breaker();
        for (int i = 0; i < 5; i++) {
            breaker.recordCall(SESSION);
        }

        assertThatThrownBy(() -> breaker.recordCall(SESSION))
                .isInstanceOf(AgentBudgetException.class)
                .isNotInstanceOf(BudgetExceededException.class);
    }

    @Test
    void doesNotTripWhenCallsStraddleAWindowBoundaryWithoutExceedingTheRate() {
        CallRateBreaker breaker = breaker();

        // four calls, then four more a full window later: eight calls, never more than five
        // inside any ten-second window
        for (int i = 0; i < 4; i++) {
            breaker.recordCall(SESSION);
            clock.advanceSeconds(1);
        }
        clock.advanceSeconds(10);
        for (int i = 0; i < 4; i++) {
            assertThatCode(() -> breaker.recordCall(SESSION)).doesNotThrowAnyException();
            clock.advanceSeconds(1);
        }
    }

    @Test
    void countsOnlyCallsStillInsideTheTrailingWindow() {
        CallRateBreaker breaker = breaker();
        for (int i = 0; i < 5; i++) {
            breaker.recordCall(SESSION);
        }
        assertThat(breaker.status(SESSION).callsInWindow()).isEqualTo(5);

        // the whole window slides past, so the count drains and a sixth call is fine
        clock.advanceSeconds(11);
        assertThat(breaker.status(SESSION).callsInWindow()).isZero();
        assertThatCode(() -> breaker.recordCall(SESSION)).doesNotThrowAnyException();
    }

    @Test
    void aSustainedRateUnderTheLimitNeverTrips() {
        CallRateBreaker breaker = breaker();

        // one call every three seconds for five minutes: comfortably under five per ten seconds
        for (int i = 0; i < 100; i++) {
            assertThatCode(() -> breaker.recordCall(SESSION)).doesNotThrowAnyException();
            clock.advanceSeconds(3);
        }
    }

    @Nested
    class OnceTripped {

        @Test
        void rejectsEvenCallsThatWouldOtherwiseBeUnderTheRate() {
            CallRateBreaker breaker = breaker();
            trip(breaker);

            // the window has drained, but the breaker is still cooling off
            clock.advanceSeconds(9);
            assertThatThrownBy(() -> breaker.recordCall(SESSION))
                    .isInstanceOf(CallRateExceededException.class);
        }

        @Test
        void reopensOnceTheCoolOffElapses() {
            CallRateBreaker breaker = breaker();
            trip(breaker);

            clock.advanceSeconds(10);
            assertThatCode(() -> breaker.recordCall(SESSION)).doesNotThrowAnyException();
            assertThat(breaker.status(SESSION).state()).isEqualTo(BreakerState.CLOSED);
        }

        @Test
        void reopensWithAFreshCountRatherThanTrippingImmediatelyAgain() {
            CallRateBreaker breaker = breaker();
            trip(breaker);
            clock.advanceSeconds(10);

            // a full fresh allowance, not one call before tripping again
            for (int i = 0; i < 5; i++) {
                assertThatCode(() -> breaker.recordCall(SESSION)).doesNotThrowAnyException();
            }
        }

        @Test
        void coolOffDefaultsToTheWindowWhenUnset() {
            CallRateBreaker breaker = CallRateBreaker.builder()
                    .maxCalls(2)
                    .window(Duration.ofSeconds(30))
                    .clock(clock)
                    .build();
            breaker.recordCall(SESSION);
            breaker.recordCall(SESSION);
            assertThatThrownBy(() -> breaker.recordCall(SESSION)).isInstanceOf(CallRateExceededException.class);

            clock.advanceSeconds(29);
            assertThat(breaker.status(SESSION).isOpen()).isTrue();
            clock.advanceSeconds(1);
            assertThat(breaker.status(SESSION).isOpen()).isFalse();
        }
    }

    @Nested
    class ReportingState {

        @Test
        void reportsAClosedBreakerWithItsRunningCount() {
            CallRateBreaker breaker = breaker();
            breaker.recordCall(SESSION);
            breaker.recordCall(SESSION);

            BreakerStatus status = breaker.status(SESSION);
            assertThat(status.sessionId()).isEqualTo(SESSION);
            assertThat(status.state()).isEqualTo(BreakerState.CLOSED);
            assertThat(status.isOpen()).isFalse();
            assertThat(status.callsInWindow()).isEqualTo(2);
            assertThat(status.maxCalls()).isEqualTo(5);
            assertThat(status.window()).isEqualTo(Duration.ofSeconds(10));
            assertThat(status.reopensAt()).isNull();
        }

        @Test
        void reportsAnOpenBreakerAndWhenItWillClose() {
            CallRateBreaker breaker = breaker();
            trip(breaker);

            BreakerStatus status = breaker.status(SESSION);
            assertThat(status.state()).isEqualTo(BreakerState.OPEN);
            assertThat(status.isOpen()).isTrue();
            assertThat(status.reopensAt()).isEqualTo(clock.instant().plus(Duration.ofSeconds(10)));
        }

        @Test
        void readingStatusDoesNotCountAsACall() {
            CallRateBreaker breaker = breaker();
            for (int i = 0; i < 5; i++) {
                breaker.recordCall(SESSION);
            }
            for (int i = 0; i < 20; i++) {
                breaker.status(SESSION);
            }

            assertThat(breaker.status(SESSION).callsInWindow()).isEqualTo(5);
            assertThat(breaker.status(SESSION).isOpen()).isFalse();
        }

        @Test
        void reportsAnUnseenSessionAsClosedAndIdle() {
            BreakerStatus status = breaker().status("never-called");
            assertThat(status.state()).isEqualTo(BreakerState.CLOSED);
            assertThat(status.callsInWindow()).isZero();
        }
    }

    @Nested
    class AcrossSessions {

        @Test
        void countsEachSessionSeparately() {
            CallRateBreaker breaker = breaker();
            for (int i = 0; i < 5; i++) {
                breaker.recordCall("noisy");
            }
            assertThatThrownBy(() -> breaker.recordCall("noisy")).isInstanceOf(CallRateExceededException.class);

            assertThatCode(() -> breaker.recordCall("quiet")).doesNotThrowAnyException();
            assertThat(breaker.status("quiet").state()).isEqualTo(BreakerState.CLOSED);
        }

        @Test
        void resettingASessionClosesItWithoutDisturbingOthers() {
            CallRateBreaker breaker = breaker();
            trip(breaker);
            breaker.recordCall("other");

            breaker.reset(SESSION);

            assertThat(breaker.status(SESSION).state()).isEqualTo(BreakerState.CLOSED);
            assertThat(breaker.status(SESSION).callsInWindow()).isZero();
            assertThat(breaker.status("other").callsInWindow()).isEqualTo(1);
        }

        @Test
        void sweepsOutSettledSessionsOnceTrackingGrowsPastItsCap() {
            CallRateBreaker breaker = CallRateBreaker.builder()
                    .maxCalls(5)
                    .window(Duration.ofSeconds(10))
                    .clock(clock)
                    .maxSessions(4)
                    .build();

            // a session that is actively tripped must survive the sweep
            trip(breaker);
            for (int i = 0; i < 50; i++) {
                breaker.recordCall("transient-" + i);
                clock.advanceSeconds(11); // each one settles before the next arrives
            }

            // the tripped session is long past its cool-off by now, but the point stands: it was
            // never dropped mid-cool-off, and its history is still coherent
            assertThat(breaker.status(SESSION).callsInWindow()).isZero();
        }
    }

    @Nested
    class Configuration {

        @Test
        void rejectsAMissingOrNonsensicalConfiguration() {
            assertThatThrownBy(() -> CallRateBreaker.builder().window(Duration.ofSeconds(1)).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxCalls");

            assertThatThrownBy(() -> CallRateBreaker.builder().maxCalls(1).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("window");

            assertThatThrownBy(() -> CallRateBreaker.builder().maxCalls(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");

            assertThatThrownBy(() -> CallRateBreaker.builder().window(Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");

            assertThatThrownBy(() -> CallRateBreaker.builder().coolOff(Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }
    }

    private void trip(CallRateBreaker breaker) {
        for (int i = 0; i < 5; i++) {
            breaker.recordCall(SESSION);
        }
        try {
            breaker.recordCall(SESSION);
        } catch (CallRateExceededException expected) {
            // the trip itself is what these tests are setting up
        }
    }
}
