package com.classschedule.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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
@WithMockUser(username = "template-planner", roles = "PLANNER")
class RuleTemplateControllerIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("class_schedule_rule_templates")
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
    void seedRule() {
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,display_name) VALUES('template-planner','{noop}test','模板排课员') ON CONFLICT (username) DO NOTHING");
        Long profile = jdbc.queryForObject(
                "INSERT INTO schedule_rule_profile(term_id,code,name) VALUES((SELECT id FROM academic_term WHERE code='2026-FALL'),'DEFAULT','默认规则配置') ON CONFLICT(term_id,code) DO UPDATE SET active=TRUE RETURNING id",
                Long.class);
        jdbc.update(
                "INSERT INTO schedule_rule_instance(profile_id,rule_code,scope_type,scope_code,int_value,severity,weight) VALUES(?, 'TEACHER_DAILY_MAX','TERM','__TERM__',4,'HARD',1) ON CONFLICT DO NOTHING",
                profile);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM rule_template");
        jdbc.update("DELETE FROM schedule_rule_profile WHERE term_id=(SELECT id FROM academic_term WHERE code='2026-FALL')");
    }

    @Test
    void snapshotsPreviewsAndAppliesRuleTemplate() throws Exception {
        String create = objectMapper.writeValueAsString(Map.of(
                "sourceTermCode", "2026-FALL",
                "code", "SCHOOL-DEFAULT",
                "name", "学校默认规则",
                "description", "试点规则基线",
                "maintainedBy", "实施团队",
                "changeNote", "首版"));
        String body = mockMvc.perform(
                        post("/api/rule-templates")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(create))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemCount").value(1))
                .andReturn().getResponse().getContentAsString();
        JsonNode created = objectMapper.readTree(body);
        long id = created.get("id").asLong();

        mockMvc.perform(get("/api/rule-templates/" + id + "/preview?termCode=2026-FALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").isEmpty())
                .andExpect(jsonPath("$.modified").isEmpty())
                .andExpect(jsonPath("$.canApply").value(true));
        mockMvc.perform(
                        post("/api/rule-templates/" + id + "/apply")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("termCode", "2026-FALL"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
    }
}
