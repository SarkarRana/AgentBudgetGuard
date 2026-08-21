package io.agentbudget.demo;

import io.agentbudget.core.BudgetGuard;
import io.agentbudget.core.SpendSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code POST /api/chat} drives the budgeted call; {@code GET /api/spend/{sessionId}} is a
 * plain read against the guard bean, letting you watch a session's spend climb request by
 * request until {@code BudgetExceededException} surfaces as an HTTP 402 -- see the demo
 * README for the exact sequence.
 */
@RestController
public class ChatController {

    private final ChatService chatService;
    private final BudgetGuard budgetGuard;

    public ChatController(ChatService chatService, BudgetGuard budgetGuard) {
        this.chatService = chatService;
        this.budgetGuard = budgetGuard;
    }

    @PostMapping("/api/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String reply = chatService.chat(request.sessionId(), request.prompt());
        return new ChatResponse(request.sessionId(), reply);
    }

    @GetMapping("/api/spend/{sessionId}")
    public SpendView spend(@PathVariable("sessionId") String sessionId) {
        SpendSnapshot snapshot = budgetGuard.snapshot(sessionId);
        Map<String, String> perModel = new LinkedHashMap<>();
        snapshot.perModel().forEach((model, cost) -> perModel.put(model, cost.toString()));
        return new SpendView(snapshot.sessionId(), snapshot.total().toString(),
                snapshot.limit().toString(), snapshot.remaining().toString(), perModel);
    }

    public record ChatRequest(String sessionId, String prompt) {
    }

    public record ChatResponse(String sessionId, String reply) {
    }

    /** {@link SpendSnapshot}, flattened to strings -- {@code Money} has no bean getters for
     * Jackson to find, so serialising the snapshot directly would produce an empty object. */
    public record SpendView(String sessionId, String total, String limit, String remaining,
                            Map<String, String> perModel) {
    }
}
