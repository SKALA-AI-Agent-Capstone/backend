package org.skala.capstone;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EnvironmentController.class)
@TestPropertySource(properties = {
    "capstone.internal-token=unit-test-only-token-not-for-production-12345",
    "capstone.agent-base-url=http://127.0.0.1:1"
})
class BootstrapBoundaryTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JdbcTemplate jdbc;

    @Test
    void internalApiRejectsMissingToken() throws Exception {
        mvc.perform(get("/internal/bootstrap")).andExpect(status().isUnauthorized());
    }

    @Test
    void reviewSubmissionDoesNotPretendToAcceptWork() throws Exception {
        mvc.perform(post("/api/reviews"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"));
    }
}
