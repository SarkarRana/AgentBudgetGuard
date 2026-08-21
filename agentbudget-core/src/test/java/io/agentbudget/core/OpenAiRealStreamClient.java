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
 * A thin, test-only client for OpenAI's streaming chat completions endpoint, used solely by
 * {@link RealProviderStreamingAccuracyTest} (issue 06). Its only job is to turn a real SSE
 * response into the same shape a caller's {@link ChunkInspector} would see, and to keep aside
 * what OpenAI itself reported as usage so the test has independent ground truth to check the
 * guard's reconciliation against. Not a general-purpose client, not part of the library's public
 * surface, and not built for anything beyond this one measurement.
 */
final class OpenAiRealStreamClient {

    private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/chat/completions");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String apiKey;

    OpenAiRealStreamClient(String apiKey) {
        this.apiKey = apiKey;
    }

    /** One SSE data event, reduced to the two things streaming accounting needs. */
    record Chunk(String textDelta, TokenUsage usageFrame) {
    }

    /** Everything observed on the wire, plus the usage OpenAI itself reported as the final word. */
    record StreamOutcome(List<Chunk> chunks, TokenUsage reportedUsage) {
    }

    StreamOutcome stream(String model, String prompt, int maxTokens) {
        String requestBody = """
                {"model":%s,"messages":[{"role":"user","content":%s}],"max_tokens":%d,\
                "stream":true,"stream_options":{"include_usage":true}}""".formatted(
                MinimalJson.quote(model), MinimalJson.quote(prompt), maxTokens);

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("OpenAI request failed: HTTP " + response.statusCode() + " "
                        + response.body().collect(Collectors.joining("\n")));
            }
            return readEvents(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException("OpenAI streaming request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("OpenAI streaming request interrupted", e);
        }
    }

    private StreamOutcome readEvents(Stream<String> lines) {
        List<Chunk> chunks = new ArrayList<>();
        TokenUsage[] reported = {null};

        lines.forEach(line -> {
            if (!line.startsWith("data: ")) {
                return;
            }
            String payload = line.substring("data: ".length());
            if (payload.equals("[DONE]")) {
                return;
            }

            Object node = MinimalJson.parse(payload);
            Object choices = MinimalJson.get(node, "choices");
            String text = null;
            if (choices instanceof List<?> list && !list.isEmpty()) {
                Object content = MinimalJson.get(node, "choices", 0, "delta", "content");
                text = content instanceof String s ? s : null;
            }

            Object usageNode = MinimalJson.get(node, "usage");
            TokenUsage frame = null;
            if (usageNode != null) {
                frame = new TokenUsage(
                        MinimalJson.asLong(MinimalJson.get(usageNode, "prompt_tokens")),
                        MinimalJson.asLong(MinimalJson.get(usageNode, "prompt_tokens_details", "cached_tokens")),
                        MinimalJson.asLong(MinimalJson.get(usageNode, "completion_tokens")));
                reported[0] = frame;
            }

            chunks.add(new Chunk(text, frame));
        });

        if (reported[0] == null) {
            throw new IllegalStateException(
                    "OpenAI stream never sent a usage frame; check that stream_options.include_usage was honoured");
        }
        return new StreamOutcome(chunks, reported[0]);
    }
}
