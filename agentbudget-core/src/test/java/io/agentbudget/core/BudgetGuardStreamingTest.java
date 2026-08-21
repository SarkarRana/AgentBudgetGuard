package io.agentbudget.core;

import io.agentbudget.core.FakeStreamingLlmClient.StreamChunk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives streaming accounting the way a user would wire it: their own loop over their own
 * client's stream, with a {@link StreamSession} watching what goes past. Proves the interception
 * shape chosen in ADR 0001 end to end — checked before the stream opens, observed as it flows,
 * reconciled and charged on close.
 */
class BudgetGuardStreamingTest {

    private static final String MODEL = "fake-model";
    private static final String SESSION = "session-1";

    /** The caller's provider knowledge, in one object. */
    private static final ChunkInspector<StreamChunk> INSPECTOR =
            ChunkInspector.of(StreamChunk::textDelta, StreamChunk::usageFrame);

    /** $1 per token, and one token per character, so every expected figure reads off the text. */
    private BudgetGuard guardWithLimit(String limit) {
        ModelPricing pricing = ModelPricing.perMillionTokens("USD", 1_000_000, 1_000_000);
        return BudgetGuard.builder()
                .limit(Money.of(limit, "USD"))
                .onExceed(ExceedPolicy.STOP)
                .pricingCatalog(StaticPricingCatalog.withSingleModel(MODEL, pricing))
                .tokenEstimator(characterCount -> characterCount)
                .build();
    }

    @Test
    void anOverBudgetSessionCannotStartAGeneration() {
        BudgetGuard guard = guardWithLimit("2.00");
        FakeStreamingLlmClient client = new FakeStreamingLlmClient().thenText("never asked for");

        // spend the whole budget on a non-streaming call first
        guard.wrap(SESSION, MODEL, () -> GuardedResult.of("hi", TokenUsage.of(1, 1)));
        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("2.00", "USD"));

        assertThatThrownBy(() -> guard.openStream(SESSION, MODEL, INSPECTOR))
                .isInstanceOf(BudgetExceededException.class)
                .satisfies(e -> assertThat(((BudgetExceededException) e).currentSpend())
                        .isEqualTo(Money.of("2.00", "USD")));

