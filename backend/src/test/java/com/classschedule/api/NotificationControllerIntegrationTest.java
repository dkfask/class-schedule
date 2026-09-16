package com.classschedule.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
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
@WithMockUser(username = "notification-user", roles = "VIEWER")
class NotificationControllerIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("class_schedule_notifications")
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

    @BeforeEach
    void seed() {
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,display_name) VALUES('notification-user','{noop}test','通知用户') ON CONFLICT (username) DO NOTHING");
        jdbc.update(
                "INSERT INTO audit_event(action,aggregate_type,aggregate_id,actor,actor_user_id,actor_kind,detail) VALUES('PUBLISH','SCHEDULE_VERSION','24','notification-user',(SELECT id FROM app_user WHERE username='notification-user'),'USER',jsonb_build_object('termCode','2026-FALL'))");
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM app_notification");
        jdbc.update("DELETE FROM audit_event WHERE actor IN ('notification-user','approval-owner','approval-planner','worker')");
        jdbc.update("DELETE FROM schedule_version WHERE scenario_id IN (SELECT id FROM schedule_scenario WHERE name='approval-notify')");
        jdbc.update("DELETE FROM schedule_scenario WHERE name='approval-notify'");
    }

    @Test
    void materializesOwnerApprovalEventsForPlannerAndBusinessOwner() throws Exception {
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,display_name,enabled) VALUES('approval-owner','{noop}test','业务负责人',TRUE) ON CONFLICT (username) DO UPDATE SET enabled=TRUE");
        jdbc.update(
                "INSERT INTO app_user_role(user_id,role_id) SELECT u.id,r.id FROM app_user u, app_role r WHERE u.username='approval-owner' AND r.code='BUSINESS_OWNER' ON CONFLICT DO NOTHING");
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,display_name,enabled) VALUES('approval-planner','{noop}test','排课员',TRUE) ON CONFLICT (username) DO UPDATE SET enabled=TRUE");
        Long termId = jdbc.queryForObject("SELECT id FROM academic_term WHERE code='2026-FALL'", Long.class);
        Long scenarioId =
                jdbc.queryForObject(
                        "INSERT INTO schedule_scenario(term_id,owner_user_id,name) VALUES(?,(SELECT id FROM app_user WHERE username='approval-planner'),'approval-notify') RETURNING id",
                        Long.class,
                        termId);
        Long versionId =
                jdbc.queryForObject(
                        "INSERT INTO schedule_version(scenario_id,owner_user_id,status,owner_approval_status) VALUES(?,(SELECT id FROM app_user WHERE username='approval-planner'),'CANDIDATE','PENDING') RETURNING id",
                        Long.class,
                        scenarioId);
        jdbc.update(
                "INSERT INTO audit_event(action,aggregate_type,aggregate_id,actor,actor_kind,detail) VALUES('OWNER_APPROVAL_PENDING','SCHEDULE_VERSION',?,'worker','SERVICE',jsonb_build_object('termCode','2026-FALL'))",
                String.valueOf(versionId));
        jdbc.update(
                "INSERT INTO audit_event(action,aggregate_type,aggregate_id,actor,actor_user_id,actor_kind,detail) VALUES('OWNER_APPROVE','SCHEDULE_VERSION',?,'approval-owner',(SELECT id FROM app_user WHERE username='approval-owner'),'USER',jsonb_build_object('termCode','2026-FALL','releaseNote','可以面向全校'))",
                String.valueOf(versionId));
        jdbc.update(
                "INSERT INTO audit_event(action,aggregate_type,aggregate_id,actor,actor_user_id,actor_kind,detail) VALUES('OWNER_REJECT','SCHEDULE_VERSION',?,'approval-owner',(SELECT id FROM app_user WHERE username='approval-owner'),'USER',jsonb_build_object('termCode','2026-FALL','releaseNote','体育课连堂需要再调'))",
                String.valueOf(versionId));

        mockMvc.perform(get("/api/notifications").with(user("approval-owner").roles("BUSINESS_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.title=='候选课表待您批准')]").exists())
                .andExpect(jsonPath("$.items[?(@.title=='业务负责人已批准候选课表')]").exists())
                .andExpect(jsonPath("$.items[?(@.mandatory==true && @.title=='候选课表待您批准')]").exists());
        mockMvc.perform(get("/api/notifications").with(user("approval-planner").roles("PLANNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.title=='候选课表待您批准')]").doesNotExist())
                .andExpect(jsonPath("$.items[?(@.title=='业务负责人已批准候选课表')]").exists())
                .andExpect(jsonPath("$.items[?(@.title=='业务负责人已退回候选课表')].message").value(hasItem(containsString("体育课连堂需要再调"))));
    }

    @Test
    void materializesAuditEventAndTracksNotificationState() throws Exception {
        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingCount").value(1))
                .andExpect(jsonPath("$.items[0].title").value("课表版本已发布"));
        long id = jdbc.queryForObject("SELECT id FROM app_notification LIMIT 1", Long.class);
        mockMvc.perform(
                        patch("/api/notifications/" + id)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("status", "DONE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
        mockMvc.perform(get("/api/notifications?status=DONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("DONE"))
                .andExpect(jsonPath("$.pendingCount").value(0));
    }
}
