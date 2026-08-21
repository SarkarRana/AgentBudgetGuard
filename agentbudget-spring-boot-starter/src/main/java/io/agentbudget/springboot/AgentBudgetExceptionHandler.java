package io.agentbudget.springboot;

import io.agentbudget.core.AgentBudgetException;
import io.agentbudget.core.BudgetExceededException;
import io.agentbudget.core.CallRateExceededException;
import io.agentbudget.core.ProjectedBudgetExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps this library's exceptions to HTTP responses, so a budget breach reaches an API caller as a
 * status code and a body rather than an unhandled 500.
 *
 * <p>Registered only for servlet web applications, and only when the application has not defined
 * its own handling for these types — see {@link AgentBudgetAutoConfiguration}. Spring's
 * {@code ExceptionHandlerExceptionResolver} picks the most specific method for a given thrown
 * type, so the three named handlers below take precedence over the {@link AgentBudgetException}
 * catch-all for the exceptions they name.
 *
 * <p><strong>The status choices.</strong> {@link BudgetExceededException} and
 * {@link ProjectedBudgetExceededException} are the same kind of refusal — spend exhausted, one
 * as fact and one as forecast — and both map to {@code 402 Payment Required}: the closest thing
 * HTTP has to "you are out of budget." {@link CallRateExceededException} is textbook rate
 * limiting and maps to {@code 429 Too Many Requests}. Everything else this library throws —
 * including {@code GuardFailureException} and {@code SessionIdUnresolvableException} — is an
 * internal or configuration failure an API caller cannot act on, and falls through to
 * {@code 500 Internal Server Error} rather than leaking accounting internals into a 4xx.
 */
@RestControllerAdvice
public class AgentBudgetExceptionHandler {

    @ExceptionHandler(BudgetExceededException.class)
    public ProblemDetail handleBudgetExceeded(BudgetExceededException e) {
        ProblemDetail body = problemDetail(HttpStatus.PAYMENT_REQUIRED, e);
        body.setProperty("sessionId", e.sessionId());
        body.setProperty("limit", e.limit().toString());
        body.setProperty("currentSpend", e.currentSpend().toString());
        return body;
    }

    @ExceptionHandler(ProjectedBudgetExceededException.class)
    public ProblemDetail handleProjectedBudgetExceeded(ProjectedBudgetExceededException e) {
        ProblemDetail body = problemDetail(HttpStatus.PAYMENT_REQUIRED, e);
        body.setProperty("sessionId", e.sessionId());
        body.setProperty("limit", e.limit().toString());
        body.setProperty("currentSpend", e.currentSpend().toString());
        body.setProperty("projectedCost", e.projectedCost().toString());
        return body;
    }

    @ExceptionHandler(CallRateExceededException.class)
    public ProblemDetail handleCallRateExceeded(CallRateExceededException e) {
        ProblemDetail body = problemDetail(HttpStatus.TOO_MANY_REQUESTS, e);
        body.setProperty("sessionId", e.sessionId());
        body.setProperty("observedCalls", e.observedCalls());
        body.setProperty("maxCalls", e.maxCalls());
        body.setProperty("window", e.window().toString());
        return body;
    }

    @ExceptionHandler(AgentBudgetException.class)
    public ProblemDetail handleOther(AgentBudgetException e) {
        return problemDetail(HttpStatus.INTERNAL_SERVER_ERROR, e);
    }

    private static ProblemDetail problemDetail(HttpStatus status, AgentBudgetException e) {
        return ProblemDetail.forStatusAndDetail(status, e.getMessage());
    }
}
