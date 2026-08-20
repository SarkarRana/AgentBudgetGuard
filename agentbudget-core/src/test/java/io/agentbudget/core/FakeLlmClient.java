package io.agentbudget.core;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A hand-written fake standing in for a real provider SDK. Each call to {@link #chat} pops the
 * next scripted response, so a test can drive a specific sequence of successes and failures.
 */
final class FakeLlmClient {

    private final Deque<Scripted> script = new ArrayDeque<>();
    private int callCount = 0;

    FakeLlmClient thenReply(String reply, long inputTokens, long outputTokens) {
        script.addLast(new Scripted(Kind.SUCCESS, reply, TokenUsage.of(inputTokens, outputTokens), null));
        return this;
    }

    FakeLlmClient thenFailBeforeReachingProvider() {
        script.addLast(new Scripted(Kind.FAIL_BEFORE_PROVIDER, null, null, new RuntimeException("connection refused")));
        return this;
    }

    FakeLlmClient thenFailAfterGeneratingTokens(long inputTokens, long outputTokens) {
        script.addLast(new Scripted(Kind.FAIL_AFTER_GENERATION, null, TokenUsage.of(inputTokens, outputTokens),
                new RuntimeException("connection dropped mid-response")));
        return this;
    }

    GuardedResult<String> chat(String prompt) {
        callCount++;
        Scripted next = script.pollFirst();
        if (next == null) {
            throw new IllegalStateException("FakeLlmClient has no scripted response left for call " + callCount);
        }
        return switch (next.kind) {
            case SUCCESS -> new GuardedResult<>(next.reply, next.usage);
            case FAIL_BEFORE_PROVIDER -> throw next.cause;
            case FAIL_AFTER_GENERATION -> throw new PartialUsageException(next.usage, next.cause);
        };
    }

    int callCount() {
        return callCount;
    }

    private enum Kind {
        SUCCESS, FAIL_BEFORE_PROVIDER, FAIL_AFTER_GENERATION
    }

    private record Scripted(Kind kind, String reply, TokenUsage usage, RuntimeException cause) {
    }
}
