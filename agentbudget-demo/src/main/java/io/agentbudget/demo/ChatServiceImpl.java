package io.agentbudget.demo;

import io.agentbudget.spring.Budgeted;
import io.agentbudget.spring.BudgetedUsage;
import io.agentbudget.spring.SessionId;
import org.springframework.stereotype.Service;

/**
 * The annotation style, end to end: the budget check and the charge both happen around this
 * method without a line of budget-handling code inside it. See the root README's "Quickstart"
 * for the wrapping-style equivalent.
 */
@Service
public class ChatServiceImpl implements ChatService {

    private final FakeLlmClient llmClient;

    public ChatServiceImpl(FakeLlmClient llmClient) {
        this.llmClient = llmClient;
    }

    // No limit set on the annotation: it enforces against the application's own BudgetGuard
    // bean, whose limit comes from agentbudget.limit in application.yml.
    @Override
    @Budgeted(model = "demo-llm")
    public String chat(@SessionId String sessionId, String prompt) {
        FakeLlmClient.Response response = llmClient.chat(prompt);
        BudgetedUsage.report(response.usage());
        return response.text();
    }
}
