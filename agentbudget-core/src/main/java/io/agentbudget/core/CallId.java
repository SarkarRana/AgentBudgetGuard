package io.agentbudget.core;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies <em>one provider attempt</em> for the purpose of recording its cost. Recording the
 * same id twice charges the session once, so instrumentation that fires at-least-once is safe.
 *
 * <p>The unit is an attempt, not a logical call, and that distinction is the whole point. A call
 * that an HTTP client retries three times is three attempts: if each one reached the provider and
 * generated tokens, each is a real charge, and giving each its own id is what records all three.
 * Reusing one id across the retry sequence deliberately collapses it to a single charge — correct
 * when only one attempt ever consumed anything, and an under-count when more than one did.
 *
 * <p>{@link BudgetGuard} generates a fresh id per attempt when the caller does not supply one, so
 * the default behaviour is one charge per attempt that consumed tokens. Supply an id explicitly
 * when the same attempt may be recorded more than once — a retried instrumentation hook, an
 * at-least-once event pipeline, a replayed webhook. See ADR 0003.
 */
public record CallId(String value) {

    public CallId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("call id must not be blank");
        }
    }

    public static CallId of(String value) {
        return new CallId(value);
    }

    /**
     * A fresh identifier for an attempt the caller has not named itself.
     */
    public static CallId random() {
        return new CallId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
