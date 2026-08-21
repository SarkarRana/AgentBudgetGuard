package io.agentbudget.core;

import java.util.Objects;
import java.util.function.Function;

/**
 * The accounting handle for one streaming call. Obtained from {@link BudgetGuard#openStream},
 * driven from inside the caller's own consumption loop, and closed when the stream is done with.
 *
 * <p>The caller consumes their stream through their own client's API, unchanged; this handle only
 * watches what goes past:
 *
 * <pre>{@code
 * try (StreamSession<Chunk> session = guard.openStream("sess-1", "gpt-4o", inspector)) {
 *     for (Chunk chunk : client.stream(session.model(), prompt)) {
 *         session.observe(chunk);
 *         render(chunk);
 *     }
 * }
 * }</pre>
 *
 * <p>{@link #close()} is the single point at which usage is reconciled and charged to the session,
 * and it runs whether the loop finished, broke early, or threw — so an abandoned generation is
 * charged for what it consumed. Closing twice records once.
 *
 * <p>Chunks are never retained: only a running character count and the latest usage frame are held,
 * so a long stream costs the same fixed memory as a short one.
 *
 * <p>Reactive callers observe on the emitting thread and close on a terminal-signal thread, which
 * need not be the same thread, so every method here is synchronized.
 */
public final class StreamSession<T> implements AutoCloseable {

    private final String model;
    private final ChunkInspector<T> inspector;
    private final StreamingUsageAggregator aggregator;
    private final Function<ReconciledUsage, Money> recorder;

    private boolean closed;
    private ReconciledUsage reconciledUsage;
    private Money recordedCost;

    StreamSession(String model,
                  ChunkInspector<T> inspector,
                  StreamingUsageAggregator aggregator,
                  Function<ReconciledUsage, Money> recorder) {
        this.model = model;
        this.inspector = inspector;
        this.aggregator = aggregator;
        this.recorder = recorder;
    }

    /**
     * The model this stream will be charged against — the one requested, or the nominated
     * fallback when a {@link ExceedPolicy#SWITCH_MODEL} guard has crossed its limit.
     *
     * <p>Open your stream against this, not against the model you asked for. The guard prices
     * what it selected, so a stream opened against a different model is priced at the wrong rate.
     */
    public String model() {
        return model;
    }

    /**
     * Accounts for one chunk on its way past. Chunks observed after {@link #close()} are ignored
     * rather than rejected: a late emission from a cancelled reactive stream is a race the caller
     * did not write and should not have to defend against.
     */
    public synchronized void observe(T chunk) {
        if (closed) {
            return;
        }
        aggregator.observeText(inspector.textDelta(chunk));
        inspector.usageFrame(chunk).ifPresent(aggregator::observeUsageFrame);
    }

    /**
     * The usage accounted for so far, without closing. Useful for progress reporting mid-stream;
     * it is not what gets charged — {@link #close()} decides that.
     */
    public synchronized ReconciledUsage observedUsage() {
        return closed ? reconciledUsage : aggregator.reconcile();
    }

    /**
     * Reconciles what the stream produced and charges it to the session. Idempotent: the second
     * and later calls are no-ops, so try-with-resources around an explicit close is harmless.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        reconciledUsage = aggregator.reconcile();
        recordedCost = recorder.apply(reconciledUsage);
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    /**
     * What this stream was reconciled to and charged for.
     *
     * @throws IllegalStateException if the session is still open — there is no final answer until
     *                               the stream is done, and returning the interim one as if it were
     *                               final is how under-reporting starts
     */
    public synchronized ReconciledUsage reconciledUsage() {
        return requireClosed(reconciledUsage);
    }

    /**
     * The money charged to the session for this stream.
     *
     * @throws IllegalStateException if the session is still open
     */
    public synchronized Money recordedCost() {
        return requireClosed(recordedCost);
    }

    private <V> V requireClosed(V value) {
        if (!closed) {
            throw new IllegalStateException("Stream session is still open; close it before reading its recorded cost");
        }
        return Objects.requireNonNull(value);
    }
}
