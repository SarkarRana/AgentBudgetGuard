package io.agentbudget.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the exact sequence the demo's README walks a human through: two calls succeed,
 * a third is refused with a 402 naming the session's spend and limit. If this ever stops
 * matching the numbers in the README, one of the two is wrong.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void twoCallsSucceedAndTheThirdIsRefusedWith402() throws Exception {
        String sessionId = "demo-user-" + System.nanoTime();

        chat(sessionId, "First question").andExpect(status().isOk());
        chat(sessionId, "Second question").andExpect(status().isOk());
        chat(sessionId, "Third question")
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.limit").value("3.00 USD"))
                .andExpect(jsonPath("$.currentSpend").value("4.00 USD"));

        mockMvc.perform(get("/api/spend/{sessionId}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("4.00 USD"))
                .andExpect(jsonPath("$.remaining").value("0 USD"));
    }

    private org.springframework.test.web.servlet.ResultActions chat(String sessionId, String prompt)
            throws Exception {
        return mockMvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"%s\",\"prompt\":\"%s\"}".formatted(sessionId, prompt)));
    }
}
