package io.agentbudget.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * A hand-written fake standing in for a streaming provider SDK. {@link #stream} hands back a
 * lazily-drained {@link Iterable}, so a test can assert how much of the response was actually
 * pulled — which is how "the consumer walked away halfway through" is expressed here.
 */
final class FakeStreamingLlmClient {

    private final List<Scripted> script = new ArrayList<>();
    private int chunksPulled = 0;

    FakeStreamingLlmClient thenText(String textDelta) {
        script.add(new Scripted(new StreamChunk(textDelta, null), null));
        return this;
    }

    FakeStreamingLlmClient thenUsageFrame(long inputTokens, long outputTokens) {
        script.add(new Scripted(new StreamChunk(null, TokenUsage.of(inputTokens, outputTokens)), null));
        return this;
    }

    /**
     * A chunk carrying text and its usage frame at once, the way providers that attach usage to
     * the final content chunk do.
     */
    FakeStreamingLlmClient thenTextWithUsageFrame(String textDelta, long inputTokens, long outputTokens) {
        script.add(new Scripted(new StreamChunk(textDelta, TokenUsage.of(inputTokens, outputTokens)), null));
        return this;
    }

    /** A usage frame carrying cached input tokens as well, for the opening frame of a cached call. */
    FakeStreamingLlmClient thenUsageFrame(TokenUsage usage) {
        script.add(new Scripted(new StreamChunk(null, usage), null));
        return this;
    }

    FakeStreamingLlmClient thenDropTheConnection() {
        script.add(new Scripted(null, new RuntimeException("connection dropped mid-stream")));
        return this;
    }

    Iterable<StreamChunk> stream(String prompt) {
        return () -> new Iterator<>() {
            private int position = 0;

            @Override
            public boolean hasNext() {
                return position < script.size();
            }

            @Override
            public StreamChunk next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Scripted scripted = script.get(position++);
                chunksPulled++;
                if (scripted.failure != null) {
                    throw scripted.failure;
                }
                return scripted.chunk;
            }
        };
    }

    /**
     * A response that never ends, for asserting that observation is incremental rather than
     * buffer-then-process. A test that pulls a bounded prefix of this and terminates proves the
     * guard never needed the whole stream in hand.
     */
    Iterable<StreamChunk> endlessStream() {
        return () -> new Iterator<>() {
            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public StreamChunk next() {
                chunksPulled++;
                return new StreamChunk("tok ", null);
            }
        };
    }

    int chunksPulled() {
        return chunksPulled;
    }

    /**
     * A provider chunk: some text, or an authoritative usage frame, or neither. Both fields are
     * nullable, the way real SDK chunk accessors are.
     */
    record StreamChunk(String textDelta, TokenUsage usageFrame) {
    }

    private record Scripted(StreamChunk chunk, RuntimeException failure) {
    }
}
