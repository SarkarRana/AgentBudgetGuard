package io.agentbudget.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The aggregator in isolation: chunks in, one reconciled usage record out. No I/O, no guard, no
 * provider types — the hardest correctness surface in the library, attacked where nothing else can
 * hide a mistake.
 *
 * <p>Each test is named for a behaviour a user could describe, not for the method it calls.
 */
class StreamingUsageAggregatorTest {

    /** One token per character, so expected numbers read straight off the text. */
    private static final TokenEstimator ONE_TOKEN_PER_CHARACTER = characterCount -> characterCount;

    private StreamingUsageAggregator aggregator() {
        return new StreamingUsageAggregator(ONE_TOKEN_PER_CHARACTER);
    }

    @Nested
    class WhenTheProviderReportsUsage {

        @Test
        void aFinalUsageFrameOverridesWhatWasAccumulatedFromText() {
            StreamingUsageAggregator aggregator = aggregator();
            aggregator.observeText("twelve chars");
            aggregator.observeUsageFrame(TokenUsage.of(40, 3));

            ReconciledUsage reconciled = aggregator.reconcile();

            assertThat(reconciled.usage()).isEqualTo(TokenUsage.of(40, 3));
            assertThat(reconciled.estimated()).isFalse();
        }

        @Test
        void aLaterUsageFrameSupersedesAnEarlierOneRatherThanAddingToIt() {
            // a provider that restates a running total reconciles to its last word, not to a sum
            StreamingUsageAggregator aggregator = aggregator();
            aggregator.observeUsageFrame(TokenUsage.of(40, 5));
            aggregator.observeUsageFrame(TokenUsage.of(40, 9));

            assertThat(aggregator.reconcile().usage()).isEqualTo(TokenUsage.of(40, 9));
        }

        @Test
        void aClosingFrameThatRestatesOnlyOutputTokensDoesNotEraseTheInputTokensAlreadyReported() {
            // Anthropic's shape: message_start carries the input counts, message_delta carries
            // only the final output count. Reading the omission as a zero would lose the prompt.
            StreamingUsageAggregator aggregator = aggregator();
            aggregator.observeUsageFrame(TokenUsage.of(1_000, 200, 1));
            aggregator.observeUsageFrame(TokenUsage.of(0, 0, 350));

            ReconciledUsage reconciled = aggregator.reconcile();

            assertThat(reconciled.usage()).isEqualTo(TokenUsage.of(1_000, 200, 350));
            assertThat(reconciled.estimated()).isFalse();
        }

        @Test
        void aStreamThatReportsUsageWithoutEverEmittingTextIsChargedTheFrameExactly() {
            StreamingUsageAggregator aggregator = aggregator();
            aggregator.observeUsageFrame(TokenUsage.of(17, 0));

            ReconciledUsage reconciled = aggregator.reconcile();

            assertThat(reconciled.usage()).isEqualTo(TokenUsage.of(17, 0));
            assertThat(reconciled.estimated()).isFalse();
        }

        @Test
        void keepAliveChunksAfterTheFinalFrameLeaveItAuthoritative() {
            StreamingUsageAggregator aggregator = aggregator();
            aggregator.observeText("Hello");
            aggregator.observeUsageFrame(TokenUsage.of(4, 2));
            aggregator.observeText(null);   // a terminating sentinel chunk
            aggregator.observeText("");     // a keep-alive

            ReconciledUsage reconciled = aggregator.reconcile();

            assertThat(reconciled.usage()).isEqualTo(TokenUsage.of(4, 2));
            assertThat(reconciled.estimated()).isFalse();
        }
    }

    @Nested
    class WhenAFrameArrivesMidStream {

        @Test
        void textGeneratedAfterAUsageFrameIsToppedUpWithAnEstimateRatherThanBeingFree() {
            // a provider that opens with usage counts, then generates: everything after the frame
            // is unaccounted for, and dropping it would give away the whole response
            StreamingUsageAggregator aggregator = aggregator();
            aggregator.observeUsageFrame(TokenUsage.of(1_000, 1));
            aggregator.observeText("abcde");

            ReconciledUsage reconciled = aggregator.reconcile();

            assertThat(reconciled.usage()).isEqualTo(TokenUsage.of(1_000, 6)); // 1 reported + 5 estimated
            assertThat(reconciled.estimated()).isTrue();
        }

