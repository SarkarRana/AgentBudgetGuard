package io.agentbudget.springboot;

import io.agentbudget.core.BudgetExceededException;
import io.agentbudget.core.CallRateExceededException;
import io.agentbudget.core.GuardFailureException;
import io.agentbudget.core.Money;
import io.agentbudget.core.ProjectedBudgetExceededException;
import io.agentbudget.core.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A budget breach reaching an API caller as an HTTP status and a body, driven through a real
 * MockMvc request rather than calling the handler methods directly — this is the acceptance bar
 * the issue names: "a test asserting a chosen HTTP status."
 */
@SpringBootTest(classes = {AgentBudgetExceptionHandlerTest.TestApp.class, AgentBudgetExceptionHandlerTest.TestController.class})
@AutoConfigureMockMvc
class AgentBudgetExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * A real {@code @SpringBootApplication} rather than a bare list of {@code @SpringBootTest}
     * classes, so {@code AgentBudgetAutoConfiguration} actually runs — the point of this test is
     * that the exception handler comes from auto-configuration, not from a hand-wired bean.
     */
    @SpringBootApplication
    static class TestApp {
    }

    @RestController
    static class TestController {

        @GetMapping("/budget-exceeded")
        String budgetExceeded() {
            throw new BudgetExceededException("session-1", Money.of("2.00", "USD"), Money.of("2.00", "USD"));
        }

        @GetMapping("/projected-budget-exceeded")
        String projectedBudgetExceeded() {
            throw new ProjectedBudgetExceededException("session-1", Money.of("2.00", "USD"), Money.of("1.00", "USD"),
                    Money.of("5.00", "USD"), TokenUsage.of(5, 0));
        }

        @GetMapping("/call-rate-exceeded")
        String callRateExceeded() {
            throw new CallRateExceededException("session-1", 6, 5, Duration.ofSeconds(10));
        }

        @GetMapping("/guard-failure")
        String guardFailure() {
            throw new GuardFailureException("price the call", new IllegalStateException("pricing is down"));
        }
    }

    @Test
    void aBudgetExceededExceptionSurfacesAs402() throws Exception {
        mockMvc.perform(get("/budget-exceeded"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.sessionId").value("session-1"))
                .andExpect(jsonPath("$.limit").value("2.00 USD"))
                .andExpect(jsonPath("$.currentSpend").value("2.00 USD"));
    }

    @Test
    void aProjectedBudgetExceededExceptionAlsoSurfacesAs402() throws Exception {
        mockMvc.perform(get("/projected-budget-exceeded"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.projectedCost").value("5.00 USD"));
    }

    @Test
    void aCallRateExceededExceptionSurfacesAs429() throws Exception {
        mockMvc.perform(get("/call-rate-exceeded"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.observedCalls").value(6))
                .andExpect(jsonPath("$.maxCalls").value(5));
    }

    @Test
    void anInternalGuardFailureSurfacesAs500RatherThanLeakingAccountingDetail() throws Exception {
        mockMvc.perform(get("/guard-failure"))
                .andExpect(status().isInternalServerError());
    }
}
