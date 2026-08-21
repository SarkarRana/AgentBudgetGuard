package io.agentbudget.core;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The only evidence that streaming accounting is right rather than merely internally consistent
 * (issue 06). Every other streaming test — {@link BudgetGuardStreamingTest},
 * {@link StreamingUsageAggregatorTest} — proves the pipeline agrees with itself against a fake
 * client the test also wrote. This suite drives the real {@link ChunkInspector} /
 * {@link StreamingUsageAggregator} / {@link BudgetGuard} pipeline against a real streamed response
 * from OpenAI and Anthropic, and checks the reconciled totals against what the provider itself
 * reported as usage — ground truth this codebase does not control.
 *
 * <p>This is the acceptance bar for slice 5 (streaming reconciliation): if it fails, nothing built
 * on top of streaming should be trusted until it passes again.
 *
 * <h2>Running it</h2>
 *
 * Excluded from the default build and from CI via the {@code real-provider} JUnit tag (see the
 * {@code excludedGroups} in {@code agentbudget-core/pom.xml}). To run it, supply real API keys and
 * opt into the {@code real-provider-tests} Maven profile:
 *
 * <pre>{@code
 * OPENAI_API_KEY=sk-... ANTHROPIC_API_KEY=sk-ant-... \
 *   mvn -pl agentbudget-core -P real-provider-tests test -Dtest=RealProviderStreamingAccuracyTest
 * }</pre>
 *
 * <p>Either key may be supplied without the other — the tests for the other provider skip cleanly
 * with a message explaining why, rather than failing.
 *
 * <p>See {@code docs/real-provider-accuracy-suite.md} for what each run costs and how spend is
 * kept well under the project's development-cycle budget.
 */
@Tag("real-provider")
class RealProviderStreamingAccuracyTest {

    /** The acceptance bar from issue 06: reconciled counts within one percent of the provider's own. */
    private static final double TOLERANCE = 0.01;

    private static final String OPENAI_MODEL = "gpt-4o";
    private static final String ANTHROPIC_MODEL = "claude-haiku-4-5";

    private static final ChunkInspector<OpenAiRealStreamClient.Chunk> OPENAI_INSPECTOR =
            ChunkInspector.of(OpenAiRealStreamClient.Chunk::textDelta, OpenAiRealStreamClient.Chunk::usageFrame);

    private static final ChunkInspector<AnthropicRealStreamClient.Chunk> ANTHROPIC_INSPECTOR =
            ChunkInspector.of(AnthropicRealStreamClient.Chunk::textDelta, AnthropicRealStreamClient.Chunk::usageFrame);

    @Test
    void openAiShortReplyReconcilesWithinOnePercentOfOpenAisReportedUsage() {
        String apiKey = requireOpenAiKey();

        OpenAiRealStreamClient.StreamOutcome outcome = new OpenAiRealStreamClient(apiKey)
                .stream(OPENAI_MODEL, "Reply with exactly one word: pineapple.", 10);

        StreamSession<OpenAiRealStreamClient.Chunk> session = runThroughGuard(
                "openai-short", OPENAI_MODEL, OPENAI_INSPECTOR, outcome.chunks());

        assertReconciledMatchesProvider(session, outcome.reportedUsage());
    }

    @Test
    void openAiLongerGenerationReconcilesWithinOnePercentOfOpenAisReportedUsage() {
        String apiKey = requireOpenAiKey();

        OpenAiRealStreamClient.StreamOutcome outcome = new OpenAiRealStreamClient(apiKey)
                .stream(OPENAI_MODEL, "Write a 150-word explanation of how a hash map works.", 300);

        StreamSession<OpenAiRealStreamClient.Chunk> session = runThroughGuard(
                "openai-long", OPENAI_MODEL, OPENAI_INSPECTOR, outcome.chunks());

        assertReconciledMatchesProvider(session, outcome.reportedUsage());
        // a multi-chunk reply is what proves this isn't just a one-chunk fluke
        assertThat(outcome.chunks().size()).isGreaterThan(5);
    }

