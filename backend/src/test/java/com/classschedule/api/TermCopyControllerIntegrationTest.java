package com.classschedule.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.assertj.core.api.Assertions;
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
@WithMockUser(username = "term-copy-planner", roles = "PLANNER")
class TermCopyControllerIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("class_schedule_term_copy")
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
        jdbc.update("DELETE FROM schedule_rule_profile WHERE term_id = (SELECT id FROM academic_term WHERE code = '2027-SPRING')");
        jdbc.update("DELETE FROM activity_group_member WHERE activity_group_id IN (SELECT id FROM activity_group WHERE term_id = (SELECT id FROM academic_term WHERE code = '2027-SPRING'))");
        jdbc.update("DELETE FROM activity_group WHERE term_id = (SELECT id FROM academic_term WHERE code = '2027-SPRING')");
        jdbc.update("DELETE FROM teaching_requirement_feature WHERE teaching_requirement_id IN (SELECT r.id FROM teaching_requirement r JOIN academic_term t ON t.id=r.term_id WHERE t.code = '2027-SPRING')");
        jdbc.update("DELETE FROM teaching_requirement WHERE term_id = (SELECT id FROM academic_term WHERE code = '2027-SPRING')");
        jdbc.update("DELETE FROM teacher_availability WHERE term_id = (SELECT id FROM academic_term WHERE code = '2027-SPRING')");
        jdbc.update("DELETE FROM room_availability WHERE term_id = (SELECT id FROM academic_term WHERE code = '2027-SPRING')");
        jdbc.update("DELETE FROM student_group_availability WHERE term_id = (SELECT id FROM academic_term WHERE code = '2027-SPRING')");
        jdbc.update("DELETE FROM period_template WHERE term_id = (SELECT id FROM academic_term WHERE code = '2027-SPRING')");
        jdbc.update("DELETE FROM academic_term WHERE code = '2027-SPRING'");
    }

    @Test
    void previewsAndCopiesReusableTermDataWithoutPublishedFacts() throws Exception {
        String request =
                objectMapper.writeValueAsString(
                        Map.of(
                                "sourceTermCode", "2026-FALL",
                                "targetTermCode", "2027-SPRING",
                                "targetName", "2027 春季学期"));
        mockMvc.perform(
                        post("/api/terms/copy/preview")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canCopy").value(true))
                .andExpect(jsonPath("$.counts.requirements").value(3))
                .andExpect(jsonPath("$.willNotCopy[0]").value("已发布课表事实"));

        mockMvc.perform(
                        post("/api/terms/copy")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COPIED"))
                .andExpect(jsonPath("$.copied.requirements").value(3))
                .andExpect(jsonPath("$.needsRecheck").value(true));

        Assertions.assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM teaching_requirement r JOIN academic_term t ON t.id=r.term_id WHERE t.code='2027-SPRING'",
                                Integer.class))
                .isEqualTo(3);
        Assertions.assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM schedule_version v JOIN schedule_scenario s ON s.id=v.scenario_id JOIN academic_term t ON t.id=s.term_id WHERE t.code='2027-SPRING'",
                                Integer.class))
                .isZero();
    }
}
