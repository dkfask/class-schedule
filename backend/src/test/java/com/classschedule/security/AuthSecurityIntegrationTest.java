package com.classschedule.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
import org.springframework.security.crypto.password.PasswordEncoder;
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
class AuthSecurityIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("class_schedule_auth").withUsername("class_schedule").withPassword("class_schedule");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { registry.add("spring.datasource.url", POSTGRES::getJdbcUrl); registry.add("spring.datasource.username", POSTGRES::getUsername); registry.add("spring.datasource.password", POSTGRES::getPassword); }
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void seedUsers() {
        jdbc.update("DELETE FROM solve_job WHERE submitted_by_user_id IN (SELECT id FROM app_user WHERE username IN ('auth-planner','auth-viewer'))");
        jdbc.update("DELETE FROM app_user_role");
        jdbc.update("DELETE FROM app_user WHERE username IN ('auth-planner','auth-viewer')");
        jdbc.update("INSERT INTO app_user(username,password_hash,display_name) VALUES(?,?,?)", "auth-planner", encoder.encode("planner-pass"), "测试排课员");
        jdbc.update("INSERT INTO app_user(username,password_hash,display_name) VALUES(?,?,?)", "auth-viewer", encoder.encode("viewer-pass"), "测试只读用户");
        jdbc.update("INSERT INTO app_user_role(user_id,role_id) SELECT u.id,r.id FROM app_user u,app_role r WHERE u.username='auth-planner' AND r.code='PLANNER'");
        jdbc.update("INSERT INTO app_user_role(user_id,role_id) SELECT u.id,r.id FROM app_user u,app_role r WHERE u.username='auth-viewer' AND r.code='VIEWER'");
    }

    @Test
    void anonymousBusinessRequestIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/master-data/overview")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void loginAndMeUseSessionAuthentication() throws Exception {
        var result = mockMvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"auth-planner\",\"password\":\"planner-pass\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("auth-planner")).andReturn();
        org.springframework.mock.web.MockHttpSession session = (org.springframework.mock.web.MockHttpSession) result.getRequest().getSession(false);
        mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("PLANNER"));
    }

    @Test
    void authenticatedWriteWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/rule-facts/availability/TEACHER")
                        .with(user("auth-planner").roles("PLANNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceCode\":\"T001\",\"termCode\":\"2026-FALL\",\"periodCode\":\"MON-1\",\"available\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "auth-viewer", roles = "VIEWER")
    void viewerCanOnlyReadPublishedScheduleArtifacts() throws Exception {
        long published = seedVersion("PUBLISHED");
        long archived = seedVersion("ARCHIVED");
        mockMvc.perform(get("/api/schedule-versions/" + published)).andExpect(status().isOk());
        mockMvc.perform(get("/api/schedule-versions/" + published + "/filtered?view=CLASS")).andExpect(status().isOk());
        mockMvc.perform(get("/api/schedule-versions/" + published + "/exports/xlsx")).andExpect(status().isOk());
        mockMvc.perform(get("/api/schedule-versions/" + archived)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/schedule-versions/" + archived + "/filtered?view=CLASS")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/schedule-versions/" + archived + "/exports/xlsx")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/schedule-versions/" + published + "/adjustments/commands")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "auth-viewer", roles = "VIEWER")
    void viewerCanReadActiveTerms() throws Exception {
        mockMvc.perform(get("/api/terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("2026-FALL"));
    }
    @Test
    @WithMockUser(username = "auth-viewer", roles = "VIEWER")
    void viewerCannotManageTypedRules() throws Exception {
        mockMvc.perform(post("/api/schedule-rules").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"termCode\":\"2026-FALL\",\"ruleCode\":\"TEACHER_DAILY_MAX\",\"scopeType\":\"TERM\",\"intValue\":4,\"severity\":\"HARD\",\"weight\":1}"))
                .andExpect(status().isForbidden());
    }


    @Test
    void plannerWithViewerRoleRetainsPlannerAccess() throws Exception {
        long archived = seedVersion("ARCHIVED");
        mockMvc.perform(get("/api/schedule-versions/" + archived).with(user("auth-admin").roles("PLANNER", "VIEWER")))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "auth-viewer", roles = "VIEWER")
    void viewerCannotWritePlannerApi() throws Exception {
        mockMvc.perform(post("/api/rule-facts/availability/TEACHER").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"resourceCode\":\"T001\",\"termCode\":\"2026-FALL\",\"periodCode\":\"MON-1\",\"available\":false}"))
                .andExpect(status().isForbidden());
    }

    private long seedVersion(String status) {
        Long termId = jdbc.queryForObject("SELECT id FROM academic_term WHERE code='2026-FALL'", Long.class);
        Long scenarioId = jdbc.queryForObject("INSERT INTO schedule_scenario(term_id,name) VALUES(?,?) RETURNING id", Long.class, termId, "security-" + status + "-" + System.nanoTime());
        return jdbc.queryForObject("INSERT INTO schedule_version(scenario_id,status,score) VALUES(?,?,?) RETURNING id", Long.class, scenarioId, status, "0hard/0soft");
    }

    @Test
    @WithMockUser(username = "auth-planner", roles = "PLANNER")
    void solveJobOwnershipBlocksAnotherPlanner() throws Exception {
        String created = mockMvc.perform(post("/api/solve-jobs").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"idempotencyKey\":\"auth-owner-job\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long jobId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).get("jobId").asLong();
        mockMvc.perform(get("/api/solve-jobs/" + jobId).with(user("other-planner").roles("PLANNER")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/solve-jobs/" + jobId + "/cancel").with(user("other-planner").roles("PLANNER")).with(csrf()))
                .andExpect(status().isNotFound());
    }
    @Test
    @WithMockUser(username = "auth-planner", roles = "PLANNER")
    void plannerCanAccessWriteBoundary() throws Exception {
        mockMvc.perform(post("/api/rule-facts/availability/TEACHER").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"resourceCode\":\"T001\",\"termCode\":\"2026-FALL\",\"periodCode\":\"MON-1\",\"available\":false}"))
                .andExpect(status().isOk());
    }
}
