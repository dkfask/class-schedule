package com.classschedule.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
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
@WithMockUser(username = "test-planner", roles = "PLANNER")
class ScheduleCommandIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("class_schedule_command").withUsername("class_schedule").withPassword("class_schedule");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { registry.add("spring.datasource.url", POSTGRES::getJdbcUrl); registry.add("spring.datasource.username", POSTGRES::getUsername); registry.add("spring.datasource.password", POSTGRES::getPassword); }
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void seedOwners() {
        jdbc.update("INSERT INTO app_user(username,password_hash,display_name) VALUES('test-planner','{noop}test','测试排课员'),('alice','{noop}test','Alice'),('bob','{noop}test','Bob') ON CONFLICT (username) DO NOTHING");
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM adjustment_command_event");
        jdbc.update("DELETE FROM adjustment_command_group");
        jdbc.update("DELETE FROM activity_group_member");
        jdbc.update("DELETE FROM activity_group");
        jdbc.update("DELETE FROM teacher_availability");
        jdbc.update("DELETE FROM room_availability");
        jdbc.update("DELETE FROM student_group_availability");
    }

    @Test
    void adjustmentCreatesOneGroupAndIsIdempotent() throws Exception {
        long version = seedCandidateVersion();
        String key = "adjust-command-test";
        String body = objectMapper.writeValueAsString(Map.of("timeslotCode", "TUE-1", "roomCode", "A101", "reason", "命令组测试", "expectedRevision", 0));
        String response = mockMvc.perform(post("/api/schedule-versions/" + version + "/adjustments/1").header("Idempotency-Key", key).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groupId").isString()).andExpect(jsonPath("$.commandIds").isArray()).andExpect(jsonPath("$.revision").value(1)).andReturn().getResponse().getContentAsString();
        JsonNode first = objectMapper.readTree(response);
        mockMvc.perform(post("/api/schedule-versions/" + version + "/adjustments/1").header("Idempotency-Key", key).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groupId").value(first.get("groupId").asText())).andExpect(jsonPath("$.revision").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM adjustment_command_group WHERE schedule_version_id=?", Integer.class, version)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM adjustment_command WHERE schedule_version_id=?", Integer.class, version)).isEqualTo(1);
    }

    @Test
    void revisionConflictDoesNotWriteCommand() throws Exception {
        long version = seedCandidateVersion();
        String body = objectMapper.writeValueAsString(Map.of("timeslotCode", "TUE-1", "roomCode", "A101", "reason", "revision测试", "expectedRevision", 9));
        mockMvc.perform(post("/api/schedule-versions/" + version + "/adjustments/1").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_REVISION_CONFLICT")).andExpect(jsonPath("$.currentRevision").value(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM adjustment_command_group", Integer.class)).isZero();
    }

    @Test
    void undoAndRedoRestoreAssignmentAsAnAtomicGroup() throws Exception {
        long version = seedCandidateVersion();
        String body = objectMapper.writeValueAsString(Map.of("timeslotCode", "TUE-1", "roomCode", "A101", "reason", "撤销重做测试", "expectedRevision", 0));
        String response = mockMvc.perform(post("/api/schedule-versions/" + version + "/adjustments/1").header("Idempotency-Key", "undo-source").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString();
        UUID groupId = UUID.fromString(objectMapper.readTree(response).get("groupId").asText());
        mockMvc.perform(post("/api/schedule-versions/" + version + "/adjustments/commands/" + groupId + "/undo").header("Idempotency-Key", "undo-event").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNDONE")).andExpect(jsonPath("$.revision").value(2));
        assertThat(jdbc.queryForObject("SELECT timeslot_code FROM schedule_assignment WHERE schedule_version_id=? AND occurrence_id=1", String.class, version)).isEqualTo("MON-1");
        mockMvc.perform(post("/api/schedule-versions/" + version + "/adjustments/commands/" + groupId + "/redo").header("Idempotency-Key", "redo-event").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REDONE")).andExpect(jsonPath("$.revision").value(3));
        assertThat(jdbc.queryForObject("SELECT timeslot_code FROM schedule_assignment WHERE schedule_version_id=? AND occurrence_id=1", String.class, version)).isEqualTo("TUE-1");
    }

    @Test
    void lockBlocksOtherOwnerAndUnlockRestoresEditing() throws Exception {
        long version = seedCandidateVersion("alice");
        mockMvc.perform(post("/api/schedule-versions/" + version + "/lock").with(user("alice").roles("PLANNER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("owner", "alice", "reason", "审核中"))))
                .andExpect(status().isOk());
        String body = objectMapper.writeValueAsString(Map.of("timeslotCode", "TUE-1", "roomCode", "A101", "reason", "锁定写入", "expectedRevision", 1));
        mockMvc.perform(post("/api/schedule-versions/" + version + "/adjustments/1").with(user("bob").roles("PLANNER")).header("X-Actor", "bob").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_NOT_OWNED"));
        mockMvc.perform(delete("/api/schedule-versions/" + version + "/lock").with(user("alice").roles("PLANNER")).with(csrf()).header("X-Actor", "alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNLOCKED"));
    }

    @Test
    void lockOwnerCanPublishButOtherOwnerCannot() throws Exception {
        long version = seedCandidateVersion("alice");
        mockMvc.perform(post("/api/schedule-versions/" + version + "/lock")
                        .with(user("alice").roles("PLANNER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("owner", "alice", "reason", "发布审核"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/schedule-versions/" + version + "/publish")
                        .with(user("bob").roles("PLANNER")).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_NOT_OWNED"));
        mockMvc.perform(post("/api/schedule-versions/" + version + "/publish")
                        .with(user("alice").roles("PLANNER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void commandHistoryReturnsGroupedChildren() throws Exception {
        long version = seedCandidateVersion();
        String body = objectMapper.writeValueAsString(Map.of("timeslotCode", "TUE-1", "roomCode", "A101", "reason", "历史测试"));
        mockMvc.perform(post("/api/schedule-versions/" + version + "/adjustments/1").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mockMvc.perform(get("/api/schedule-versions/" + version + "/adjustments/commands")).andExpect(status().isOk()).andExpect(jsonPath("$[0].commandType").value("ADJUST")).andExpect(jsonPath("$[0].commands[0].sequence").value(1));
    }

    private long seedCandidateVersion() {
        return seedCandidateVersion("test-planner");
    }

    private long seedCandidateVersion(String owner) {
        Long term = jdbc.queryForObject("SELECT id FROM academic_term WHERE code='2026-FALL'", Long.class);
        Long scenario = jdbc.queryForObject("INSERT INTO schedule_scenario(term_id,owner_user_id,name) VALUES(?,(SELECT id FROM app_user WHERE username=?), 'command-test') RETURNING id", Long.class, term, owner);
        Long version = jdbc.queryForObject("INSERT INTO schedule_version(scenario_id,owner_user_id,status,score,legacy_identity_unverified) VALUES(?,(SELECT id FROM app_user WHERE username=?), 'CANDIDATE','0hard/0soft',FALSE) RETURNING id", Long.class, scenario, owner);
        jdbc.update("INSERT INTO schedule_assignment(schedule_version_id,occurrence_id,occurrence_key,subject_code,subject_name,teacher_code,teacher_name,student_group_code,student_group_name,timeslot_code,timeslot_label,weekday,period_no,room_code,room_name,source,locked,duration,student_count,required_features,room_features,room_capacity) VALUES(?,1,'1','MATH','数学','T001','张老师','G7-1','七年级1班','MON-1','周一 第1节',1,1,'A101','教学楼 A101','SOLVER',false,1,0,'{}','{}',50)", version);
        jdbc.update("INSERT INTO schedule_assignment(schedule_version_id,occurrence_id,occurrence_key,subject_code,subject_name,teacher_code,teacher_name,student_group_code,student_group_name,timeslot_code,timeslot_label,weekday,period_no,room_code,room_name,source,locked,duration,student_count,required_features,room_features,room_capacity) VALUES(?,2,'2','CHN','语文','T001','张老师','G7-2','七年级2班','MON-2','周一 第2节',1,2,'A102','教学楼 A102','SOLVER',false,1,0,'{}','{}',50)", version);
        return version;
    }
}
