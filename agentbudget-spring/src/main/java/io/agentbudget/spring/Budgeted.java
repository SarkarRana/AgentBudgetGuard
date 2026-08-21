package io.agentbudget.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enforces a spend limit around a Spring bean method, so budget concerns stay out of the
 * business logic inside it.
 *
 * <pre>{@code
 * @Budgeted(limit = "$2.00", model = "gpt-4o")
 * public String summarise(@SessionId String userId, String document) {
 *     ...
 * }
 * }</pre>
 *
 * <p>The session's spend is checked <em>before</em> the method runs: an exhausted session throws
 * {@link io.agentbudget.core.BudgetExceededException} and the method body never executes.
 *
 * <p><strong>Reporting what was spent.</strong> The advice cannot see inside the method, so it
 * cannot know what the call consumed unless the method says so. Either report it with
 * {@link BudgetedUsage#report}, or return a {@link io.agentbudget.core.GuardedResult} and the
 * advice will unwrap it. A method that does neither is enforced but never charged.
 *
 * <p><strong>Placeholders.</strong> {@code limit}, {@code model}, and {@code session} all accept
 * a {@code ${property.name}} placeholder as well as a literal, resolved against the application's
 * environment the same way {@code @Value} is — so an operational limit can live in
 * {@code application.yml} and change per environment without a rebuild.
 *
 * <p><strong>Self-invocation does not work.</strong> This is Spring AOP: the advice lives on a
 * proxy, so it only applies to calls that arrive through that proxy. One method of a bean calling
 * another on {@code this} bypasses the proxy entirely and is not intercepted, however it is
 * annotated. Call it from another bean, or inject the bean into itself. See the module README.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface Budgeted {

    /**
     * The spend ceiling for the session, written the way a human writes it: {@code "$2.00"},
     * {@code "2.00 USD"}, {@code "€1.50"}. A malformed value fails when the method is first
     * intercepted, naming the offending text.
     *
     * <p>Leave unset to use the limit of the application's own {@code BudgetGuard} bean.
     */
    String limit() default "";

    /**
     * The model this method's calls are priced against, used when usage is reported without
     * naming one of its own.
     */
    String model() default "";

    /**
     * The session id to charge, when it is a fixed literal rather than something to read off the
     * invocation. Usually left unset in favour of a {@link SessionId} parameter.
     */
    String session() default "";

    /**
     * Which budget the session id is scoped to: {@code "session"} (the default — whatever the
     * resolved session id naturally means to your application, unchanged), {@code "request"} (a
     * fresh budget per HTTP request, requiring {@code agentbudget-spring-boot-starter} and an
     * active request), or any other literal, which is a <strong>named scope</strong> — a fixed
     * budget shared by every caller under that name, ignoring the resolved session id entirely.
     *
     * <p>Windowing is a property of the resolver chain: it decorates whichever
     * {@link SessionIdResolver} is configured, default or custom. A resolver bean an application
     * defines for itself is not decorated — see the starter's README.
     */
    String window() default "session";
}