        @Test
        void textObservedBeforeTheFrameIsNotChargedTwiceOnTopOfIt() {
            StreamingUsageAggregator aggregator = aggregator();
            aggregator.observeText("counted by the provider");
            aggregator.observeUsageFrame(TokenUsage.of(10, 4));
            aggregator.observeText("ab"); // only these two characters are unaccounted for

            assertThat(aggregator.reconcile().usage()).isEqualTo(TokenUsage.of(10, 6));
        }

        @Test
        void aSecondFrameAbsorbsTheTextThatFollowedTheFirstOne() {
            // the top-up is provisional: once the provider speaks again, its number wins outright
            StreamingUsageAggregator aggregator = aggregator();
            aggregator.observeUsageFrame(TokenUsage.of(1_000, 1));
            aggregator.observeText("abcde");
            assertThat(aggregator.reconcile().estimated()).isTrue();

            aggregator.observeUsageFrame(TokenUsage.of(0, 0, 12));

            ReconciledUsage reconciled = aggregator.reconcile();
            assertThat(reconciled.usage()).isEqualTo(TokenUsage.of(1_000, 12));
            assertThat(reconciled.estimated()).isFalse();
        }
    }

    @Nested
    class WhenNoFrameEverArrives {

        @Test
        void theTotalIsEstimatedFromTheTextObserved() {
            StreamingUsageAggregator aggregator = aggregator();
            aggregator.observeText("abc");
            aggregator.observeText("de");

            ReconciledUsage reconciled = aggregator.reconcile();

            assertThat(reconciled.usage()).isEqualTo(TokenUsage.of(0, 0, 5));
            assertThat(reconciled.estimated()).isTrue();
        }

        @Test
        void chunksCarryingNoTextContributeNothing() {
            StreamingUsageAggregator aggregator = aggregator();
            aggregator.observeText(null);   // a role-only opening chunk
            aggregator.observeText("");     // a keep-alive
            aggregator.observeText("abcd");

            assertThat(aggregator.reconcile().usage()).isEqualTo(TokenUsage.of(0, 0, 4));
        }

        @Test
        void theDefaultHeuristicEstimatesRoughlyFourCharactersPerToken() {
            StreamingUsageAggregator aggregator =
                    new StreamingUsageAggregator(TokenEstimator.CHARACTERS_PER_TOKEN_HEURISTIC);
            aggregator.observeText("a".repeat(400));

            assertThat(aggregator.reconcile().usage().outputTokens()).isEqualTo(100);
        }

        @Test
        void anyOutputAtAllEstimatesAsAtLeastOneTokenUnderTheDefaultHeuristic() {
            StreamingUsageAggregator aggregator =
                    new StreamingUsageAggregator(TokenEstimator.CHARACTERS_PER_TOKEN_HEURISTIC);
            aggregator.observeText("a");

            assertThat(aggregator.reconcile().usage().outputTokens()).isEqualTo(1);
        }
    }

    @Nested
    class WhenTheStreamEndsBadly {

        @Test
        void aStreamAbortedMidGenerationIsChargedForTheTextProducedUpToTheAbort() {
            // an abort is nothing more than reconciling earlier than usual
            StreamingUsageAggregator aggregator = aggregator();
            aggregator.observeText("abc");
            aggregator.observeText("de");
            // the consumer hit stop here; the frame the provider would have sent never arrives

            ReconciledUsage reconciled = aggregator.reconcile();

            assertThat(reconciled.usage()).isEqualTo(TokenUsage.of(0, 0, 5));
            assertThat(reconciled.estimated()).isTrue();
        }

