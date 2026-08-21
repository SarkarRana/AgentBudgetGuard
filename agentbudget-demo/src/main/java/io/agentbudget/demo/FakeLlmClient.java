package io.agentbudget.demo;

import io.agentbudget.core.TokenUsage;
import org.springframework.stereotype.Component;

/**
 * Stands in for a real provider SDK so this demo runs with no API key and no network call. A
 * real integration would call OpenAI or Anthropic here and read its usage off the response;
 * everything downstream of {@link #chat} -- the budget check, the charge, the 402 when a
 * session runs out -- behaves identically either way.
 *
 * <p>Usage is fixed rather than derived from the prompt, so the same handful of requests always
 * cross the same limit -- see the demo's README for the exact numbers.
 */
@Component
public class FakeLlmClient {

    private static final TokenUsage USAGE_PER_CALL = TokenUsage.of(50, 75);

    public Response chat(String prompt) {
        String reply = "Here's a fake AI-generated reply to: \"%s\"".formatted(prompt);
        return new Response(reply, USAGE_PER_CALL);
    }

    public record Response(String text, TokenUsage usage) {
    }
}
