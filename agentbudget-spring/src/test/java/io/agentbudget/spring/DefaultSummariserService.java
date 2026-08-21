package io.agentbudget.spring;

import io.agentbudget.core.GuardedResult;
import io.agentbudget.core.TokenUsage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static io.agentbudget.spring.BudgetedTestSupport.MODEL;

/**
 * Stands in for a service that calls an LLM. It records what actually ran, so a test can prove
 * the method body was reached — or was not, when the budget refused it.
 */
@Service
class DefaultSummariserService implements SummariserService {

    private final List<String> executed = new ArrayList<>();

    @Budgeted(limit = "$4.00", model = MODEL)
    @Override
    public String summarise(@SessionId String sessionId, long tokens) {
        executed.add("summarise");
        BudgetedUsage.report(tokens, 0);
        return "summary of " + tokens;
    }

    @Budgeted(limit = "$4.00", model = MODEL)
    @Override
    public String summariseWithoutSession(long tokens) {
        executed.add("summariseWithoutSession");
        BudgetedUsage.report(tokens, 0);
        return "summary";
    }

    @Budgeted(limit = "$4.00", model = MODEL, session = "the-fixed-session")
    @Override
    public String summariseFixedSession(long tokens) {
        executed.add("summariseFixedSession");
        BudgetedUsage.report(tokens, 0);
        return "summary";
    }

    @Override
    public String unbudgeted(long tokens) {
        executed.add("unbudgeted");
        BudgetedUsage.report(tokens, 0);
        return "not budgeted";
    }

    @Budgeted(limit = "$4.00", model = MODEL)
    @Override
    public String throwsChecked(@SessionId String sessionId) throws Exception {
        executed.add("throwsChecked");
        throw new java.io.IOException("the provider is unreachable");
    }

    @Budgeted(limit = "$4.00", model = MODEL)
    @Override
    public GuardedResult<String> summariseReturningUsage(@SessionId String sessionId, TokenUsage usage) {
        executed.add("summariseReturningUsage");
        return GuardedResult.of("summary", usage);
    }

    @Budgeted(limit = "$10.00", model = MODEL)
    @Override
    public String summariseTwice(@SessionId String sessionId, long first, long second) {
        executed.add("summariseTwice");
        BudgetedUsage.report(first, 0);
        BudgetedUsage.report(second, 0);
        return "summary";
    }

    @Budgeted(limit = "$4.00", model = MODEL)
    @Override
    public String selfInvoking(@SessionId String sessionId, long tokens) {
        executed.add("selfInvoking");
        // deliberately bypasses the proxy — see the self-invocation test
        return summarise(sessionId, tokens);
    }

    @Override
    public List<String> executed() {
        return executed;
    }
}