    @Test
    void anthropicShortReplyReconcilesWithinOnePercentOfAnthropicsReportedUsage() {
        String apiKey = requireAnthropicKey();

        AnthropicRealStreamClient.StreamOutcome outcome = new AnthropicRealStreamClient(apiKey)
                .stream(ANTHROPIC_MODEL, "Reply with exactly one word: pineapple.", 10);

        StreamSession<AnthropicRealStreamClient.Chunk> session = runThroughGuard(
                "anthropic-short", ANTHROPIC_MODEL, ANTHROPIC_INSPECTOR, outcome.chunks());

        assertReconciledMatchesProvider(session, outcome.reportedUsage());
    }

    @Test
    void anthropicLongerGenerationReconcilesWithinOnePercentOfAnthropicsReportedUsage() {
        String apiKey = requireAnthropicKey();

        AnthropicRealStreamClient.StreamOutcome outcome = new AnthropicRealStreamClient(apiKey)
                .stream(ANTHROPIC_MODEL, "Write a 150-word explanation of how a hash map works.", 300);

        StreamSession<AnthropicRealStreamClient.Chunk> session = runThroughGuard(
                "anthropic-long", ANTHROPIC_MODEL, ANTHROPIC_INSPECTOR, outcome.chunks());

        assertReconciledMatchesProvider(session, outcome.reportedUsage());
        // Anthropic's opening usage frame plus closing restatement — the merge path in
        // StreamingUsageAggregator — only actually runs when there is more than one frame
        assertThat(outcome.chunks().stream().filter(c -> c.usageFrame() != null).count()).isGreaterThanOrEqualTo(2);
    }

    private static String requireOpenAiKey() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "OPENAI_API_KEY not set; skipping real-provider OpenAI accuracy check");
        return apiKey;
    }

    private static String requireAnthropicKey() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "ANTHROPIC_API_KEY not set; skipping real-provider Anthropic accuracy check");
        return apiKey;
    }

    private <T> StreamSession<T> runThroughGuard(String sessionId, String model, ChunkInspector<T> inspector,
                                                  List<T> chunks) {
        BudgetGuard guard = BudgetGuard.builder()
                .limit(Money.of("20.00", "USD"))
                .onExceed(ExceedPolicy.STOP)
                .pricingCatalog(BuiltInPricingCatalog.catalog())
                .build();

        StreamSession<T> session = guard.openStream(sessionId, model, inspector);
        try (session) {
            for (T chunk : chunks) {
                session.observe(chunk);
            }
        }
        return session;
    }

    private void assertReconciledMatchesProvider(StreamSession<?> session, TokenUsage providerReported) {
        ReconciledUsage reconciled = session.reconciledUsage();

        // the provider always sent an authoritative frame in every scenario this suite drives, so
        // nothing here should have fallen back to the character-count estimator
        assertThat(reconciled.estimated()).as("reconciled usage should be authoritative, not estimated").isFalse();

        TokenUsage actual = reconciled.usage();
        assertWithinTolerance("input tokens", actual.inputTokens(), providerReported.inputTokens());
        assertWithinTolerance("cached input tokens", actual.cachedInputTokens(), providerReported.cachedInputTokens());
        assertWithinTolerance("output tokens", actual.outputTokens(), providerReported.outputTokens());

        System.out.printf("[real-provider-accuracy] reconciled=%s reported=%s cost=%s%n",
                actual, providerReported, session.recordedCost());
    }

    private void assertWithinTolerance(String label, long actual, long expected) {
        if (expected == 0) {
            assertThat(actual).as(label).isZero();
            return;
        }
        double relativeError = Math.abs(actual - expected) / (double) expected;
        assertThat(relativeError)
                .as("%s: guard reconciled %d, provider reported %d (%.2f%% off)",
                        label, actual, expected, relativeError * 100)
                .isLessThanOrEqualTo(TOLERANCE);
    }
}
