package io.agentbudget.core;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reads the two things streaming accounting needs out of one of the caller's chunks: the text
 * it carried, and the authoritative usage frame it carried if it was the frame-bearing chunk.
 *
 * <p>This is the whole provider-specific surface of streaming. Core cannot name a Spring AI
 * {@code ChatResponse} or an OpenAI {@code ChatCompletionChunk}, so the caller supplies one
 * inspector at {@link BudgetGuard#openStream} time and the consumption loop stays a bare
 * {@link StreamSession#observe}.
 */
public interface ChunkInspector<T> {

    /**
     * The text this chunk contributed to the response, or {@code null}/empty if it carried none
     * (a role-only opening chunk, a keep-alive, or the final usage frame itself).
     */
    String textDelta(T chunk);

    /**
     * The provider's authoritative token counts if this chunk carried them, otherwise empty.
     * When one arrives it overrides everything accumulated from text deltas.
     */
    Optional<TokenUsage> usageFrame(T chunk);

    /**
     * Builds an inspector from two accessors on the caller's chunk type. Both may return
     * {@code null} for a chunk that carries neither, which is the common case for provider
     * SDKs whose getters are nullable.
     */
    static <T> ChunkInspector<T> of(Function<T, String> textDelta, Function<T, TokenUsage> usageFrame) {
        Objects.requireNonNull(textDelta, "textDelta");
        Objects.requireNonNull(usageFrame, "usageFrame");
        return new ChunkInspector<>() {
            @Override
            public String textDelta(T chunk) {
                return textDelta.apply(chunk);
            }

            @Override
            public Optional<TokenUsage> usageFrame(T chunk) {
                return Optional.ofNullable(usageFrame.apply(chunk));
            }
        };
    }

    /**
     * An inspector for a provider that streams text and never sends a usage frame. Every stream
     * read through it reconciles to an estimate.
     */
    static <T> ChunkInspector<T> ofText(Function<T, String> textDelta) {
        return of(textDelta, chunk -> null);
    }
}
