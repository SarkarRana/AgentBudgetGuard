package io.agentbudget.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tests {@link MinimalJson} against the actual shapes OpenAI and Anthropic send, so the
 * real-provider suite in {@link RealProviderStreamingAccuracyTest} rests on a parser verified
 * without spending anything or needing an API key.
 */
class MinimalJsonTest {

    @Test
    void parsesAnOpenAiStyleDeltaChunk() {
        Object node = MinimalJson.parse("""
                {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}],"usage":null}
                """);

        assertThat(MinimalJson.get(node, "choices", 0, "delta", "content")).isEqualTo("Hello");
        assertThat(MinimalJson.get(node, "usage")).isNull();
    }

    @Test
    void parsesAnOpenAiStyleFinalUsageChunk() {
        Object node = MinimalJson.parse("""
                {"id":"chatcmpl-1","choices":[],"usage":{"prompt_tokens":12,"completion_tokens":34,"total_tokens":46,"prompt_tokens_details":{"cached_tokens":8}}}
                """);

        assertThat(MinimalJson.asLong(MinimalJson.get(node, "usage", "prompt_tokens"))).isEqualTo(12L);
        assertThat(MinimalJson.asLong(MinimalJson.get(node, "usage", "completion_tokens"))).isEqualTo(34L);
        assertThat(MinimalJson.asLong(MinimalJson.get(node, "usage", "prompt_tokens_details", "cached_tokens"))).isEqualTo(8L);
    }

    @Test
    void parsesAnAnthropicStyleMessageStartEvent() {
        Object node = MinimalJson.parse("""
                {"type":"message_start","message":{"id":"msg_1","role":"assistant","content":[],\
                "usage":{"input_tokens":25,"cache_read_input_tokens":0,"output_tokens":1}}}
                """);

        assertThat(MinimalJson.asLong(MinimalJson.get(node, "message", "usage", "input_tokens"))).isEqualTo(25L);
    }

    @Test
    void parsesAnAnthropicStyleContentBlockDeltaEvent() {
        Object node = MinimalJson.parse("""
                {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi there"}}
                """);

        assertThat(MinimalJson.get(node, "delta", "text")).isEqualTo("Hi there");
    }

    @Test
    void handlesEscapesNestingAndMissingPathsGracefully() {
        Object node = MinimalJson.parse("""
                {"text":"line one\\nline two \\"quoted\\"","nested":{"list":[1,2,3]},"flag":true,"absent":null}
                """);

        assertThat(MinimalJson.get(node, "text")).isEqualTo("line one\nline two \"quoted\"");
        assertThat(MinimalJson.get(node, "nested", "list", 2)).isEqualTo(3L);
        assertThat(MinimalJson.get(node, "flag")).isEqualTo(Boolean.TRUE);
        assertThat(MinimalJson.get(node, "absent")).isNull();
        assertThat(MinimalJson.get(node, "nested", "missing", "deeper")).isNull();
    }

    @Test
    void quoteEscapesForRoundTripBackIntoARequestBody() {
        String quoted = MinimalJson.quote("say \"hi\"\nnow");

        Object parsedBack = MinimalJson.parse("{\"prompt\":" + quoted + "}");
        assertThat(MinimalJson.get(parsedBack, "prompt")).isEqualTo("say \"hi\"\nnow");
    }
}
