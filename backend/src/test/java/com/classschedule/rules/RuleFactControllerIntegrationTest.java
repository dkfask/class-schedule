package com.classschedule.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
class RuleFactControllerIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("class_schedule_rules").withUsername("class_schedule").withPassword("class_schedule");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { registry.add("spring.datasource.url", POSTGRES::getJdbcUrl); registry.add("spring.datasource.username", POSTGRES::getUsername); registry.add("spring.datasource.password", POSTGRES::getPassword); }
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void writesAvailabilityFeaturesAndActivityGroup() throws Exception {
        mockMvc.perform(post("/api/rule-facts/availability/TEACHER").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"resourceCode\":\"T001\",\"termCode\":\"2026-FALL\",\"periodCode\":\"MON-1\",\"available\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UPDATED"));
        mockMvc.perform(post("/api/rule-facts/room-features").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"roomCode\":\"A101\",\"featureCode\":\"LAB\",\"featureName\":\"实验室\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/rule-facts/requirement-features").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"requirementCode\":\"REQ-1\",\"featureCode\":\"LAB\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/rule-facts/activity-groups").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"ACT-1\",\"name\":\"合班测试\",\"activityType\":\"JOINED\",\"requirementCodes\":[\"REQ-1\"]}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT available FROM teacher_availability WHERE teacher_id=(SELECT id FROM teacher WHERE code='T001') AND term_id=(SELECT id FROM academic_term WHERE code='2026-FALL') AND period_code='MON-1'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_feature_catalog WHERE code='LAB'", Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsUnknownResourceOrPeriod() throws Exception {
        mockMvc.perform(post("/api/rule-facts/availability/TEACHER").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"resourceCode\":\"MISSING\",\"termCode\":\"2026-FALL\",\"periodCode\":\"MON-1\",\"available\":false}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void listsAvailabilityAfterWrite() throws Exception {
        mockMvc.perform(post("/api/rule-facts/availability/ROOM").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"resourceCode\":\"A101\",\"termCode\":\"2026-FALL\",\"periodCode\":\"MON-2\",\"available\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/rule-facts/availability?termCode=2026-FALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.resourceType=='ROOM' && @.resourceCode=='A101' && @.periodCode=='MON-2')].available").value(false));
    }

    @Test
    void listsRoomAndRequirementFeaturesWithOptionalFilters() throws Exception {
        mockMvc.perform(get("/api/rule-facts/room-features"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/rule-facts/room-features?roomCode=A101"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/rule-facts/requirement-features"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/rule-facts/requirement-features?requirementCode=REQ-1"))
                .andExpect(status().isOk());
    }
}
