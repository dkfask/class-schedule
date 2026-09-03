package com.classschedule.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
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
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("class_schedule_auth")
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
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void seedUsers() {
        jdbc.update("DELETE FROM app_user_role");
        jdbc.update("UPDATE app_role SET active=TRUE");
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,display_name,enabled) VALUES(?,?,?,TRUE) ON CONFLICT (username) DO UPDATE SET password_hash=EXCLUDED.password_hash, display_name=EXCLUDED.display_name, enabled=TRUE",
                "auth-planner",
                encoder.encode("planner-pass"),
                "测试排课员");
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,display_name,enabled) VALUES(?,?,?,TRUE) ON CONFLICT (username) DO UPDATE SET password_hash=EXCLUDED.password_hash, display_name=EXCLUDED.display_name, enabled=TRUE",
                "auth-viewer",
                encoder.encode("viewer-pass"),
                "测试只读用户");
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,display_name,enabled) VALUES(?,?,?,TRUE) ON CONFLICT (username) DO UPDATE SET password_hash=EXCLUDED.password_hash, display_name=EXCLUDED.display_name, enabled=TRUE",
                "auth-admin",
                encoder.encode("admin-pass"),
                "测试管理员");
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,display_name,enabled) VALUES(?,?,?,FALSE) ON CONFLICT (username) DO UPDATE SET password_hash=EXCLUDED.password_hash, display_name=EXCLUDED.display_name, enabled=FALSE",
                "disabled-owner",
                encoder.encode("disabled-pass"),
                "停用排课员");
        jdbc.update(
                "INSERT INTO app_user_role(user_id,role_id) SELECT u.id,r.id FROM app_user u,app_role r WHERE u.username='auth-planner' AND r.code='PLANNER'");
        jdbc.update(
                "INSERT INTO app_user_role(user_id,role_id) SELECT u.id,r.id FROM app_user u,app_role r WHERE u.username='auth-viewer' AND r.code='VIEWER'");
        jdbc.update(
                "INSERT INTO app_user_role(user_id,role_id) SELECT u.id,r.id FROM app_user u,app_role r WHERE u.username='auth-admin' AND r.code IN ('PLANNER','USER_ADMIN')");
    }

    @Test
    void anonymousBusinessRequestIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/master-data/overview")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void loginAndMeUseSessionAuthentication() throws Exception {
        var result =
                mockMvc.perform(
                                post("/api/auth/login")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"username\":\"auth-planner\",\"password\":\"planner-pass\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.username").value("auth-planner"))
                        .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("PLANNER"));
    }

    @Test
    void loginRotatesAnExistingSessionId() throws Exception {
        MockHttpSession preAuthenticationSession = new MockHttpSession();
        String oldSessionId = preAuthenticationSession.getId();

        var result =
                mockMvc.perform(
                                post("/api/auth/login")
                                        .session(preAuthenticationSession)
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"username\":\"auth-planner\",\"password\":\"planner-pass\"}"))
                        .andExpect(status().isOk())
                        .andReturn();

        MockHttpSession authenticatedSession =
                (MockHttpSession) result.getRequest().getSession(false);
        assertThat(authenticatedSession.getId()).isNotEqualTo(oldSessionId);
        mockMvc.perform(get("/api/auth/me").session(authenticatedSession))
                .andExpect(status().isOk());
    }

    @Test
    void authenticatedWriteWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(
                        post("/api/rule-facts/availability/TEACHER")
                                .with(user("auth-planner").roles("PLANNER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"resourceCode\":\"T001\",\"termCode\":\"2026-FALL\",\"periodCode\":\"MON-1\",\"available\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "auth-viewer", roles = "VIEWER")
    void viewerCanOnlyReadPublishedScheduleArtifacts() throws Exception {
        long published = seedVersion("PUBLISHED");
        long archived = seedVersion("ARCHIVED");
        mockMvc.perform(get("/api/schedule-versions/" + published)).andExpect(status().isOk());
        mockMvc.perform(get("/api/schedule-versions/" + published + "/filtered?view=CLASS"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/schedule-versions/" + published + "/exports/xlsx"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/schedule-versions/" + archived))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/schedule-versions/" + archived + "/filtered?view=CLASS"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/schedule-versions/" + archived + "/exports/xlsx"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/schedule-versions/" + published + "/adjustments/commands"))
                .andExpect(status().isForbidden());
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
        mockMvc.perform(
                        post("/api/schedule-rules")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"termCode\":\"2026-FALL\",\"ruleCode\":\"TEACHER_DAILY_MAX\",\"scopeType\":\"TERM\",\"intValue\":4,\"severity\":\"HARD\",\"weight\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void plannerWithViewerRoleRetainsPlannerAccess() throws Exception {
        long archived = seedVersion("ARCHIVED");
        mockMvc.perform(
                        get("/api/schedule-versions/" + archived)
                                .with(user("auth-planner").roles("PLANNER", "VIEWER")))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "auth-viewer", roles = "VIEWER")
    void viewerCannotWritePlannerApi() throws Exception {
        mockMvc.perform(
                        post("/api/rule-facts/availability/TEACHER")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"resourceCode\":\"T001\",\"termCode\":\"2026-FALL\",\"periodCode\":\"MON-1\",\"available\":false}"))
                .andExpect(status().isForbidden());
    }

    private long seedVersion(String status) {
        return seedVersion(status, "auth-planner");
    }

    private long seedVersion(String status, String owner) {
        Long termId =
                jdbc.queryForObject(
                        "SELECT id FROM academic_term WHERE code='2026-FALL'", Long.class);
        Long scenarioId =
                jdbc.queryForObject(
                        "INSERT INTO schedule_scenario(term_id,owner_user_id,name) VALUES(?,(SELECT id FROM app_user WHERE username=?),?) RETURNING id",
                        Long.class,
                        termId,
                        owner,
                        "security-" + status + "-" + System.nanoTime());
        return jdbc.queryForObject(
                "INSERT INTO schedule_version(scenario_id,owner_user_id,status,score,legacy_identity_unverified) VALUES(?,(SELECT id FROM app_user WHERE username=?),?,?,FALSE) RETURNING id",
                Long.class,
                scenarioId,
                owner,
                status,
                "0hard/0soft");
    }

    @Test
    @WithMockUser(username = "auth-planner", roles = "PLANNER")
    void solveJobOwnershipBlocksAnotherPlanner() throws Exception {
        String created =
                mockMvc.perform(
                                post("/api/solve-jobs")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"idempotencyKey\":\"auth-owner-job\"}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        long jobId =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(created)
                        .get("jobId")
                        .asLong();
        mockMvc.perform(
                        get("/api/solve-jobs/" + jobId)
                                .with(user("other-planner").roles("PLANNER")))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        post("/api/solve-jobs/" + jobId + "/cancel")
                                .with(user("other-planner").roles("PLANNER"))
                                .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "auth-planner", roles = "PLANNER")
    void createdVersionIsOwnedBySubmittingPlanner() throws Exception {
        String created =
                mockMvc.perform(
                                post("/api/solve-jobs")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"idempotencyKey\":\"auth-owned-version-"
                                                        + System.nanoTime()
                                                        + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created);
        long versionId = body.get("versionId").asLong();

        mockMvc.perform(
                        get("/api/schedule-versions/" + versionId)
                                .with(user("auth-planner").roles("PLANNER")))
                .andExpect(status().isOk());
        mockMvc.perform(
                        get("/api/schedule-versions/" + versionId)
                                .with(user("other-planner").roles("PLANNER")))
                .andExpect(status().isNotFound());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT owner_user_id = (SELECT id FROM app_user WHERE username='auth-planner') FROM schedule_version WHERE id=?",
                                Boolean.class,
                                versionId))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT s.owner_user_id = v.owner_user_id FROM schedule_version v JOIN schedule_scenario s ON s.id=v.scenario_id WHERE v.id=?",
                                Boolean.class,
                                versionId))
                .isTrue();
    }

    @Test
    @WithMockUser(username = "auth-planner", roles = "PLANNER")
    void anotherPlannerCannotReadOrModifyAnOwnedVersion() throws Exception {
        long versionId = seedVersion("CANDIDATE");

        mockMvc.perform(
                        get("/api/schedule-versions/" + versionId)
                                .with(user("other-planner").roles("PLANNER")))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        post("/api/schedule-versions/" + versionId + "/adjustments/1")
                                .with(user("other-planner").roles("PLANNER"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"timeslotCode\":\"MON-1\",\"roomCode\":\"A101\",\"reason\":\"越权调整\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_NOT_OWNED"));
    }

    @Test
    @WithMockUser(username = "auth-planner", roles = "PLANNER")
    void diffCannotUseAnotherUsersVersionAsComparisonBaseline() throws Exception {
        long ownVersionId = seedVersion("CANDIDATE");
        long otherVersionId = seedVersion("CANDIDATE", "auth-viewer");

        mockMvc.perform(
                        get(
                                "/api/schedule-versions/"
                                        + ownVersionId
                                        + "/diff?againstVersionId="
                                        + otherVersionId))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(
            username = "auth-admin",
            roles = {"PLANNER", "USER_ADMIN"})
    void userAdminCanReadAndForkAnotherUsersVersion() throws Exception {
        long sourceVersionId = seedVersion("CANDIDATE");

        mockMvc.perform(get("/api/schedule-versions/" + sourceVersionId))
                .andExpect(status().isOk());
        String response =
                mockMvc.perform(
                                post("/api/schedule-versions/" + sourceVersionId + "/fork")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\":\"管理员分支\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("DRAFT"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        long forkedVersionId =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(response)
                        .get("versionId")
                        .asLong();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT v.owner_user_id = (SELECT id FROM app_user WHERE username='auth-admin') AND s.owner_user_id = v.owner_user_id FROM schedule_version v JOIN schedule_scenario s ON s.id=v.scenario_id WHERE v.id=?",
                                Boolean.class,
                                forkedVersionId))
                .isTrue();
    }

    @Test
    @WithMockUser(username = "auth-viewer", roles = "VIEWER")
    void viewerCannotReadAnUnpublishedVersionOwnedByAnotherUser() throws Exception {
        long versionId = seedVersion("CANDIDATE");
        mockMvc.perform(get("/api/schedule-versions/" + versionId))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/schedule-versions/" + versionId + "/filtered?view=CLASS"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "auth-planner", roles = "PLANNER")
    void disabledOwnerCannotReadItsPreviouslyOwnedJobOrVersion() throws Exception {
        String created =
                mockMvc.perform(
                                post("/api/solve-jobs")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"idempotencyKey\":\"disabled-owner-job-"
                                                        + System.nanoTime()
                                                        + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created);
        long jobId = body.get("jobId").asLong();
        long versionId = body.get("versionId").asLong();
        jdbc.update("UPDATE app_user SET enabled=FALSE WHERE username='auth-planner'");

        mockMvc.perform(get("/api/solve-jobs/" + jobId).with(user("auth-planner").roles("PLANNER")))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        post("/api/solve-jobs/" + jobId + "/cancel")
                                .with(user("auth-planner").roles("PLANNER"))
                                .with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        get("/api/schedule-versions/" + versionId)
                                .with(user("auth-planner").roles("PLANNER")))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "auth-planner", roles = "PLANNER")
    void inactiveAdminRoleCannotUseRepositoryOwnershipBypass() throws Exception {
        long versionId = seedVersion("CANDIDATE");
        jdbc.update("UPDATE app_role SET active=FALSE WHERE code='USER_ADMIN'");
        try {
            mockMvc.perform(
                            get("/api/schedule-versions/" + versionId)
                                    .with(user("auth-admin").roles("PLANNER", "USER_ADMIN")))
                    .andExpect(status().isNotFound());
        } finally {
            jdbc.update("UPDATE app_role SET active=TRUE WHERE code='USER_ADMIN'");
        }
    }

    @Test
    @WithMockUser(username = "auth-planner", roles = "PLANNER")
    void unknownVersionAndJobIdsHaveStableNotFoundResponses() throws Exception {
        long missingId = Long.MAX_VALUE;
        mockMvc.perform(get("/api/schedule-versions/" + missingId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/schedule-versions/" + missingId + "/filtered?view=CLASS"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/solve-jobs/" + missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
        mockMvc.perform(post("/api/solve-jobs/" + missingId + "/cancel").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
    }

    @Test
    @WithMockUser(username = "auth-planner", roles = "PLANNER")
    void concurrentSolveJobSubmissionsReturnTheSameHandle() throws Exception {
        String key = "auth-concurrent-job-" + System.nanoTime();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first =
                    executor.submit(
                            () -> {
                                start.await();
                                return mockMvc.perform(
                                                post("/api/solve-jobs")
                                                        .with(user("auth-planner").roles("PLANNER"))
                                                        .with(csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                "{\"idempotencyKey\":\""
                                                                        + key
                                                                        + "\"}"))
                                        .andReturn();
                            });
            var second =
                    executor.submit(
                            () -> {
                                start.await();
                                return mockMvc.perform(
                                                post("/api/solve-jobs")
                                                        .with(user("auth-planner").roles("PLANNER"))
                                                        .with(csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                "{\"idempotencyKey\":\""
                                                                        + key
                                                                        + "\"}"))
                                        .andReturn();
                            });
            start.countDown();

            var firstResponse = first.get().getResponse();
            var secondResponse = second.get().getResponse();
            assertThat(firstResponse.getStatus()).isEqualTo(200);
            assertThat(secondResponse.getStatus()).isEqualTo(200);
            var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var firstBody = objectMapper.readTree(firstResponse.getContentAsString());
            var secondBody = objectMapper.readTree(secondResponse.getContentAsString());
            assertThat(secondBody.get("jobId").asLong()).isEqualTo(firstBody.get("jobId").asLong());
            assertThat(secondBody.get("versionId").asLong())
                    .isEqualTo(firstBody.get("versionId").asLong());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM solve_job WHERE submitted_by_user_id = (SELECT id FROM app_user WHERE username = ?) AND idempotency_key = ? AND status IN ('QUEUED','RUNNING')",
                                    Integer.class,
                                    "auth-planner",
                                    key))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @WithMockUser(username = "auth-planner", roles = "PLANNER")
    void plannerCanAccessWriteBoundary() throws Exception {
        mockMvc.perform(
                        post("/api/rule-facts/availability/TEACHER")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"resourceCode\":\"T001\",\"termCode\":\"2026-FALL\",\"periodCode\":\"MON-1\",\"available\":false}"))
                .andExpect(status().isOk());
    }
}
