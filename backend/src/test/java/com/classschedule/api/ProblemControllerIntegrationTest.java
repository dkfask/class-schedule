package com.classschedule.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
@WithMockUser(username = "problem-planner", roles = "PLANNER")
class ProblemControllerIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("class_schedule_problems")
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
    void seedUser() {
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,display_name) VALUES('problem-planner','{noop}test','问题排课员') ON CONFLICT (username) DO NOTHING");
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM problem_record");
    }

    @Test
    void createsListsAndClosesProblemWithContext() throws Exception {
        String create =
                objectMapper.writeValueAsString(
                        Map.of(
                                "termCode", "2026-FALL",
                                "title", "数学有两节课未分配",
                                "description", "求解完成后仍有教学任务未安排",
                                "priority", "HIGH",
                                "category", "DATA",
                                "evidence", "solve job #9"));
        String location =
                mockMvc.perform(
                                post("/api/problems")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(create))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("OPEN"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        long id = objectMapper.readTree(location).get("id").asLong();
        mockMvc.perform(get("/api/problems?termCode=2026-FALL&category=DATA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("数学有两节课未分配"))
                .andExpect(jsonPath("$.items[0].priority").value("HIGH"));
        mockMvc.perform(
                        patch("/api/problems/" + id)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("status", "CLOSED", "resolution", "已补充人工调整并复核"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        org.assertj.core.api.Assertions.assertThat(
                        jdbc.queryForObject("SELECT status FROM problem_record WHERE id=?", String.class, id))
                .isEqualTo("CLOSED");
    }
}
