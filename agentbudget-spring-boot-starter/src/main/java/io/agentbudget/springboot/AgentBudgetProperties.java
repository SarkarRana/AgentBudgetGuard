package io.agentbudget.springboot;

import io.agentbudget.core.ExceedPolicy;
import io.agentbudget.core.FailureMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code agentbudget.*} in {@code application.yml} — the settings a developer wants to change
 * without a rebuild.
 *
 * <pre>{@code
 * agentbudget:
 *   limit: "$5.00"
 *   on-exceed: WARN
 *   warn-at: "80%"
 *   on-failure: FAIL_OPEN
 *   breaker:
 *     enabled: true
 *     max-calls: 20
 *     window: 60s
 *     cool-off: 5m
 * }</pre>
 *
 * <p>Deliberately not every {@code BudgetGuard.Builder} setting — a {@code PricingCatalog} or a
 * custom {@code TokenEstimator} is a bean, not a string, and stays code-only. What is here is
 * exactly what the issue asked to be operator-tunable: the limit, the exceed policy, the warning
 * threshold, and the breaker.
 *
 * <p>Every field is documented on the field itself, which is what the
 * {@code spring-boot-configuration-processor} annotation processor reads to generate this
 * module's {@code META-INF/spring-configuration-metadata.json} — the IDE completion and hover
 * text an application author sees while typing {@code application.yml}.
 */
@ConfigurationProperties(prefix = "agentbudget")
public class AgentBudgetProperties {

    /**
     * The spend ceiling, written the way a human writes it: {@code "$5.00"}, {@code "5.00 USD"}.
     * Required for the auto-configured {@code BudgetGuard} bean; leave the whole
     * {@code agentbudget} prefix unset if you are supplying your own guard bean instead.
     */
    private String limit;

    /** What happens once a session reaches its limit. Defaults to {@link ExceedPolicy#STOP}. */
    private ExceedPolicy onExceed = ExceedPolicy.STOP;

    /**
     * An early-warning line below the limit — {@code "80%"} for a fraction of the limit, or an
     * amount such as {@code "$4.00"}. Unset by default: no warning threshold.
     */
    private String warnAt;

    /**
     * What happens when the guard's own accounting fails. Defaults to
     * {@link FailureMode#FAIL_OPEN}: log it and let the call through.
     */
    private FailureMode onFailure = FailureMode.FAIL_OPEN;

    private final Breaker breaker = new Breaker();
    private final Fallback fallback = new Fallback();

    public String getLimit() {
        return limit;
    }

    public void setLimit(String limit) {
        this.limit = limit;
    }

    public ExceedPolicy getOnExceed() {
        return onExceed;
    }

    public void setOnExceed(ExceedPolicy onExceed) {
        this.onExceed = onExceed;
    }

    public String getWarnAt() {
        return warnAt;
    }

    public void setWarnAt(String warnAt) {
        this.warnAt = warnAt;
    }

    public FailureMode getOnFailure() {
        return onFailure;
    }

    public void setOnFailure(FailureMode onFailure) {
        this.onFailure = onFailure;
    }

    public Breaker getBreaker() {
        return breaker;
    }

    public Fallback getFallback() {
        return fallback;
    }

    /**
     * {@code agentbudget.breaker.*} — runaway-loop protection, independent of spend. Off by
     * default: a call-rate ceiling is a decision an application should opt into, not one this
     * library should make for it.
     */
    public static class Breaker {

        /** Turns the breaker on. Off by default. */
        private boolean enabled = false;

        /** The most calls a session may make within {@link #window}. */
        private int maxCalls = 20;

        /** The trailing window {@link #maxCalls} is counted over. */
        private Duration window = Duration.ofMinutes(1);

        /**
         * How long a tripped session stays rejected before its breaker closes again. Defaults to
         * {@link #window} when unset.
         */
        private Duration coolOff;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxCalls() {
            return maxCalls;
        }

        public void setMaxCalls(int maxCalls) {
            this.maxCalls = maxCalls;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public Duration getCoolOff() {
            return coolOff;
        }

        public void setCoolOff(Duration coolOff) {
            this.coolOff = coolOff;
        }
    }

    /**
     * {@code agentbudget.fallback.*} — the cheaper model and ceiling
     * {@link ExceedPolicy#SWITCH_MODEL} needs. Consulted only when {@link #onExceed} is set to
     * {@code SWITCH_MODEL}; see ADR 0004 for why a fallback needs a hard limit at all.
     */
    public static class Fallback {

        /** The model to degrade to once the limit is crossed. */
        private String model;

        /** The ceiling at which even the fallback model stops. */
        private String hardLimit;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getHardLimit() {
            return hardLimit;
        }

        public void setHardLimit(String hardLimit) {
            this.hardLimit = hardLimit;
        }
    }
}
