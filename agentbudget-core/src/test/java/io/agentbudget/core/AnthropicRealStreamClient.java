package io.agentbudget.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A thin, test-only client for Anthropic's streaming Messages endpoint, used solely by
 * {@link RealProviderStreamingAccuracyTest} (issue 06). Mirrors {@link OpenAiRealStreamClient}'s
 * role: turn a real SSE response into the shape a caller's {@link ChunkInspector} would see, and
 * keep aside what Anthropic itself reported as usage as independent ground truth.
 *
 * <p>Anthropic's shape is the one {@link StreamingUsageAggregator}'s merge logic exists for: usage
 * arrives twice, an opening {@code message_start} frame with input counts and a placeholder output
 * count, and a closing {@code message_delta} frame that restates output only. Both frames are fed
 * through, unmerged — merging is {@code StreamingUsageAggregator}'s job, not this client's.
 */
final class AnthropicRealStreamClient {

    private static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String apiKey;

    AnthropicRealStreamClient(String apiKey) {
        this.apiKey = apiKey;
    }

    /** One SSE data event, reduced to the two things streaming accounting needs. */
    record Chunk(String textDelta, TokenUsage usageFrame) {
    }

    /** Everything observed on the wire, plus the usage Anthropic itself reported as the final word. */
    record StreamOutcome(List<Chunk> chunks, TokenUsage reportedUsage) {
    }

    StreamOutcome stream(String model, String prompt, int maxTokens) {
        String requestBody = """
                {"model":%s,"max_tokens":%d,"stream":true,\
                "messages":[{"role":"user","content":%s}]}""".formatted(
                MinimalJson.quote(model), maxTokens, MinimalJson.quote(prompt));

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofSeconds(60))
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Anthropic request failed: HTTP " + response.statusCode() + " "
                        + response.body().collect(Collectors.joining("\n")));
            }
            return readEvents(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException("Anthropic streaming request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Anthropic streaming request interrupted", e);
        }
    }

    private StreamOutcome readEvents(Stream<String> lines) {
        List<Chunk> chunks = new ArrayList<>();
        long[] input = {0};
        long[] cachedInput = {0};
        long[] output = {0};
        String[] currentEvent = {""};

        lines.forEach(line -> {
            if (line.startsWith("event: ")) {
                currentEvent[0] = line.substring("event: ".length());
                return;
            }
            if (!line.startsWith("data: ")) {
                return;
            }
            Object node = MinimalJson.parse(line.substring("data: ".length()));

            switch (currentEvent[0]) {
                case "message_start" -> {
                    Object usage = MinimalJson.get(node, "message", "usage");
                    input[0] = MinimalJson.asLong(MinimalJson.get(usage, "input_tokens"));
                    cachedInput[0] = MinimalJson.asLong(MinimalJson.get(usage, "cache_read_input_tokens"));
                    output[0] = MinimalJson.asLong(MinimalJson.get(usage, "output_tokens"));
                    chunks.add(new Chunk(null, new TokenUsage(input[0], cachedInput[0], output[0])));
                }
                case "content_block_delta" -> {
                    Object text = MinimalJson.get(node, "delta", "text");
                    chunks.add(new Chunk(text instanceof String s ? s : null, null));
                }
                case "message_delta" -> {
                    Object usage = MinimalJson.get(node, "usage");
                    if (usage != null) {
                        output[0] = MinimalJson.asLong(MinimalJson.get(usage, "output_tokens"));
                        chunks.add(new Chunk(null, new TokenUsage(input[0], cachedInput[0], output[0])));
                    }
                }
                default -> {
                    // ping, content_block_start/stop, message_stop: nothing this suite needs
                }
            }
        });

        return new StreamOutcome(chunks, new TokenUsage(input[0], cachedInput[0], output[0]));
    }
}