        @Test
        void aStreamAbortedAfterItsOpeningFrameIsChargedThatFrameAndWhatFollowedIt() {
            StreamingUsageAggregator aggregator = aggregator();
            aggregator.observeUsageFrame(TokenUsage.of(1_000, 200, 1));
            aggregator.observeText("half a res");
            // cancelled: no closing frame will ever restate the output count

            ReconciledUsage reconciled = aggregator.reconcile();

            assertThat(reconciled.usage()).isEqualTo(TokenUsage.of(1_000, 200, 11));
            assertThat(reconciled.estimated()).isTrue();
        }

        @Test
        void aStreamThatErrorsMidFlightIsChargedItsPartialConsumption() {
            StreamingUsageAggregator aggregator = aggregator();
            aggregator.observeText("abc");
            // the connection dropped here

            assertThat(aggregator.reconcile().usage()).isEqualTo(TokenUsage.of(0, 0, 3));
        }

        @Test
        void aStreamThatProducedNothingReconcilesToZeroRatherThanThrowing() {
            ReconciledUsage reconciled = aggregator().reconcile();

            assertThat(reconciled.usage()).isEqualTo(TokenUsage.ZERO);
            assertThat(reconciled.estimated()).isTrue();
        }

        @Test
        void aStreamThatFailedBeforeProducingAnythingIsChargedZeroRatherThanBeingRejected() {
            StreamingUsageAggregator aggregator = aggregator();
            aggregator.observeText(null); // the opening chunk arrived, then the connection died

            assertThat(aggregator.reconcile().usage()).isEqualTo(TokenUsage.ZERO);
        }
    }

    @Nested
    class WhenReconcilingRepeatedly {

        @Test
        void reconcilingMidStreamReportsWhatHasBeenSeenSoFarWithoutEndingTheStream() {
            StreamingUsageAggregator aggregator = aggregator();
            aggregator.observeText("abc");

            assertThat(aggregator.reconcile().usage()).isEqualTo(TokenUsage.of(0, 0, 3));

            aggregator.observeText("de");

            assertThat(aggregator.reconcile().usage()).isEqualTo(TokenUsage.of(0, 0, 5));
        }

        @Test
        void askingTwiceInARowGivesTheSameAnswerRatherThanConsumingWhatWasObserved() {
            StreamingUsageAggregator aggregator = aggregator();
            aggregator.observeUsageFrame(TokenUsage.of(7, 1));
            aggregator.observeText("abc");

            ReconciledUsage first = aggregator.reconcile();
            ReconciledUsage second = aggregator.reconcile();

            assertThat(first).isEqualTo(second).isEqualTo(aggregator.reconcile());
        }
    }

    @Nested
    class WhenItIsGivenBadInput {

        @Test
        void anEstimatorThatReturnsANegativeCountIsFlooredAtZeroRatherThanLosingTheCharge() {
            // an exception out of reconcile() would surface inside close() and drop the record
            StreamingUsageAggregator aggregator = new StreamingUsageAggregator(characterCount -> -5);
            aggregator.observeUsageFrame(TokenUsage.of(9, 2));
            aggregator.observeText("abc");

            assertThat(aggregator.reconcile().usage()).isEqualTo(TokenUsage.of(9, 2));
        }

        @Test
        void anAbsurdEstimateSaturatesInsteadOfOverflowingIntoANegativeTotal() {
            StreamingUsageAggregator aggregator = new StreamingUsageAggregator(c -> Long.MAX_VALUE);
            aggregator.observeUsageFrame(TokenUsage.of(1, 1));
            aggregator.observeText("a");

            assertThat(aggregator.reconcile().usage().outputTokens()).isEqualTo(Long.MAX_VALUE);
        }

        @Test
        void aNullUsageFrameIsRejectedRatherThanWipingOutTheFrameAlreadyRecorded() {
            StreamingUsageAggregator aggregator = aggregator();
            aggregator.observeUsageFrame(TokenUsage.of(5, 5));

            assertThatThrownBy(() -> aggregator.observeUsageFrame(null))
                    .isInstanceOf(NullPointerException.class);
            assertThat(aggregator.reconcile().usage()).isEqualTo(TokenUsage.of(5, 5));
        }

        @Test
        void anAggregatorWithoutAnEstimatorIsRejectedAtConstruction() {
            assertThatThrownBy(() -> new StreamingUsageAggregator(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
