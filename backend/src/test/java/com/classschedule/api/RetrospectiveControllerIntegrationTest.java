package com.classschedule.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(username = "retro-planner", roles = "PLANNER")
class RetrospectiveControllerIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("class_schedule_retrospectives")
                    .withUsername("class_schedule")
                    .withPassword("class_schedule");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM term_retrospective WHERE term_id=(SELECT id FROM academic_term WHERE code='2026-FALL')");
    }

    @Test
    void readsMetricsAndStoresImplementationNotes() throws Exception {
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,display_name) VALUES('retro-planner','{noop}test','复盘排课员') ON CONFLICT (username) DO NOTHING");
        mockMvc.perform(get("/api/retrospectives?termCode=2026-FALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.solveAttempts").value(0))
                .andExpect(jsonPath("$.notes.supportInterventionCount").value(0));
        mockMvc.perform(
                        patch("/api/retrospectives")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "termCode", "2026-FALL",
                                        "ruleAdaptation", "增加教师每日上限",
                                        "legacyIssues", "无",
                                        "schoolFeedback", "希望支持班级视图",
                                        "supportInterventionCount", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes.ruleAdaptation").value("增加教师每日上限"))
                .andExpect(jsonPath("$.notes.supportInterventionCount").value(2));
    }
}
