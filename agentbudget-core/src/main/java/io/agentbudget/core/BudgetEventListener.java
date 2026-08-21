package io.agentbudget.core;

/**
 * Notified when a session's spend crosses its warning threshold or its limit. The extension point
 * that keeps telemetry integration possible without this library depending on any telemetry
 * stack: emit a metric, page someone, write a row — that is the caller's business, not ours.
 *
 * <p>Fired <strong>once per crossing</strong>, not on every call thereafter. A session that
 * crosses eighty percent notifies once and stays quiet until it crosses the limit too.
 *
 * <p>Called on the thread that made the crossing call, inside it. A listener that blocks holds up
 * that call, so keep it quick — hand off to an executor if the work is not trivial. A listener
 * that throws is logged and swallowed: a broken callback must never break the call in flight.
 */
@FunctionalInterface
public interface BudgetEventListener {

    void onBudgetEvent(BudgetEvent event);

    /** A listener that does nothing, the default when none is configured. */
    BudgetEventListener NONE = event -> { };
}
