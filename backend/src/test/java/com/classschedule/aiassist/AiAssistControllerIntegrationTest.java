package com.classschedule.aiassist;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
    }

    @Autowired MockMvc mockMvc;

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
}
