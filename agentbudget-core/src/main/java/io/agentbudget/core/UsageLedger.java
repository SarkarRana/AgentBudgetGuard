package io.agentbudget.core;

import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Accumulates priced usage into a running session total, and holds enough detail to explain it.
 * Every record is keyed by a {@link CallId} identifying the provider attempt that produced it,
 * and recording is idempotent for repeats of an id: the second and later records of the same
 * attempt do not move the total.
 *
 * <p>Two rules give retry-safety its shape:
 *
 * <ul>
 *   <li><strong>An attempt that cost nothing claims nothing.</strong> A zero cost is not
 *       recorded at all and does not consume its id, so a connection-level failure that reached
 *       no provider leaves the id free for the attempt that follows it.</li>
 *   <li><strong>A recorded call is remembered for a bounded window.</strong> Retries and
 *       duplicate instrumentation arrive within seconds of the original, so only the most recent
 *       {@code recordedCallHistory} attempts are retained. Beyond that window an id is forgotten
 *       and a replay of it would charge again — which is the right trade against a session ledger
 *       that grows without limit.</li>
 * </ul>
 *
 * <p>The running total and the per-model breakdown cover the session's whole history; only the
 * per-call list is bounded by that window. Nothing here is ever rounded — totals accumulate at
 * full {@link java.math.BigDecimal} precision, and rounding is a presentation concern.
 *
 * <p>Recording checks and updates several pieces of state as one step, so this class is guarded
 * by its own monitor. The critical section is a map lookup and a couple of adds, sitting next to
 * an LLM round-trip measured in seconds; concurrent recording against one session reaches the
 * exact expected total.
 */
public final class UsageLedger {

    /**
     * How many recorded calls one session remembers by default. Generous next to any real retry
     * window, and bounded so a long-lived session cannot grow a ledger without limit.
     */
    public static final int DEFAULT_RECORDED_CALL_HISTORY = 1024;

    private final Currency currency;
    private final int recordedCallHistory;
    private final LinkedHashMap<CallId, CallRecord> recordedCalls;
    private final Map<String, Money> perModel = new LinkedHashMap<>();
    private Money total;

    public UsageLedger(Currency currency) {
        this(currency, DEFAULT_RECORDED_CALL_HISTORY);
    }

    public UsageLedger(Currency currency, int recordedCallHistory) {
        if (recordedCallHistory <= 0) {
            throw new IllegalArgumentException(
                    "recordedCallHistory must be positive, got " + recordedCallHistory);
        }
        this.currency = Objects.requireNonNull(currency, "currency");
        this.total = Money.zero(currency);
        this.recordedCallHistory = recordedCallHistory;
        // insertion-order, so the per-call breakdown reads oldest first and the eldest entry
        // dropped on overflow is the oldest call.
        this.recordedCalls = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CallId, CallRecord> eldest) {
                return size() > UsageLedger.this.recordedCallHistory;
            }
        };
    }

    /**
     * Charges {@code call} to this session and reports the totals either side of the record.
     *
     * <p>A no-op — leaving every total untouched — when the call id has already been recorded, or
     * when the cost is zero. In the duplicate case the record passed in is ignored entirely: the
     * attempt stands at whatever it was first recorded as, so an id recorded twice with different
     * usage still counts once.
     */
    public synchronized RecordOutcome record(CallRecord call) {
        Objects.requireNonNull(call, "call");

        Money previousTotal = total;
        if (call.cost().isZero() || recordedCalls.containsKey(call.callId())) {
            return new RecordOutcome(false, previousTotal, previousTotal);
        }

        recordedCalls.put(call.callId(), call);
        perModel.merge(call.model(), call.cost(), Money::plus);
        total = total.plus(call.cost());
        return new RecordOutcome(true, previousTotal, total);
    }

    public synchronized Money total() {
        return total;
    }

    /**
     * Whether {@code callId} has been recorded and is still within the remembered window.
     */
    public synchronized boolean hasRecorded(CallId callId) {
        Objects.requireNonNull(callId, "callId");
        return recordedCalls.containsKey(callId);
    }

    /**
     * What {@code callId} was charged, or {@code null} if it was never recorded or has since
     * fallen out of the remembered window.
     */
    public synchronized CallRecord recordOf(CallId callId) {
        Objects.requireNonNull(callId, "callId");
        return recordedCalls.get(callId);
    }

    /**
     * An immutable view of this session's spend against {@code limit}, safe to hand to another
     * thread. Taken under the lock, so it is a coherent instant rather than a set of figures read
     * at slightly different times.
     */
    public synchronized SpendSnapshot snapshot(String sessionId, Money limit) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(limit, "limit");

        Money left = limit.minus(total);
        return new SpendSnapshot(sessionId,
                total,
                limit,
                left.isNegative() ? Money.zero(currency) : left,
                new LinkedHashMap<>(perModel),
                new ArrayList<>(recordedCalls.values()));
    }

    /**
     * Clears this session's spend, its per-model breakdown, and its call history, so a long-lived
     * process can start a fresh budget for the next unit of work under the same session id.
     *
     * <p>Recording idempotency resets with it: a call id charged before the reset can be charged
     * again after it, which is correct — it is a new budget.
     */
    public synchronized void reset() {
        total = Money.zero(currency);
        perModel.clear();
        recordedCalls.clear();
    }
}