        // the check happened before the stream was opened, so nothing was ever pulled
        assertThat(client.chunksPulled()).isZero();
    }

    @Test
    void aStreamThatCompletesNormallyIsChargedTheProvidersUsageFrame() {
        BudgetGuard guard = guardWithLimit("100.00");
        FakeStreamingLlmClient client = new FakeStreamingLlmClient()
                .thenText("Hello, ")
                .thenText("world")
                .thenUsageFrame(3, 7); // authoritative: 10 tokens, $10

        StringBuilder transcript = new StringBuilder();
        StreamSession<StreamChunk> session = guard.openStream(SESSION, MODEL, INSPECTOR);
        try (session) {
            for (StreamChunk chunk : client.stream("hello")) {  // the caller's own API, unchanged
                session.observe(chunk);
                if (chunk.textDelta() != null) {
                    transcript.append(chunk.textDelta());
                }
            }
        }

        assertThat(transcript).hasToString("Hello, world");
        assertThat(session.reconciledUsage().usage()).isEqualTo(TokenUsage.of(3, 7));
        assertThat(session.reconciledUsage().estimated()).isFalse();
        assertThat(session.recordedCost()).isEqualTo(Money.of("10.00", "USD"));
        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("10.00", "USD"));
    }

    @Test
    void aStreamWhoseProviderSendsNoUsageFrameIsChargedAnEstimateRatherThanNothing() {
        BudgetGuard guard = guardWithLimit("100.00");
        FakeStreamingLlmClient client = new FakeStreamingLlmClient()
                .thenText("abc")
                .thenText("de");

        StreamSession<StreamChunk> session = guard.openStream(SESSION, MODEL, INSPECTOR);
        try (session) {
            for (StreamChunk chunk : client.stream("hello")) {
                session.observe(chunk);
            }
        }

        assertThat(session.reconciledUsage().estimated()).isTrue();
        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("5.00", "USD"));
    }

    @Test
    void aStreamAbandonedPartwayThroughIsChargedForWhatItConsumed() {
        BudgetGuard guard = guardWithLimit("100.00");
        FakeStreamingLlmClient client = new FakeStreamingLlmClient()
                .thenText("abc")
                .thenText("de")
                .thenText("this part is never generated")
                .thenUsageFrame(1000, 1000);

        StreamSession<StreamChunk> session = guard.openStream(SESSION, MODEL, INSPECTOR);
        try (session) {
            int seen = 0;
            for (StreamChunk chunk : client.stream("hello")) {
                session.observe(chunk);
                if (++seen == 2) {
                    break;  // the user hit stop
                }
            }
        }

        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("5.00", "USD"));
        assertThat(client.chunksPulled()).isEqualTo(2);
    }

    @Test
    void aStreamThatErrorsMidFlightRecordsItsPartialConsumption() {
        BudgetGuard guard = guardWithLimit("100.00");
        FakeStreamingLlmClient client = new FakeStreamingLlmClient()
                .thenText("abc")
                .thenDropTheConnection();

        StreamSession<StreamChunk> session = guard.openStream(SESSION, MODEL, INSPECTOR);
        assertThatThrownBy(() -> {
            try (session) {
                for (StreamChunk chunk : client.stream("hello")) {
                    session.observe(chunk);
                }
            }
        }).hasMessageContaining("connection dropped");

        // the failure propagated, and close() still ran on the way out
        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("3.00", "USD"));
    }

    @Test
    void aProviderThatOpensWithItsUsageFrameIsChargedThatFrameAndItsClosingRestatement() {
        // Anthropic's shape: input counts up front with a placeholder output count, then a
        // closing frame that restates output only. Neither half may be lost.
        BudgetGuard guard = guardWithLimit("100.00");
        FakeStreamingLlmClient client = new FakeStreamingLlmClient()
                .thenUsageFrame(TokenUsage.of(1, 0, 1))
                .thenText("Hello, ")
                .thenText("world")
                .thenUsageFrame(TokenUsage.of(0, 0, 4));

        StreamSession<StreamChunk> session = guard.openStream(SESSION, MODEL, INSPECTOR);
        try (session) {
            for (StreamChunk chunk : client.stream("hello")) {
                session.observe(chunk);
            }
        }

        assertThat(session.reconciledUsage().usage()).isEqualTo(TokenUsage.of(1, 0, 4));
        assertThat(session.reconciledUsage().estimated()).isFalse();
        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("5.00", "USD"));
    }

    @Test
    void aStreamAbandonedAfterItsOpeningUsageFrameIsStillChargedForWhatItGenerated() {
        // the closing frame never arrives, so the text after the opening one is estimated on top
        // of it rather than given away
        BudgetGuard guard = guardWithLimit("100.00");
        FakeStreamingLlmClient client = new FakeStreamingLlmClient()
                .thenUsageFrame(TokenUsage.of(2, 0, 1))
                .thenText("abc")
                .thenText("never generated")
                .thenUsageFrame(TokenUsage.of(0, 0, 1000));

        StreamSession<StreamChunk> session = guard.openStream(SESSION, MODEL, INSPECTOR);
        try (session) {
            for (StreamChunk chunk : client.stream("hello")) {
                session.observe(chunk);
                if (chunk.textDelta() != null) {
                    break;  // the user hit stop on the first token
                }
            }
        }

        assertThat(session.reconciledUsage().usage()).isEqualTo(TokenUsage.of(2, 0, 4)); // 1 reported + 3 estimated
        assertThat(session.reconciledUsage().estimated()).isTrue();
        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("6.00", "USD"));
    }

    @Test
    void aChunkCarryingBothTextAndItsUsageFrameIsNotChargedForBoth() {
        BudgetGuard guard = guardWithLimit("100.00");
        FakeStreamingLlmClient client = new FakeStreamingLlmClient()
                .thenText("Hello, ")
                .thenTextWithUsageFrame("world", 1, 4);

        StreamSession<StreamChunk> session = guard.openStream(SESSION, MODEL, INSPECTOR);
        try (session) {
            for (StreamChunk chunk : client.stream("hello")) {
                session.observe(chunk);
            }
        }

        assertThat(session.reconciledUsage().usage()).isEqualTo(TokenUsage.of(1, 4));
        assertThat(session.reconciledUsage().estimated()).isFalse();
        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("5.00", "USD"));
    }

    @Test
    void aStreamThatErrorsAfterItsUsageFrameIsChargedThatFrameRatherThanAnEstimate() {
        BudgetGuard guard = guardWithLimit("100.00");
        FakeStreamingLlmClient client = new FakeStreamingLlmClient()
                .thenTextWithUsageFrame("abc", 2, 6)
                .thenDropTheConnection();

        StreamSession<StreamChunk> session = guard.openStream(SESSION, MODEL, INSPECTOR);
        assertThatThrownBy(() -> {
            try (session) {
                for (StreamChunk chunk : client.stream("hello")) {
                    session.observe(chunk);
                }
            }
        }).hasMessageContaining("connection dropped");

        assertThat(session.reconciledUsage().estimated()).isFalse();
        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("8.00", "USD"));
    }

    @Test
    void anEmptyStreamRecordsZeroWithoutThrowing() {
        BudgetGuard guard = guardWithLimit("100.00");

        StreamSession<StreamChunk> session = guard.openStream(SESSION, MODEL, INSPECTOR);
        try (session) {
            for (StreamChunk chunk : new FakeStreamingLlmClient().stream("hello")) {
                session.observe(chunk);
            }
        }

        assertThat(session.reconciledUsage().usage()).isEqualTo(TokenUsage.ZERO);
        assertThat(guard.spend(SESSION)).isEqualTo(Money.zero(java.util.Currency.getInstance("USD")));
    }

    @Test
    void chunksAreObservedAsTheyArriveRatherThanBufferedUpFirst() {
        BudgetGuard guard = guardWithLimit("100.00");
        FakeStreamingLlmClient client = new FakeStreamingLlmClient();

        // a response with no end: only incremental observation can terminate at all
        StreamSession<StreamChunk> session = guard.openStream(SESSION, MODEL, INSPECTOR);
        try (session) {
            int seen = 0;
            for (StreamChunk chunk : client.endlessStream()) {
                session.observe(chunk);
                // usage is available mid-stream, so nothing is waiting on the stream to end
                assertThat(session.observedUsage().usage().outputTokens()).isEqualTo(4L * ++seen);
                if (seen == 5) {
                    break;
                }
            }
        }

        assertThat(client.chunksPulled()).isEqualTo(5);
        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("20.00", "USD"));
    }

    @Test
    void closingASessionTwiceChargesTheSessionOnce() {
        BudgetGuard guard = guardWithLimit("100.00");

        StreamSession<StreamChunk> session = guard.openStream(SESSION, MODEL, INSPECTOR);
        try (session) {
            session.observe(new StreamChunk("abc", null));
            session.close();
        }

        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("3.00", "USD"));
    }

    @Test
    void aChunkArrivingAfterCloseDoesNotChangeWhatWasCharged() {
        BudgetGuard guard = guardWithLimit("100.00");

        StreamSession<StreamChunk> session = guard.openStream(SESSION, MODEL, INSPECTOR);
        session.observe(new StreamChunk("abc", null));
        session.close();
        session.observe(new StreamChunk("late emission after cancellation", null));

        assertThat(session.reconciledUsage().usage()).isEqualTo(TokenUsage.of(0, 0, 3));
        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("3.00", "USD"));
    }

    @Test
    void theRecordedCostIsNotReadableUntilTheStreamIsDone() {
        BudgetGuard guard = guardWithLimit("100.00");
        StreamSession<StreamChunk> session = guard.openStream(SESSION, MODEL, INSPECTOR);
        session.observe(new StreamChunk("abc", null));

        assertThatThrownBy(session::recordedCost)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still open");
        assertThat(session.isClosed()).isFalse();
    }

    @Test
    void streamingSpendAccumulatesAgainstTheSameSessionTotalAsNonStreamingSpend() {
        BudgetGuard guard = guardWithLimit("10.00");
        guard.wrap(SESSION, MODEL, () -> GuardedResult.of("hi", TokenUsage.of(2, 0)));

        StreamSession<StreamChunk> session = guard.openStream(SESSION, MODEL, INSPECTOR);
        try (session) {
            session.observe(new StreamChunk(null, TokenUsage.of(2, 6))); // $8, taking the session to $10
        }

        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("10.00", "USD"));
        assertThat(guard.remaining(SESSION)).isEqualTo(Money.of("0.00", "USD"));

        // that stream took the session to its limit, so the next one cannot open
        assertThatThrownBy(() -> {
            try (StreamSession<StreamChunk> overBudget = guard.openStream(SESSION, MODEL, INSPECTOR)) {
                overBudget.observe(new StreamChunk(null, TokenUsage.of(1, 1)));
            }
        }).isInstanceOf(BudgetExceededException.class);
    }

    @Test
    void openStreamRejectsMissingArguments() {
        BudgetGuard guard = guardWithLimit("10.00");

        assertThatThrownBy(() -> guard.openStream(null, MODEL, INSPECTOR))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> guard.openStream(SESSION, null, INSPECTOR))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> guard.openStream(SESSION, MODEL, null))
                .isInstanceOf(NullPointerException.class);
    }
}
