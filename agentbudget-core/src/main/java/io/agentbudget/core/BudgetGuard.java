package io.agentbudget.core;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The library's entry point. Wraps a call to any LLM client, prices what it consumed against a
 * {@link PricingCatalog}, and enforces a spend limit per session.
 *
 * <p>One guard instance is shared across sessions: every call to {@link #wrap} carries the
 * caller-supplied session id, and each session accumulates its own total independently, held
 * behind the configured {@link SessionStore}. The guard never invents scoping of its own — no
 * thread-locals, no implicit request context — the caller decides what a session is.
 *
 * <p>Two entry points, one budget: {@link #wrap} for a call that returns once, and
 * {@link #openStream} for one that arrives in pieces. Both charge the same session ledger, so a
 * mixed workload has a single running total.
 *
 * <p>Both entry points come in two forms: one that generates a {@link CallId} per attempt, and
 * one that takes the caller's own. Recording is idempotent per id, so instrumentation that fires
 * at-least-once cannot double-charge a session.
 *
 * <p>An optional warning threshold and {@link BudgetEventListener} give early notice: the
 * listener fires once as spend crosses eighty percent (or wherever the threshold sits) and once
 * as it crosses the limit, never repeatedly thereafter. The library logs through the JDK's
 * {@link System.Logger}, so it takes no telemetry dependency of its own.
 *
 * <p>If the guard's own accounting throws, {@link FailureMode} decides what happens: fail-open
 * by default, logging and letting the call through, because a budget library must never become
 * the outage. A deliberate refusal is not an accounting failure and always reaches the caller.
 *
 * <p>An optional {@link CallRateBreaker} guards the rate as well as the spend. It is configured
 * independently and checked first, so a runaway loop against a cheap model is stopped in seconds
 * rather than dollars, even on a session nowhere near its limit.
 */
public final class BudgetGuard {

    private final Money limit;
    private final ExceedPolicy policy;
    private final PricingCatalog pricingCatalog;
    private final SessionStore sessionStore;
    private final TokenEstimator tokenEstimator;
    private final int recordedCallHistory;
    private final CallRateBreaker callRateBreaker;
    private final Money warningThreshold;
    private final Preflight preflight;
    private final FailureMode failureMode;
    private final String fallbackModel;
    private final Money hardLimit;
    private final BudgetEventListener listener;
    private final ExceedPolicyEngine policyEngine;

    private BudgetGuard(Money limit, ExceedPolicy policy, PricingCatalog pricingCatalog, SessionStore sessionStore,
                        TokenEstimator tokenEstimator, int recordedCallHistory, CallRateBreaker callRateBreaker,
                        Money warningThreshold, BudgetEventListener listener, Preflight preflight,
                        FailureMode failureMode, String fallbackModel, Money hardLimit) {
        this.limit = limit;
        this.policy = policy;
        this.pricingCatalog = pricingCatalog;
        this.sessionStore = sessionStore;
        this.tokenEstimator = tokenEstimator;
        this.recordedCallHistory = recordedCallHistory;
        this.callRateBreaker = callRateBreaker;
        this.warningThreshold = warningThreshold;
        this.listener = listener;
        this.preflight = preflight;
        this.failureMode = failureMode;
        this.fallbackModel = fallbackModel;
        this.hardLimit = hardLimit;
        this.policyEngine = new ExceedPolicyEngine();
    }

    private static final Logger LOG = System.getLogger(BudgetGuard.class.getName());

    public static Builder builder() {
        return new Builder();
    }

    /**
     * A builder pre-loaded with this guard's settings, for deriving a variant that differs in one
     * respect — typically a different limit.
     *
     * <p>The derived guard shares this one's {@link SessionStore}, so both charge the same session
     * ledgers. That is what lets several limits apply to one session's running total: each guard
     * enforces its own ceiling against spend they all accumulate together.
     */
    public Builder toBuilder() {
        Builder builder = new Builder()
                .limit(limit)
                .onExceed(policy)
                .pricingCatalog(pricingCatalog)
                .sessionStore(sessionStore)
                .tokenEstimator(tokenEstimator)
                .recordedCallHistory(recordedCallHistory)
                .onFailure(failureMode)
                .onBudgetEvent(listener);
        if (callRateBreaker != null) {
            builder.callRateBreaker(callRateBreaker);
        }
        if (warningThreshold != null) {
            builder.warnAt(BudgetThreshold.ofAmount(warningThreshold));
        }
        if (preflight != null) {
            builder.preflight(preflight);
        }
        if (fallbackModel != null) {
            builder.fallbackModel(fallbackModel);
        }
        if (hardLimit != null) {
            builder.hardLimit(hardLimit);
        }
        return builder;
    }

    public Money limit() {
        return limit;
    }

    /**
     * The spend at which this guard's warning threshold sits, or {@code null} when none is
     * configured.
     */
    public Money warningThreshold() {
        return warningThreshold;
    }

    /**
     * The cheaper model this guard degrades to under {@link ExceedPolicy#SWITCH_MODEL}, or
     * {@code null} when no fallback is configured.
     */
    public String fallbackModel() {
        return fallbackModel;
    }

    /**
     * The ceiling at which even the fallback model stops, or {@code null} when no fallback is
     * configured.
     */
    public Money hardLimit() {
        return hardLimit;
    }

    public Money spend(String sessionId) {
        return ledgerFor(sessionId).total();
    }

    public Money remaining(String sessionId) {
        Money spent = spend(sessionId);
        Money left = limit.minus(spent);
        return left.isNegative() ? Money.zero(limit.currency()) : left;
    }

    /**
     * Runs {@code call} against {@code sessionId}'s budget under a freshly generated
     * {@link CallId}. Throws {@link BudgetExceededException} before invoking {@code call} if that
     * session has already reached its limit.
     *
     * <p>One generated id per invocation means one charge per attempt: a call your HTTP client
     * retries arrives here once per attempt and is charged for each attempt that actually
     * consumed tokens. Use {@link #wrap(String, String, CallId, GuardedCall)} when the same
     * attempt may be recorded more than once.
     */
    public <T> T wrap(String sessionId, String model, GuardedCall<T> call) {
        return wrap(sessionId, model, CallId.random(), call);
    }

    /**
     * Runs a call whose model this guard chooses. The model-aware entry point, and the only one
     * that can honour {@link ExceedPolicy#SWITCH_MODEL}: once the session crosses its limit the
     * guard hands the call its nominated fallback model instead of the requested one, and prices
     * what comes back at the fallback's rate.
     *
     * <p>Behaves exactly like {@link #wrap} under every other policy — the model handed in is
     * simply the one that was asked for.
     *
     * <pre>{@code
     * String answer = guard.call("sess-1", "gpt-4o", model -> client.chat(model, prompt));
     * }</pre>
     */
    public <T> T call(String sessionId, String model, ModelAwareCall<T> call) {
        return call(sessionId, model, CallId.random(), call);
    }

    /**
     * As above, recording what the call consumed under {@code callId}.
     */
    public <T> T call(String sessionId, String model, CallId callId, ModelAwareCall<T> call) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(call, "call");
        return dispatch(sessionId, model, callId, null, call, false);
    }

    /**
     * Runs {@code call} against {@code sessionId}'s budget, refusing it up front if pre-flight
     * estimation projects that {@code estimate} would breach the limit.
     *
     * <p>The projection is only consulted when estimation is enabled on the builder; with it off
     * this behaves exactly as the overload without an estimate.
     */
    public <T> T wrap(String sessionId, String model, CallEstimate estimate, GuardedCall<T> call) {
        return wrap(sessionId, model, CallId.random(), estimate, call);
    }

    /**
     * Runs {@code call} under {@code callId}, refusing it up front if pre-flight estimation
     * projects that {@code estimate} would breach the limit.
     */
    public <T> T wrap(String sessionId, String model, CallId callId, CallEstimate estimate, GuardedCall<T> call) {
        Objects.requireNonNull(estimate, "estimate");
        return wrap(sessionId, model, estimate, call, callId, true);
    }

    /**
     * Runs {@code call} against {@code sessionId}'s budget, recording what it consumed under
     * {@code callId}. Recording is idempotent: if this id has already been charged to the
     * session, the call still runs but its cost is not added a second time.
     *
     * <p>An attempt that consumed nothing — a connection failure that never reached the provider
     * — records nothing and leaves {@code callId} unclaimed, so the retry that follows it can
     * reuse the same id and still be charged. See {@link CallId} for what an id identifies.
     */
    public <T> T wrap(String sessionId, String model, CallId callId, GuardedCall<T> call) {
        return wrap(sessionId, model, null, call, callId, false);
    }

    private <T> T wrap(String sessionId, String model, CallEstimate estimate, GuardedCall<T> call,
                       CallId callId, boolean estimated) {
        Objects.requireNonNull(call, "call");
        // An opaque call cannot be re-pointed at another model. Making the call anyway would
        // send it to the requested model and price it at the fallback's rate, which is a silent
        // under-charge — so it refuses instead, naming the entry point that can do this.
        return dispatch(sessionId, model, callId, estimate, chosenModel -> {
            if (!chosenModel.equals(model)) {
                throw new IllegalStateException(
                        ("Session '%s' should switch from '%s' to the fallback model '%s', but wrap() takes an opaque "
                                + "call this guard cannot re-point. Use call(sessionId, model, m -> ...) instead, "
                                + "which is handed the model to use.")
                                .formatted(sessionId, model, chosenModel));
            }
            return call.call();
        }, estimated);
    }

    private <T> T dispatch(String sessionId, String model, CallId callId, CallEstimate estimate,
                           ModelAwareCall<T> call, boolean estimated) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(call, "call");

        UsageLedger ledger = accounting("resolve the session's ledger", () -> ledgerFor(sessionId));
        String effectiveModel = model;
        if (ledger != null) {
            String selected = accounting("check the budget", () -> admit(sessionId, model, ledger));
            if (selected != null) {
                effectiveModel = selected;
            }
            String projected = effectiveModel;
            if (estimated) {
                accounting("project the call's cost", () -> projectAndCheck(sessionId, projected, estimate, ledger));
            }
        }

        String chargedModel = effectiveModel;
        try {
            GuardedResult<T> result = call.callWith(chargedModel);
            recordSafely(ledger, callId, chargedModel, result.usage(), sessionId);
            return result.output();
        } catch (PartialUsageException e) {
            recordSafely(ledger, callId, chargedModel, e.usage(), sessionId);
            throw e;
        }
    }

    /**
     * Opens accounting for one streaming call against {@code sessionId}'s budget, and hands back
     * the handle the caller drives from inside their own consumption loop. The caller's stream is
     * never passed to this method, wrapped, or returned — they keep consuming it through their own
     * client's API, exactly as they did before.
     *
     * <p>The budget is checked here, before the caller has asked their client for anything, so an
     * over-budget session throws {@link BudgetExceededException} and the generation never starts.
     * Open the session before opening the stream, and the check is a genuine pre-flight one.
     *
     * <p>Under {@link ExceedPolicy#SWITCH_MODEL} the session may nominate a different model to
     * the one requested — open your stream against {@link StreamSession#model()} rather than the
     * model you asked for, or the guard will price a call it did not make.
     *
     * <p>The returned session must be closed — that is where usage is reconciled and charged.
     * Close it with try-with-resources so a loop that breaks early or throws is still charged for
     * what it consumed. See ADR 0001 for why interception takes this shape.
     */
    public <T> StreamSession<T> openStream(String sessionId, String model, ChunkInspector<T> inspector) {
        return openStream(sessionId, model, CallId.random(), inspector);
    }

    /**
     * Opens accounting for one streaming call, recording what it consumed under {@code callId}
     * when the session is closed. Same idempotency as
     * {@link #wrap(String, String, CallId, GuardedCall)}: a stream re-opened and re-consumed
     * under an id already charged to the session does not charge it twice.
     */
    public <T> StreamSession<T> openStream(String sessionId, String model, CallId callId,
                                           ChunkInspector<T> inspector) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(inspector, "inspector");

        UsageLedger ledger = accounting("resolve the session's ledger", () -> ledgerFor(sessionId));
        String effectiveModel = model;
        if (ledger != null) {
            String selected = accounting("check the budget", () -> admit(sessionId, model, ledger));
            if (selected != null) {
                effectiveModel = selected;
            }
        }

        String chargedModel = effectiveModel;
        return new StreamSession<>(chargedModel, inspector, new StreamingUsageAggregator(tokenEstimator),
                reconciled -> {
                    Money cost = recordSafely(ledger, callId, chargedModel, reconciled.usage(), sessionId);
                    return cost != null ? cost : Money.zero(limit.currency());
                });
    }

    /**
     * An immutable view of what {@code sessionId} has spent, broken down by model and by call.
     * Safe to hand to another thread or serialise onto an admin endpoint.
     */
    public SpendSnapshot snapshot(String sessionId) {
        return ledgerFor(sessionId).snapshot(sessionId, limit);
    }

    /**
     * Clears {@code sessionId}'s spend so a long-lived process can start a fresh budget for the
     * next unit of work under the same id. Other sessions are untouched, and a configured
     * {@link CallRateBreaker} forgets this session's call history too.
     */
    public void resetSession(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        ledgerFor(sessionId).reset();
        if (callRateBreaker != null) {
            callRateBreaker.reset(sessionId);
        }
    }

    /**
     * The breaker's view of {@code sessionId}, for a health endpoint.
     *
     * @throws IllegalStateException if no {@link CallRateBreaker} is configured
     */
    public BreakerStatus breakerStatus(String sessionId) {
        if (callRateBreaker == null) {
            throw new IllegalStateException("No call-rate breaker is configured on this guard");
        }
        return callRateBreaker.status(sessionId);
    }

    /**
     * Decides whether this session may dispatch a call at all, and counts it against the rate if
     * so. Rate first, then spend: a session already refused on budget grounds never reached the
     * provider, so counting it towards a runaway loop would be counting a call that never
     * happened.
     */
    /**
     * Decides whether this session may dispatch a call at all, which model it goes to, and counts
     * it against the rate.
     *
     * @return the model the call should actually use — the requested one, or the nominated
     *         fallback once a {@code SWITCH_MODEL} guard has crossed its limit
     */
    private String admit(String sessionId, String requestedModel, UsageLedger ledger) {
        Money currentSpend = ledger.total();
        PolicyDecision decision = policyEngine.evaluate(currentSpend, limit, hardLimit, policy);
        if (decision == PolicyDecision.STOP) {
            throw new BudgetExceededException(sessionId, limit, currentSpend);
        }
        if (decision == PolicyDecision.WARN) {
            LOG.log(Level.WARNING,
                    () -> "Session '%s' has spent %s against a limit of %s; allowing the call under the WARN policy"
                            .formatted(sessionId, currentSpend, limit));
        }
        if (callRateBreaker != null) {
            callRateBreaker.recordCall(sessionId);
        }
        if (decision != PolicyDecision.SWITCH || requestedModel.equals(fallbackModel)) {
            return requestedModel;
        }
        LOG.log(Level.INFO,
                () -> "Session '%s' has spent %s against a limit of %s; routing this call to the fallback model '%s'"
                        .formatted(sessionId, currentSpend, limit, fallbackModel));
        return fallbackModel;
    }

    /**
     * The cost this call is projected to incur, given the guard's pre-flight settings. Useful for
     * showing a user what a request will cost before they commit to it.
     *
     * @throws IllegalStateException if pre-flight estimation is not enabled on this guard
     */
    public Money projectedCost(String model, CallEstimate estimate) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(estimate, "estimate");
        if (preflight == null) {
            throw new IllegalStateException("Pre-flight estimation is not enabled on this guard");
        }
        return pricingCatalog.price(model, estimate.projectedUsage(preflight.expectedOutputTokens(), tokenEstimator));
    }

    /**
     * Refuses the call if what it is projected to cost would take the session past its limit.
     *
     * <p>A forecast, not a fact, so it throws {@link ProjectedBudgetExceededException} rather
     * than the exception that means the money is already gone.
     */
    private void projectAndCheck(String sessionId, String model, CallEstimate estimate, UsageLedger ledger) {
        if (preflight == null) {
            return;
        }
        TokenUsage projectedUsage = estimate.projectedUsage(preflight.expectedOutputTokens(), tokenEstimator);
        Money projectedCost = pricingCatalog.price(model, projectedUsage);
        Money currentSpend = ledger.total();
        if (currentSpend.plus(projectedCost).compareTo(limit) > 0) {
            throw new ProjectedBudgetExceededException(sessionId, limit, currentSpend, projectedCost, projectedUsage);
        }
    }

    /**
     * Charges what the call consumed, under the configured failure mode.
     *
     * <p>By the time this runs the call has already gone out and the money is already spent, so
     * "blocking the call" is no longer available. Under fail-closed it still throws, because a
     * caller who chose fail-closed would rather have an error than a silently unmetered call;
     * under fail-open it logs and hands the caller their result.
     */
    private Money recordSafely(UsageLedger ledger, CallId callId, String model, TokenUsage usage, String sessionId) {
        if (ledger == null) {
            return null;
        }
        return accounting("record what the call consumed",
                () -> recordUsage(ledger, callId, model, usage, sessionId));
    }

    /**
     * Runs one step of the guard's own accounting under the configured {@link FailureMode}.
     *
     * <p>A {@link BudgetDecisionException} always propagates: a refusal is the library working,
     * and swallowing it under fail-open would swallow the whole point of the library. Anything
     * else is an internal failure — fail-open logs it and returns {@code null} so the caller's
     * call proceeds unmetered, fail-closed wraps it in a {@link GuardFailureException}.
     */
    private <T> T accounting(String operation, Supplier<T> step) {
        try {
            return step.get();
        } catch (BudgetDecisionException e) {
            throw e;
        } catch (RuntimeException e) {
            if (failureMode == FailureMode.FAIL_CLOSED) {
                throw new GuardFailureException(operation, e);
            }
            LOG.log(Level.WARNING,
                    "Budget accounting failed while trying to " + operation
                            + "; letting the call through because this guard is fail-open", e);
            return null;
        }
    }

    private void accounting(String operation, Runnable step) {
        accounting(operation, () -> {
            step.run();
            return null;
        });
    }

    /**
     * Prices {@code usage}, charges it, and notifies on any line the charge crossed.
     */
    private Money recordUsage(UsageLedger ledger, CallId callId, String model, TokenUsage usage,
                              String sessionId) {
        Money cost = pricingCatalog.price(model, usage);
        RecordOutcome outcome = ledger.record(new CallRecord(callId, model, usage, cost));
        notifyCrossings(sessionId, ledger, outcome);
        return cost;
    }

    /**
     * Fires the listener for each line this record took the session across.
     *
     * <p>Crossings are read off the totals either side of the record rather than from the total
     * alone, which is what makes "once per crossing" true: a session already over its threshold
     * did not cross it again, so it stays quiet.
     */
    private void notifyCrossings(String sessionId, UsageLedger ledger, RecordOutcome outcome) {
        if (!outcome.recorded() || listener == BudgetEventListener.NONE) {
            return;
        }
        boolean crossedThreshold = warningThreshold != null && outcome.crossed(warningThreshold);
        boolean crossedLimit = outcome.crossed(limit);
        if (!crossedThreshold && !crossedLimit) {
            return;
        }

        SpendSnapshot snapshot = ledger.snapshot(sessionId, limit);
        if (crossedThreshold) {
            notify(new BudgetEvent(sessionId, BudgetBoundary.WARNING_THRESHOLD, warningThreshold, snapshot));
        }
        if (crossedLimit) {
            notify(new BudgetEvent(sessionId, BudgetBoundary.LIMIT, limit, snapshot));
        }
    }

    /**
     * A listener that throws is logged and swallowed. The caller's call is already in flight and
     * their money is already spent; failing it because a metrics hook blew up would turn an
     * observability problem into an outage.
     */
    private void notify(BudgetEvent event) {
        try {
            listener.onBudgetEvent(event);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING,
                    "Budget event listener threw for session '%s' crossing %s; the call is unaffected"
                            .formatted(event.sessionId(), event.boundary()), e);
        }
    }

    private UsageLedger ledgerFor(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return sessionStore.ledgerFor(sessionId, () -> new UsageLedger(limit.currency(), recordedCallHistory));
    }

    public static final class Builder {
        private Money limit;
        private ExceedPolicy policy = ExceedPolicy.STOP;
        private PricingCatalog pricingCatalog;
        private SessionStore sessionStore;
        private TokenEstimator tokenEstimator = TokenEstimator.CHARACTERS_PER_TOKEN_HEURISTIC;
        private int recordedCallHistory = UsageLedger.DEFAULT_RECORDED_CALL_HISTORY;
        private CallRateBreaker callRateBreaker;
        private BudgetThreshold warnAt;
        private BudgetEventListener listener = BudgetEventListener.NONE;
        private Preflight preflight;
        private FailureMode failureMode = FailureMode.FAIL_OPEN;
        private String fallbackModel;
        private Money hardLimit;

        public Builder limit(Money limit) {
            this.limit = limit;
            return this;
        }

        public Builder onExceed(ExceedPolicy policy) {
            this.policy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        public Builder pricingCatalog(PricingCatalog pricingCatalog) {
            this.pricingCatalog = pricingCatalog;
            return this;
        }

        /**
         * Overrides where session ledgers live. Defaults to an {@link InMemorySessionStore} with
         * no eviction configured; pass a configured one to enable idle-session eviction, or a
         * custom implementation entirely (e.g. a future persistent store).
         */
        public Builder sessionStore(SessionStore sessionStore) {
            this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
            return this;
        }

        /**
         * Overrides how output tokens are estimated for a stream whose provider sent no usage
         * frame. Defaults to the four-characters-per-token heuristic; only the fallback path uses
         * it, since an authoritative frame always wins.
         */
        public Builder tokenEstimator(TokenEstimator tokenEstimator) {
            this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
            return this;
        }

        /**
         * Overrides how many recorded {@link CallId}s a session remembers for idempotency.
         * Defaults to {@link UsageLedger#DEFAULT_RECORDED_CALL_HISTORY}; raise it only if your
         * retries or duplicate instrumentation can arrive more than that many calls after the
         * original, since the window is what keeps a long-lived session's ledger bounded.
         */
        public Builder recordedCallHistory(int recordedCallHistory) {
            if (recordedCallHistory <= 0) {
                throw new IllegalArgumentException(
                        "recordedCallHistory must be positive, got " + recordedCallHistory);
            }
            this.recordedCallHistory = recordedCallHistory;
            return this;
        }

        /**
         * Adds runaway-loop protection, configured independently of the budget. Unset by default:
         * a guard without one enforces spend alone.
         */
        public Builder callRateBreaker(CallRateBreaker callRateBreaker) {
            this.callRateBreaker = Objects.requireNonNull(callRateBreaker, "callRateBreaker");
            return this;
        }

        /**
         * Sets an early-warning line below the limit — {@code BudgetThreshold.ofFraction(0.8)} for
         * eighty percent, or {@code ofAmount(...)} for a fixed figure. Unset by default, in which
         * case only the limit itself is notified.
         */
        public Builder warnAt(BudgetThreshold warnAt) {
            this.warnAt = Objects.requireNonNull(warnAt, "warnAt");
            return this;
        }

        /**
         * Notified once as a session crosses the warning threshold and once as it crosses the
         * limit. Unset by default.
         */
        public Builder onBudgetEvent(BudgetEventListener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        /**
         * Turns on pre-flight estimation, so a call whose projected cost would breach the limit is
         * refused before dispatch. Off by default — with it off, a call is only ever judged on
         * what it actually spent.
         */
        public Builder preflight(Preflight preflight) {
            this.preflight = Objects.requireNonNull(preflight, "preflight");
            return this;
        }

        /**
         * What happens when the guard's own accounting fails. Defaults to
         * {@link FailureMode#FAIL_OPEN}: log it and let the call through, so a bug in here never
         * becomes an outage out there.
         */
        public Builder onFailure(FailureMode failureMode) {
            this.failureMode = Objects.requireNonNull(failureMode, "failureMode");
            return this;
        }

        /**
         * The cheaper model {@link ExceedPolicy#SWITCH_MODEL} degrades to once the limit is
         * crossed. Must be priceable by the configured catalog — that is checked here, at build
         * time, rather than discovered at the breach.
         */
        public Builder fallbackModel(String fallbackModel) {
            this.fallbackModel = Objects.requireNonNull(fallbackModel, "fallbackModel");
            return this;
        }

        /**
         * The ceiling at which even the fallback model stops. Required with
         * {@link ExceedPolicy#SWITCH_MODEL}, because a cheaper model is not a free one: without a
         * ceiling, degrading would be an unbounded licence to keep spending.
         */
        public Builder hardLimit(Money hardLimit) {
            this.hardLimit = Objects.requireNonNull(hardLimit, "hardLimit");
            return this;
        }

        /**
         * Fails now, rather than at the breach, if this guard is set to degrade but could not
         * actually do so.
         */
        private void validateFallback() {
            if (policy != ExceedPolicy.SWITCH_MODEL) {
                return;
            }
            if (fallbackModel == null) {
                throw new IllegalArgumentException(
                        "SWITCH_MODEL requires a fallbackModel; there is nothing to degrade to without one");
            }
            if (hardLimit == null) {
                throw new IllegalArgumentException(
                        "SWITCH_MODEL requires a hardLimit; without a ceiling the fallback model would never stop");
            }
            if (hardLimit.compareTo(limit) <= 0) {
                throw new IllegalArgumentException(
                        "hardLimit %s must be above the limit %s, or the fallback would never be reached"
                                .formatted(hardLimit, limit));
            }
            try {
                pricingCatalog.price(fallbackModel, TokenUsage.ZERO);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "Fallback model '%s' cannot be priced by the configured catalog, so it is not a usable fallback"
                                .formatted(fallbackModel), e);
            }
        }

        public BudgetGuard build() {
            if (limit == null) {
                throw new IllegalArgumentException("limit must be set");
            }
            if (limit.amount().signum() <= 0) {
                throw new IllegalArgumentException("limit must be a positive amount, got " + limit);
            }
            if (pricingCatalog == null) {
                throw new IllegalArgumentException("pricingCatalog must be set");
            }
            SessionStore resolvedStore = sessionStore != null ? sessionStore : InMemorySessionStore.withDefaults();
            Money resolvedThreshold = warnAt != null ? warnAt.resolveAgainst(limit) : null;
            if (resolvedThreshold != null && resolvedThreshold.compareTo(limit) > 0) {
                throw new IllegalArgumentException(
                        "Warning threshold %s is above the limit %s, so it could never warn early"
                                .formatted(resolvedThreshold, limit));
            }
            validateFallback();
            return new BudgetGuard(limit, policy, pricingCatalog, resolvedStore, tokenEstimator, recordedCallHistory,
                    callRateBreaker, resolvedThreshold, listener, preflight, failureMode, fallbackModel, hardLimit);
        }
    }
}
