package com.classschedule.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.classschedule.api.AdjustmentPreviewRequest;
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
@WithMockUser(username = "test-planner", roles = "PLANNER")
class ScheduleQueryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("class_schedule_query")
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

    @BeforeEach
    void seedOwner() {
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,display_name) VALUES('test-planner','{noop}test','测试排课员') ON CONFLICT (username) DO NOTHING");
    }

    @AfterEach
    void cleanRuleFacts() {
        jdbc.update("DELETE FROM schedule_rule_instance");
        jdbc.update("DELETE FROM schedule_rule_profile");
        jdbc.update("DELETE FROM activity_group_member");
        jdbc.update("DELETE FROM activity_group");
        jdbc.update("DELETE FROM teacher_availability");
        jdbc.update("DELETE FROM room_availability");
        jdbc.update("DELETE FROM student_group_availability");
    }

    @Test
    void softViolationsDoNotBlockAdjustUndoRedoOrExchange() throws Exception {
        long version = seedCandidateVersion();
        addSoftSpreadRule();
        ObjectMapper objectMapper = new ObjectMapper();

        String adjusted =
                mockMvc.perform(
                                post("/api/schedule-versions/" + version + "/adjustments/1")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "timeslotCode",
                                                                "TUE-1",
                                                                "roomCode",
                                                                "A101",
                                                                "reason",
                                                                "软规则调整"))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String adjustGroup = objectMapper.readTree(adjusted).get("groupId").asText();

        mockMvc.perform(
                        post("/api/schedule-versions/"
                                        + version
                                        + "/adjustments/commands/"
                                        + adjustGroup
                                        + "/undo")
                                .with(csrf())
                                .header("Idempotency-Key", "soft-undo-" + System.nanoTime()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDONE"));
        mockMvc.perform(
                        post("/api/schedule-versions/"
                                        + version
                                        + "/adjustments/commands/"
                                        + adjustGroup
                                        + "/redo")
                                .with(csrf())
                                .header("Idempotency-Key", "soft-redo-" + System.nanoTime()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REDONE"));

        String exchanged =
                mockMvc.perform(
                                post("/api/schedule-versions/" + version + "/adjustments/exchange")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "occurrenceId",
                                                                1,
                                                                "swapOccurrenceId",
                                                                2,
                                                                "reason",
                                                                "软规则交换"))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String exchangeGroup = objectMapper.readTree(exchanged).get("groupId").asText();
        mockMvc.perform(
                        post("/api/schedule-versions/"
                                        + version
                                        + "/adjustments/commands/"
                                        + exchangeGroup
                                        + "/undo")
                                .with(csrf())
                                .header(
                                        "Idempotency-Key",
                                        "soft-exchange-undo-" + System.nanoTime()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDONE"));
    }

    @Test
    void optionsAndFilteredAssignmentsUseStableCodes() throws Exception {
        long version = seedCandidateVersion();
        mockMvc.perform(get("/api/schedule-versions/" + version + "/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeslots").isArray())
                .andExpect(jsonPath("$.rooms").isArray())
                .andExpect(jsonPath("$.studentGroups").isArray());
        mockMvc.perform(
                        get(
                                "/api/schedule-versions/"
                                        + version
                                        + "/filtered?view=TEACHER&resourceCode=T001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments").isArray())
                .andExpect(jsonPath("$.assignments[0].teacherCode").value("T001"));
    }

    @Test
    void adjustmentPreviewReportsConflictWithoutWritingCommand() throws Exception {
        long version = seedCandidateVersion();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM adjustment_command", Integer.class);
        var body =
                new ObjectMapper()
                        .writeValueAsString(new AdjustmentPreviewRequest(1L, "MON-2", "A102"));
        mockMvc.perform(
                        post("/api/schedule-versions/" + version + "/adjustments/preview")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.hardViolations").isArray());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM adjustment_command", Integer.class))
                .isEqualTo(before);
    }

    @Test
    void invalidPreviewReturnsStructuredViolationsWithoutWriting() throws Exception {
        long version = seedCandidateVersion();
        mockMvc.perform(
                        post("/api/schedule-versions/" + version + "/adjustments/preview")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        new ObjectMapper()
                                                .writeValueAsString(
                                                        new AdjustmentPreviewRequest(
                                                                1L, "NOT-A-SLOT", "NOT-A-ROOM"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(
                        jsonPath("$.hardViolations[*].code")
                                .value(
                                        org.hamcrest.Matchers.hasItems(
                                                "TIMESLOT_NOT_FOUND", "ROOM_NOT_FOUND")));
    }

    @Test
    void exchangeFailureLeavesAssignmentsAndCommandsUnchanged() throws Exception {
        long version = seedCandidateVersion();
        String beforeSlot =
                jdbc.queryForObject(
                        "SELECT timeslot_code FROM schedule_assignment WHERE schedule_version_id=? AND occurrence_id=1",
                        String.class,
                        version);
        int beforeCommands =
                jdbc.queryForObject("SELECT COUNT(*) FROM adjustment_command", Integer.class);
        jdbc.update(
                "INSERT INTO teacher_availability(teacher_id,term_id,period_code,available) VALUES((SELECT id FROM teacher WHERE code='T001'),(SELECT id FROM academic_term WHERE code='2026-FALL'),'MON-2',FALSE)");
        mockMvc.perform(
                        post("/api/schedule-versions/" + version + "/adjustments/exchange")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        new ObjectMapper()
                                                .writeValueAsString(
                                                        Map.of(
                                                                "occurrenceId",
                                                                1,
                                                                "swapOccurrenceId",
                                                                2,
                                                                "reason",
                                                                "规则回滚测试"))))
                .andExpect(status().isConflict());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT timeslot_code FROM schedule_assignment WHERE schedule_version_id=? AND occurrence_id=1",
                                String.class,
                                version))
                .isEqualTo(beforeSlot);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM adjustment_command", Integer.class))
                .isEqualTo(beforeCommands);
    }

    @Test
    void assignmentContractIncludesCodesSourceLockedAndDuration() throws Exception {
        long version = seedCandidateVersion();
        jdbc.update(
                "UPDATE schedule_assignment SET source='MANUAL', locked=TRUE, duration=2 WHERE schedule_version_id=? AND occurrence_id=1",
                version);

        mockMvc.perform(get("/api/schedule-versions/" + version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments[0].subjectCode").value("MATH"))
                .andExpect(jsonPath("$.assignments[0].teacherCode").value("T001"))
                .andExpect(jsonPath("$.assignments[0].studentGroupCode").value("G7-1"))
                .andExpect(jsonPath("$.assignments[0].roomCode").value("A101"))
                .andExpect(jsonPath("$.assignments[0].source").value("MANUAL"))
                .andExpect(jsonPath("$.assignments[0].locked").value(true))
                .andExpect(jsonPath("$.assignments[0].duration").value(2));
    }

    @Test
    void lockedAssignmentCannotBePreviewedOrConfirmed() throws Exception {
        long version = seedCandidateVersion();
        jdbc.update(
                "UPDATE schedule_assignment SET locked=TRUE WHERE schedule_version_id=? AND occurrence_id=1",
                version);
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM adjustment_command", Integer.class);
        var body =
                new ObjectMapper()
                        .writeValueAsString(new AdjustmentPreviewRequest(1L, "TUE-1", "A101"));

        mockMvc.perform(
                        post("/api/schedule-versions/" + version + "/adjustments/preview")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.lockedConflict").value(true));
        mockMvc.perform(
                        post("/api/schedule-versions/" + version + "/adjustments/1")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        new ObjectMapper()
                                                .writeValueAsString(
                                                        Map.of(
                                                                "timeslotCode",
                                                                "TUE-1",
                                                                "roomCode",
                                                                "A101",
                                                                "reason",
                                                                "锁定课程测试"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.validation.allowed").value(false));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM adjustment_command", Integer.class))
                .isEqualTo(before);
    }

    @Test
    void confirmationRevalidatesAndReturnsCommandId() throws Exception {
        long version = seedCandidateVersion();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM adjustment_command", Integer.class);
        mockMvc.perform(
                        post("/api/schedule-versions/" + version + "/adjustments/1")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        new ObjectMapper()
                                                .writeValueAsString(
                                                        Map.of(
                                                                "timeslotCode",
                                                                "TUE-1",
                                                                "roomCode",
                                                                "A101",
                                                                "reason",
                                                                "调整到周二第一节"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commandId").isNumber())
                .andExpect(jsonPath("$.validation.allowed").value(true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM adjustment_command", Integer.class))
                .isEqualTo(before + 1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT source FROM schedule_assignment WHERE schedule_version_id=? AND occurrence_id=1",
                                String.class,
                                version))
                .isEqualTo("MANUAL");
    }

    @Test
    void publishUpdatesCandidateOnceAndRejectsSecondPublish() throws Exception {
        long version = seedCandidateVersion();
        mockMvc.perform(post("/api/schedule-versions/" + version + "/publish").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        mockMvc.perform(post("/api/schedule-versions/" + version + "/publish").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("NOT_PUBLISHABLE"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM schedule_version WHERE id=?",
                                String.class,
                                version))
                .isEqualTo("PUBLISHED");
    }

    @Test
    void legalAdjustmentCanBePublishedAfterIndependentValidation() throws Exception {
        long version = seedCandidateVersion();
        mockMvc.perform(
                        post("/api/schedule-versions/" + version + "/adjustments/1")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        new ObjectMapper()
                                                .writeValueAsString(
                                                        Map.of(
                                                                "timeslotCode",
                                                                "TUE-1",
                                                                "roomCode",
                                                                "A101",
                                                                "reason",
                                                                "发布前调整"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/schedule-versions/" + version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.publishable").value(true));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT score FROM schedule_version WHERE id=?",
                                String.class,
                                version))
                .isNull();
        mockMvc.perform(post("/api/schedule-versions/" + version + "/publish").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void adjustedDraftWithHardConflictCannotBePublished() throws Exception {
        long version = seedCandidateVersion();
        mockMvc.perform(
                        post("/api/schedule-versions/" + version + "/adjustments/1")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        new ObjectMapper()
                                                .writeValueAsString(
                                                        Map.of(
                                                                "timeslotCode",
                                                                "TUE-1",
                                                                "roomCode",
                                                                "A101",
                                                                "reason",
                                                                "冲突回归"))))
                .andExpect(status().isOk());
        jdbc.update(
                "UPDATE schedule_assignment SET timeslot_code='TUE-1', timeslot_label='周二 第1节', weekday=2, period_no=1 WHERE schedule_version_id=? AND occurrence_id=2",
                version);

        mockMvc.perform(get("/api/schedule-versions/" + version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishable").value(false));
        mockMvc.perform(post("/api/schedule-versions/" + version + "/publish").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("NOT_PUBLISHABLE"));
    }

    @Test
    void versionListAndDiffUseStableOccurrenceKey() throws Exception {
        long version = seedCandidateVersion();
        long forked =
                jdbc.queryForObject(
                        "INSERT INTO schedule_version(scenario_id,owner_user_id,parent_version_id,status,score) SELECT scenario_id,(SELECT id FROM app_user WHERE username='test-planner'),id,'DRAFT',NULL FROM schedule_version WHERE id=? RETURNING id",
                        Long.class,
                        version);
        jdbc.update(
                "INSERT INTO schedule_assignment(schedule_version_id,occurrence_id,occurrence_key,subject_code,subject_name,teacher_code,teacher_name,student_group_code,student_group_name,timeslot_code,timeslot_label,weekday,period_no,room_code,room_name,source,locked,duration,student_count,required_features,room_features,room_capacity) SELECT ?,occurrence_id,occurrence_key,subject_code,subject_name,teacher_code,teacher_name,student_group_code,student_group_name,'TUE-1',timeslot_label,2,1,room_code,room_name,'MANUAL',locked,duration,student_count,required_features,room_features,room_capacity FROM schedule_assignment WHERE schedule_version_id=? AND occurrence_id=1",
                forked,
                version);
        mockMvc.perform(
                        get(
                                "/api/schedule-versions?termCode=2026-FALL&status=DRAFT&page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").isNumber());
        mockMvc.perform(
                        get(
                                "/api/schedule-versions/"
                                        + forked
                                        + "/diff?againstVersionId="
                                        + version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].occurrenceKey").value("1"));
    }

    @Test
    void exchangeCandidatesRejectLockedAssignment() throws Exception {
        long version = seedCandidateVersion();
        jdbc.update(
                "UPDATE schedule_assignment SET locked=TRUE WHERE schedule_version_id=? AND occurrence_id=1",
                version);
        mockMvc.perform(
                        post("/api/schedule-versions/"
                                        + version
                                        + "/adjustments/exchange-candidates")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        new ObjectMapper()
                                                .writeValueAsString(
                                                        Map.of(
                                                                "occurrenceId",
                                                                1,
                                                                "timeslotCode",
                                                                "MON-2",
                                                                "roomCode",
                                                                "A102"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedWithoutExchange").value(false));
        mockMvc.perform(
                        post("/api/schedule-versions/" + version + "/adjustments/exchange")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        new ObjectMapper()
                                                .writeValueAsString(
                                                        Map.of(
                                                                "occurrenceId",
                                                                1,
                                                                "swapOccurrenceId",
                                                                2,
                                                                "reason",
                                                                "锁定交换测试"))))
                .andExpect(status().isConflict());
    }

    private long seedCandidateVersion() {
        Long term =
                jdbc.queryForObject(
                        "SELECT id FROM academic_term WHERE code='2026-FALL'", Long.class);
        Long scenario =
                jdbc.queryForObject(
                        "INSERT INTO schedule_scenario(term_id,owner_user_id,name) VALUES(?,(SELECT id FROM app_user WHERE username='test-planner'), 'query-test') RETURNING id",
                        Long.class,
                        term);
        Long version =
                jdbc.queryForObject(
                        "INSERT INTO schedule_version(scenario_id,owner_user_id,status,score,legacy_identity_unverified) VALUES(?,(SELECT id FROM app_user WHERE username='test-planner'), 'CANDIDATE','0hard/0soft',FALSE) RETURNING id",
                        Long.class,
                        scenario);
        jdbc.update(
                "INSERT INTO schedule_assignment(schedule_version_id,occurrence_id,subject_code,subject_name,teacher_code,teacher_name,student_group_code,student_group_name,timeslot_code,timeslot_label,weekday,period_no,room_code,room_name,source,locked,duration) VALUES(?,1,'MATH','数学','T001','张老师','G7-1','七年级1班','MON-1','周一 第1节',1,1,'A101','教学楼 A101','SOLVER',false,1)",
                version);
        jdbc.update(
                "INSERT INTO schedule_assignment(schedule_version_id,occurrence_id,subject_code,subject_name,teacher_code,teacher_name,student_group_code,student_group_name,timeslot_code,timeslot_label,weekday,period_no,room_code,room_name,source,locked,duration) VALUES(?,2,'CHN','语文','T001','张老师','G7-2','七年级2班','MON-2','周一 第2节',1,2,'A102','教学楼 A102','SOLVER',false,1)",
                version);
        return version;
    }

    private void addSoftSpreadRule() {
        Long profile =
                jdbc.queryForObject(
                        "INSERT INTO schedule_rule_profile(term_id,code,name) VALUES((SELECT id FROM academic_term WHERE code='2026-FALL'), ?, '软规则测试') RETURNING id",
                        Long.class,
                        "soft-query-" + System.nanoTime());
        jdbc.update(
                "INSERT INTO schedule_rule_instance(profile_id,rule_code,scope_type,scope_code,int_value,severity,weight) VALUES(?, 'SUBJECT_MIN_SPREAD_DAYS', 'SUBJECT', 'MATH', 2, 'SOFT', 7)",
                profile);
    }
}
