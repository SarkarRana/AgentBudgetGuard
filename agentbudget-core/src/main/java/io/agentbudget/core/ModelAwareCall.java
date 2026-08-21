package io.agentbudget.core;

/**
 * A call whose model is a parameter rather than something the caller has already baked in. This
 * is what makes {@link ExceedPolicy#SWITCH_MODEL} possible: the guard decides which model the
 * call goes to and hands it in, instead of trying to re-issue an opaque supplier it cannot see
 * inside.
 *
 * <pre>{@code
 * String answer = guard.call("sess-1", "gpt-4o", model -> client.chat(model, prompt));
 * }</pre>
 *
 * <p>Honour the model you are given. Ignoring it and calling your original model anyway is the
 * one way to make the accounting lie: the guard prices what it asked for, so a call that quietly
 * went somewhere else is charged at the wrong rate. See ADR 0004.
 */
@FunctionalInterface
public interface ModelAwareCall<T> {

    /**
     * Makes the call against {@code model} — the model the guard selected, which is the nominated
     * fallback rather than the requested model once the session has crossed its limit.
     */
    GuardedResult<T> callWith(String model);
}
