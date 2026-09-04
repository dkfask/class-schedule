package com.classschedule.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
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
class SolveReadinessControllerIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("class_schedule_readiness_controller")
                    .withUsername("class_schedule")
                    .withPassword("class_schedule");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedPlannerIdentity() {
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,display_name,enabled) VALUES('readiness-planner','{noop}test','测试排课员',TRUE) ON CONFLICT (username) DO UPDATE SET enabled=TRUE");
    }

    @Test
    @WithMockUser(username = "readiness-planner", roles = "PLANNER")
    void plannerGetsReadyPayloadForCurrentTerm() throws Exception {
        mockMvc.perform(get("/api/solve-readiness").param("termCode", "2026-FALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.termCode").value("2026-FALL"))
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.timeslotCount").value(30))
                .andExpect(jsonPath("$.roomCount").value(2))
                .andExpect(jsonPath("$.requirementCount").value(3))
                .andExpect(jsonPath("$.issues").isArray())
                .andExpect(jsonPath("$.issues").isEmpty());
    }

    @Test
    @WithMockUser(username = "readiness-planner", roles = "PLANNER")
    void plannerGetsStructuredIssueForUnknownTerm() throws Exception {
        mockMvc.perform(get("/api/solve-readiness").param("termCode", "  MISSING-TERM  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.termCode").value("MISSING-TERM"))
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.timeslotCount").value(0))
                .andExpect(jsonPath("$.roomCount").value(0))
                .andExpect(jsonPath("$.requirementCount").value(0))
                .andExpect(jsonPath("$.issues[0].code").value("TERM_NOT_FOUND"));
    }

    @Test
    @WithMockUser(username = "readiness-viewer", roles = "VIEWER")
    void viewerCannotCheckReadiness() throws Exception {
        mockMvc.perform(get("/api/solve-readiness").param("termCode", "2026-FALL"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "readiness-planner", roles = "PLANNER")
    void blockedEnqueueReturnsReadinessDetailsWithoutCreatingRows() throws Exception {
        String termCode = "CTRL-BLOCK-" + System.nanoTime();
        jdbc.update(
                "INSERT INTO academic_term(code,name,status) VALUES(?,?, 'DRAFT')",
                termCode,
                "controller readiness block");
        int scenariosBefore =
                jdbc.queryForObject("SELECT COUNT(*) FROM schedule_scenario", Integer.class);
        int versionsBefore =
                jdbc.queryForObject("SELECT COUNT(*) FROM schedule_version", Integer.class);
        int jobsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM solve_job", Integer.class);

        try {
            mockMvc.perform(
                            post("/api/solve-jobs")
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"idempotencyKey\":\"controller-block-"
                                                    + System.nanoTime()
                                                    + "\",\"termCode\":\""
                                                    + termCode
                                                    + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SOLVER_DATA_NOT_READY"))
                    .andExpect(jsonPath("$.readiness.termCode").value(termCode))
                    .andExpect(jsonPath("$.readiness.ready").value(false))
                    .andExpect(jsonPath("$.readiness.issues[0].code").value("NO_TIMESLOTS"))
                    .andExpect(
                            jsonPath("$.readiness.issues[1].code").value("NO_ACTIVE_REQUIREMENTS"));
            org.assertj.core.api.Assertions.assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM schedule_scenario", Integer.class))
                    .isEqualTo(scenariosBefore);
            org.assertj.core.api.Assertions.assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM schedule_version", Integer.class))
                    .isEqualTo(versionsBefore);
            org.assertj.core.api.Assertions.assertThat(
                            jdbc.queryForObject("SELECT COUNT(*) FROM solve_job", Integer.class))
                    .isEqualTo(jobsBefore);
        } finally {
            jdbc.update("DELETE FROM academic_term WHERE code = ?", termCode);
        }
    }
}
