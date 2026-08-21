package io.agentbudget.core;

import io.agentbudget.core.FakeStreamingLlmClient.StreamChunk;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the guard the way a client with its own retry policy does: the retry loop lives outside
 * the guard, so one logical call arrives here once per attempt.
 *
 * <p>The distinction under test is between attempts that consumed tokens and attempts that did
 * not. Two connection failures and a success is one charge; three attempts that each generated
 * tokens is three.
 */
class BudgetGuardRetryIdempotencyTest {

    private static final String MODEL = "fake-model";
    private static final String SESSION = "session-1";

    private BudgetGuard guard() {
        ModelPricing pricing = ModelPricing.perMillionTokens("USD", 1_000_000, 1_000_000); // $1 per token
        return BudgetGuard.builder()
                .limit(Money.of("100.00", "USD"))
                .onExceed(ExceedPolicy.STOP)
                .pricingCatalog(StaticPricingCatalog.withSingleModel(MODEL, pricing))
                .build();
    }

    /**
     * Stands in for an HTTP client's retry policy: retries transport failures up to
     * {@code attempts} times, gives up immediately on a budget stop, and never sleeps.
     */
    private static <T> T retrying(int attempts, Supplier<T> call) {
        RuntimeException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return call.get();
            } catch (BudgetExceededException e) {
                throw e;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last;
    }

    @Test
    void twoConnectionFailuresThenASuccessIsChargedOnce() {
        BudgetGuard guard = guard();
        FakeLlmClient client = new FakeLlmClient()
                .thenFailBeforeReachingProvider()
                .thenFailBeforeReachingProvider()
                .thenReply("hi", 1, 1); // costs $2
        CallId callId = CallId.of("logical-call-1");

        String reply = retrying(3, () -> guard.wrap(SESSION, MODEL, callId, () -> client.chat("hello")));

        assertThat(reply).isEqualTo("hi");
        assertThat(client.callCount()).isEqualTo(3);
        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("2.00", "USD"));
    }

    @Test
    void threeAttemptsThatEachConsumedTokensAreChargedThreeTimes() {
        BudgetGuard guard = guard();
        FakeLlmClient client = new FakeLlmClient()
                .thenFailAfterGeneratingTokens(1, 1) // costs $2
                .thenFailAfterGeneratingTokens(1, 1) // costs $2
                .thenReply("hi", 1, 1);              // costs $2

        // each attempt is its own provider attempt, so each gets its own generated id
        String reply = retrying(3, () -> guard.wrap(SESSION, MODEL, () -> client.chat("hello")));

        assertThat(reply).isEqualTo("hi");
        assertThat(client.callCount()).isEqualTo(3);
        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("6.00", "USD"));
    }

    @Test
    void recordingTheSameAttemptTwiceDoesNotDoubleTheTotal() {
        BudgetGuard guard = guard();
        FakeLlmClient client = new FakeLlmClient()
                .thenReply("hi", 1, 1)   // costs $2
                .thenReply("hi", 1, 1);  // the duplicate delivery, same logical attempt
        CallId callId = CallId.of("attempt-1");

        guard.wrap(SESSION, MODEL, callId, () -> client.chat("hello"));
        guard.wrap(SESSION, MODEL, callId, () -> client.chat("hello"));

        assertThat(client.callCount()).isEqualTo(2);
        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("2.00", "USD"));
    }

    @Test
    void aDuplicateAttemptReportingDifferentUsageStillChargesTheFirstAmount() {
        BudgetGuard guard = guard();
        FakeLlmClient client = new FakeLlmClient()
                .thenReply("hi", 1, 1)   // costs $2
                .thenReply("hi", 5, 5);  // the replay claims $10 of usage
        CallId callId = CallId.of("attempt-1");

        guard.wrap(SESSION, MODEL, callId, () -> client.chat("hello"));
        guard.wrap(SESSION, MODEL, callId, () -> client.chat("hello"));

        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("2.00", "USD"));
    }

    @Test
    void anAttemptThatConsumedNothingLeavesItsIdFreeForTheNextOne() {
        BudgetGuard guard = guard();
        FakeLlmClient client = new FakeLlmClient()
                .thenReply("free", 0, 0) // reached nothing, consumed nothing
                .thenReply("hi", 1, 1);  // costs $2
        CallId callId = CallId.of("attempt-1");

        guard.wrap(SESSION, MODEL, callId, () -> client.chat("hello"));
        assertThat(guard.spend(SESSION)).isEqualTo(Money.zero(java.util.Currency.getInstance("USD")));

        guard.wrap(SESSION, MODEL, callId, () -> client.chat("hello"));
        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("2.00", "USD"));
    }

    @Test
    void idempotencyIsScopedToOneSession() {
        BudgetGuard guard = guard();
        FakeLlmClient client = new FakeLlmClient()
                .thenReply("hi", 1, 1)
                .thenReply("hi", 1, 1);
        CallId callId = CallId.of("attempt-1");

        guard.wrap("session-a", MODEL, callId, () -> client.chat("hello"));
        guard.wrap("session-b", MODEL, callId, () -> client.chat("hello"));

        assertThat(guard.spend("session-a")).isEqualTo(Money.of("2.00", "USD"));
        assertThat(guard.spend("session-b")).isEqualTo(Money.of("2.00", "USD"));
    }

    @Test
    void aReplayedStreamUnderOneCallIdIsChargedOnce() {
        BudgetGuard guard = guard();
        CallId callId = CallId.of("stream-attempt-1");
        ChunkInspector<StreamChunk> inspector =
                ChunkInspector.of(StreamChunk::textDelta, StreamChunk::usageFrame);

        for (int attempt = 0; attempt < 2; attempt++) {
            FakeStreamingLlmClient client = new FakeStreamingLlmClient()
                    .thenText("hello")
                    .thenUsageFrame(1, 1); // authoritative: costs $2
            try (StreamSession<StreamChunk> session = guard.openStream(SESSION, MODEL, callId, inspector)) {
                for (StreamChunk chunk : client.stream("hello")) {
                    session.observe(chunk);
                }
            }
        }

        assertThat(guard.spend(SESSION)).isEqualTo(Money.of("2.00", "USD"));
    }
}
