package io.agentbudget.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the parameter that identifies the session to charge.
 *
 * <pre>{@code
 * @Budgeted(limit = "$2.00", model = "gpt-4o")
 * public String summarise(@SessionId String tenantId, String document) { ... }
 * }</pre>
 *
 * <p>This is how {@link DefaultSessionIdResolver} finds a session id, and it is explicit on
 * purpose: the library does not invent scoping, so nothing is read from a thread-local, a
 * security context, or a request attribute unless you write a {@link SessionIdResolver} that
 * does. See ADR 0005.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SessionId {
}
