package com.classschedule.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class MasterDataCrudIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("class_schedule_masterdata").withUsername("class_schedule").withPassword("class_schedule");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { registry.add("spring.datasource.url", POSTGRES::getJdbcUrl); registry.add("spring.datasource.username", POSTGRES::getUsername); registry.add("spring.datasource.password", POSTGRES::getPassword); }
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @Test void teacherCrudAndSoftDeactivate() throws Exception {
        mockMvc.perform(post("/api/master-data/teachers").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"TCRUD\",\"name\":\"CRUD教师\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("TCRUD"));
        mockMvc.perform(post("/api/master-data/teachers").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"TCRUD\",\"name\":\"重复\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/master-data/teachers/" + jdbc.queryForObject("SELECT id FROM teacher WHERE code='TCRUD'", Long.class)).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT active FROM teacher WHERE code='TCRUD'", Boolean.class)).isFalse();
    }

    @Test void requirementRejectsInactiveTeacher() throws Exception {
        mockMvc.perform(post("/api/master-data/teaching-requirements").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"REQCRUD\",\"termCode\":\"2026-FALL\",\"studentGroupCode\":\"G7-1\",\"subjectCode\":\"MATH\",\"teacherCode\":\"missing\",\"weeklyPeriods\":1,\"durationPeriods\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEACHING_REQUIREMENT_CONFLICT"));
    }

    @Test void teachingRequirementUpdateRejectsDuplicateCode() throws Exception {
        mockMvc.perform(post("/api/master-data/teaching-requirements").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"REQ-A\",\"termCode\":\"2026-FALL\",\"studentGroupCode\":\"G7-1\",\"subjectCode\":\"MATH\",\"teacherCode\":\"T001\",\"weeklyPeriods\":1,\"durationPeriods\":1}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/master-data/teaching-requirements").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"REQ-B\",\"termCode\":\"2026-FALL\",\"studentGroupCode\":\"G7-2\",\"subjectCode\":\"CHN\",\"teacherCode\":\"T002\",\"weeklyPeriods\":1,\"durationPeriods\":1}"))
                .andExpect(status().isCreated());
        long secondId = jdbc.queryForObject("SELECT id FROM teaching_requirement WHERE code='REQ-B'", Long.class);
        mockMvc.perform(patch("/api/master-data/teaching-requirements/" + secondId).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"REQ-A\",\"termCode\":\"2026-FALL\",\"studentGroupCode\":\"G7-2\",\"subjectCode\":\"CHN\",\"teacherCode\":\"T002\",\"weeklyPeriods\":1,\"durationPeriods\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEACHING_REQUIREMENT_CONFLICT"));
    }

    @Test void listSupportsResourcePagination() throws Exception {
        mockMvc.perform(get("/api/master-data/teachers?page=0&size=2")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray()).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(2));
    }
}
