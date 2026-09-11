package com.classschedule.aiassist;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(username = "test-planner", roles = "PLANNER")
class AiAssistControllerIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("class_schedule_aiassist")
                    .withUsername("class_schedule")
                    .withPassword("class_schedule");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.ai.config-encryption-key", () -> "test-encryption-key");
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AiModelSettingsService aiModelSettings;

    @org.junit.jupiter.api.BeforeEach
    void clearPersistedSettings() {
        jdbc.update("DELETE FROM ai_model_settings");
        aiModelSettings.reload();
    }

    @Test
    void statusReportsDiagnosticsEnabledAndChatDisabledByDefault() throws Exception {
        mockMvc.perform(get("/api/ai-assist/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnosticsEnabled").value(true))
                .andExpect(jsonPath("$.chatEnabled").value(false));
    }

    @Test
    void chatReturnsServiceUnavailableWhenNotConfigured() throws Exception {
        mockMvc.perform(
                        post("/api/ai-assist/chat")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"messages\":[{\"content\":\"现在能发布吗\"}]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_NOT_CONFIGURED"));
    }

    @Test
    void chatRejectsEmptyMessages() throws Exception {
        mockMvc.perform(
                        post("/api/ai-assist/chat")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"messages\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void diagnosticsOfUnknownVersionReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/ai-assist/diagnostics/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @org.springframework.security.test.context.support.WithAnonymousUser
    void aiAssistRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/ai-assist/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void plannerCannotManageAiSettings() throws Exception {
        mockMvc.perform(get("/api/ai-assist/settings")).andExpect(status().isForbidden());
    }

    @Test
    void systemAdminCanReadAndUpdateAiSettingsWithoutPlannerRole() throws Exception {
        var admin =
                user("ai-admin")
                        .authorities(
                                new SimpleGrantedAuthority("ROLE_USER_ADMIN"),
                                new SimpleGrantedAuthority("AI_CONFIG_MANAGE"));

        mockMvc.perform(get("/api/ai-assist/settings").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyConfigured").value(false))
                .andExpect(jsonPath("$.protocol").value("OPENAI"))
                .andExpect(jsonPath("$.source").value("ENVIRONMENT"));

        mockMvc.perform(
                        put("/api/ai-assist/settings")
                                .with(admin)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"baseUrl\":\"https://api.minimax.cn/anthropic\",\"protocol\":\"ANTHROPIC\",\"model\":\"MiniMax-M2.7\",\"apiKey\":\"secret-value\",\"clearApiKey\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protocol").value("ANTHROPIC"))
                .andExpect(jsonPath("$.model").value("MiniMax-M2.7"))
                .andExpect(jsonPath("$.apiKeyConfigured").value(true))
                .andExpect(jsonPath("$.chatEnabled").value(true))
                .andExpect(jsonPath("$.apiKey").doesNotExist());

        mockMvc.perform(get("/api/ai-assist/settings").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("ADMIN"))
                .andExpect(jsonPath("$.apiKeyConfigured").value(true));
    }

    @Test
    void userAdminNeedsAiConfigPermission() throws Exception {
        mockMvc.perform(
                        get("/api/ai-assist/settings")
                                .with(user("ai-admin-without-permission").roles("USER_ADMIN")))
                .andExpect(status().isForbidden());
    }
}
