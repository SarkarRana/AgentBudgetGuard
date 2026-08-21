package io.agentbudget.springboot;

import io.agentbudget.core.PricingCatalog;
import io.agentbudget.core.StaticPricingCatalog;
import io.agentbudget.core.TokenUsage;
import io.agentbudget.spring.Budgeted;
import io.agentbudget.spring.BudgetedUsage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @Budgeted(window = "request")} driven through a real HTTP request dispatch, proving what
 * the unit-level {@link WindowAwareSessionIdResolverTest} only asserts against a bare invocation:
 * two calls inside one HTTP request share a budget, and the next request starts fresh.
 *
 * <p>$1/token pricing, a $2.00 limit and a $2.00-per-call cost, chosen so the limit is reached
 * exactly after one call: the STOP policy refuses once spend is <em>at or over</em> the limit, so
 * the second call in one request trips immediately rather than being allowed through and merely
 * overshooting.
 */
@SpringBootTest(classes = {WindowEndToEndTest.TestApp.class, WindowEndToEndTest.DefaultSpendingService.class,
        WindowEndToEndTest.TestController.class},
        // a generous app-wide default so a base BudgetGuard bean exists at all; the method's own
        // $2.00 limit (derived from it, sharing its session store) is what actually enforces here
        properties = "agentbudget.limit=$1000.00")
@AutoConfigureMockMvc
class WindowEndToEndTest {

    private static final String MODEL = "fake-model";

    @Autowired
    private MockMvc mockMvc;

    @SpringBootApplication
    static class TestApp {
        @Bean
        PricingCatalog pricingCatalog() {
            return StaticPricingCatalog.withSingleModel(MODEL,
                    io.agentbudget.core.ModelPricing.perMillionTokens("USD", 1_000_000, 1_000_000));
        }
    }

    // an interface, per this module's own documented contract: proxying is JDK dynamic proxies
    // only, and a bean with no interface cannot be advised that way.
    interface SpendingService {
        void spend();
    }

    @Service
    static class DefaultSpendingService implements SpendingService {
        @Budgeted(limit = "$2.00", model = MODEL, session = "fixed", window = "request")
        @Override
        public void spend() {
            BudgetedUsage.report(TokenUsage.of(2, 0));
        }
    }

    @RestController
    static class TestController {

        private final SpendingService service;

        TestController(SpendingService service) {
            this.service = service;
        }

        @GetMapping("/spend-once")
        String spendOnce() {
            service.spend();
            return "ok";
        }

        @GetMapping("/spend-twice")
        String spendTwice() {
            service.spend();
            service.spend(); // same HTTP request: shares the first call's window
            return "ok";
        }
    }

    @Test
    void twoCallsInOneRequestShareABudgetAndTheSecondTripsIt() throws Exception {
        mockMvc.perform(get("/spend-twice")).andExpect(status().isPaymentRequired());
    }

    @Test
    void aFreshRequestStartsAFreshBudgetRatherThanInheritingTheLastOnesSpend() throws Exception {
        // this would fail if "request" windowing leaked into a process-wide session, since the
        // previous test (or a previous call in this one) would have already spent against it
        mockMvc.perform(get("/spend-once")).andExpect(status().isOk());
        mockMvc.perform(get("/spend-once")).andExpect(status().isOk());
    }
}
