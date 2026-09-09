package com.classschedule.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
        jdbc.update("DELETE FROM audit_event WHERE actor='notification-user'");
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
