package com.classschedule.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
@WithMockUser(username = "rule-planner", roles = "PLANNER")
class ScheduleRuleValidatorIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("class_schedule_rule_validator")
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
    @Autowired ScheduleRuleValidator validator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void seedOwner() {
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,display_name) VALUES('rule-planner','{noop}test','测试排课员') ON CONFLICT (username) DO NOTHING");
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM schedule_rule_instance");
        jdbc.update("DELETE FROM schedule_rule_profile");
        jdbc.update("DELETE FROM activity_group_member");
        jdbc.update("DELETE FROM activity_group");
    }

    @Test
    void catalogAndTermRuleUseStableContract() throws Exception {
        mockMvc.perform(get("/api/schedule-rules/catalog"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[?(@.ruleCode=='SUBJECT_DAILY_MAX')].valueType")
                                .value("INTEGER"))
                .andExpect(
                        jsonPath("$[?(@.ruleCode=='TEACHER_PREFERRED_PERIOD')].valueType")
                                .value("TEXT"));

        mockMvc.perform(
                        post("/api/schedule-rules")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "termCode",
                                                        "2026-FALL",
                                                        "ruleCode",
                                                        "TEACHER_DAILY_MAX",
                                                        "scopeType",
                                                        "TERM",
                                                        "intValue",
                                                        4,
                                                        "severity",
                                                        "MEDIUM",
                                                        "weight",
                                                        3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPDATED"));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT scope_code FROM schedule_rule_instance WHERE rule_code='TEACHER_DAILY_MAX'",
                                String.class))
                .isEqualTo("__TERM__");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT severity FROM schedule_rule_instance WHERE rule_code='TEACHER_DAILY_MAX'",
                                String.class))
                .isEqualTo("MEDIUM");
        mockMvc.perform(get("/api/schedule-rules?termCode=2026-FALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].scope_code").doesNotExist());
    }

    @Test
    void rejectsRuleScopeMismatch() throws Exception {
        mockMvc.perform(
                        post("/api/schedule-rules")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "termCode",
                                                        "2026-FALL",
                                                        "ruleCode",
                                                        "SUBJECT_DAILY_MAX",
                                                        "scopeType",
                                                        "TEACHER",
                                                        "scopeCode",
                                                        "T001",
                                                        "intValue",
                                                        4))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void subjectDailyMaxIsScopedByStudentGroup() {
        long profileId = addRule("SUBJECT_DAILY_MAX", 1, "SOFT", 4);
        long versionId = version();

        List<ScheduleRuleValidator.Violation> violations =
                validator.validate(
                        versionId,
                        new ScheduleVersionView(
                                versionId,
                                "CANDIDATE",
                                "0hard/0soft",
                                false,
                                List.of(
                                        assignment(
                                                1, "REQ-1", "MATH", "T001", "G7-1", "MON-1", 1, 1,
                                                101),
                                        assignment(
                                                2, "REQ-2", "MATH", "T002", "G7-2", "MON-2", 1, 2,
                                                102),
                                        assignment(
                                                3, "REQ-3", "ENG", "T003", "G7-2", "TUE-1", 2, 1,
                                                101)),
                                0,
                                null,
                                null,
                                false,
                                null,
                                null,
                                "2026-FALL",
                                null,
                                null,
                                null));

        assertThat(violations).noneMatch(item -> item.code().equals("SUBJECT_DAILY_MAX"));
        jdbc.update("DELETE FROM schedule_rule_instance WHERE profile_id = ?", profileId);
        jdbc.update("DELETE FROM schedule_rule_profile WHERE id = ?", profileId);
    }

    @Test
    void subjectDailyMaxReportsTheNumberOfExcessPeriodsAsPenalty() {
        long profileId = addRule("SUBJECT_DAILY_MAX", 1, "SOFT", 5);
        long versionId = version();

        List<ScheduleRuleValidator.Violation> violations =
                validator.validate(
                        versionId,
                        new ScheduleVersionView(
                                versionId,
                                "CANDIDATE",
                                "0hard/0soft",
                                false,
                                List.of(
                                        assignment(
                                                1, "REQ-1", "MATH", "T001", "G7-1", "MON-1", 1, 1,
                                                101),
                                        assignment(
                                                2, "REQ-2", "MATH", "T002", "G7-1", "MON-2", 1, 2,
                                                102),
                                        assignment(
                                                3, "REQ-3", "MATH", "T003", "G7-1", "MON-3", 1, 3,
                                                103)),
                                0,
                                null,
                                null,
                                false,
                                null,
                                null,
                                "2026-FALL",
                                null,
                                null,
                                null));

        assertThat(violations)
                .filteredOn(item -> item.code().equals("SUBJECT_DAILY_MAX"))
                .singleElement()
                .extracting(ScheduleRuleValidator.Violation::weight)
                .isEqualTo(10);
        jdbc.update("DELETE FROM schedule_rule_instance WHERE profile_id = ?", profileId);
        jdbc.update("DELETE FROM schedule_rule_profile WHERE id = ?", profileId);
    }

    @Test
    void subjectMinSpreadIsEvaluatedPerStudentGroup() {
        long profileId = addRule("SUBJECT_MIN_SPREAD_DAYS", 2, "SOFT", 3);
        jdbc.update(
                "UPDATE schedule_rule_instance SET scope_type='SUBJECT', scope_code='MATH' WHERE profile_id = ?",
                profileId);
        long versionId = version();

        List<ScheduleRuleValidator.Violation> violations =
                validator.validate(
                        versionId,
                        new ScheduleVersionView(
                                versionId,
                                "CANDIDATE",
                                "0hard/0soft",
                                false,
                                List.of(
                                        assignment(
                                                1, "REQ-1", "MATH", "T001", "G7-1", "MON-1", 1, 1,
                                                101),
                                        assignment(
                                                2, "REQ-2", "MATH", "T002", "G7-2", "TUE-1", 2, 1,
                                                102),
                                        assignment(
                                                3, "REQ-3", "ENG", "T003", "G7-2", "WED-1", 3, 1,
                                                101)),
                                0,
                                null,
                                null,
                                false,
                                null,
                                null,
                                "2026-FALL",
                                null,
                                null,
                                null));

        assertThat(violations)
                .filteredOn(item -> item.code().equals("SUBJECT_MIN_SPREAD_DAYS"))
                .hasSize(2);
        jdbc.update("DELETE FROM schedule_rule_instance WHERE profile_id = ?", profileId);
        jdbc.update("DELETE FROM schedule_rule_profile WHERE id = ?", profileId);
    }

    @Test
    void subjectMinSpreadReportsTheNumberOfMissingDaysAsPenalty() {
        long profileId = addRule("SUBJECT_MIN_SPREAD_DAYS", 4, "SOFT", 3);
        jdbc.update(
                "UPDATE schedule_rule_instance SET scope_type='SUBJECT', scope_code='MATH' WHERE profile_id = ?",
                profileId);
        long versionId = version();

        List<ScheduleRuleValidator.Violation> violations =
                validator.validate(
                        versionId,
                        new ScheduleVersionView(
                                versionId,
                                "CANDIDATE",
                                "0hard/0soft",
                                false,
                                List.of(
                                        assignment(
                                                1, "REQ-1", "MATH", "T001", "G7-1", "MON-1", 1, 1,
                                                101),
                                        assignment(
                                                2, "REQ-2", "MATH", "T002", "G7-1", "MON-2", 1, 2,
                                                102)),
                                0,
                                null,
                                null,
                                false,
                                null,
                                null,
                                "2026-FALL",
                                null,
                                null,
                                null));

        assertThat(violations)
                .filteredOn(item -> item.code().equals("SUBJECT_MIN_SPREAD_DAYS"))
                .singleElement()
                .extracting(ScheduleRuleValidator.Violation::weight)
                .isEqualTo(9);
        jdbc.update("DELETE FROM schedule_rule_instance WHERE profile_id = ?", profileId);
        jdbc.update("DELETE FROM schedule_rule_profile WHERE id = ?", profileId);
    }

    @Test
    void reportsWholeActivityGroupMissing() {
        long groupId =
                jdbc.queryForObject(
                        "INSERT INTO activity_group(code,name,activity_type,term_id) VALUES('ACT-MISSING','缺失活动','JOINED',(SELECT id FROM academic_term WHERE code='2026-FALL')) RETURNING id",
                        Long.class);
        addMember(groupId, "REQ-1", 0);
        addMember(groupId, "REQ-2", 1);
        long versionId = version();

        List<ScheduleRuleValidator.Violation> violations =
                validator.validate(
                        versionId,
                        new ScheduleVersionView(
                                versionId,
                                "CANDIDATE",
                                "0hard/0soft",
                                false,
                                assignmentsWithoutActivityGroup(),
                                0,
                                null,
                                null,
                                false,
                                null,
                                null,
                                "2026-FALL",
                                null,
                                null,
                                null));

        assertThat(violations)
                .anyMatch(item -> item.code().equals("ACTIVITY_GROUP_MISSING") && item.blocking());
    }

    @Test
    void reportsMissingMemberInsideExistingActivityBlock() {
        long groupId =
                jdbc.queryForObject(
                        "INSERT INTO activity_group(code,name,activity_type,term_id) VALUES('ACT-PARTIAL','部分活动','JOINED',(SELECT id FROM academic_term WHERE code='2026-FALL')) RETURNING id",
                        Long.class);
        addMember(groupId, "REQ-1", 0);
        addMember(groupId, "REQ-2", 1);
        long versionId = version();

        List<ScheduleAssignmentView> assignments = assignmentsWithoutActivityGroup();
        ScheduleAssignmentView first = assignments.get(0);
        assignments =
                List.of(
                        new ScheduleAssignmentView(
                                first.occurrenceId(),
                                first.subjectCode(),
                                first.subjectName(),
                                first.teacherCode(),
                                first.teacherName(),
                                first.studentGroupCode(),
                                first.studentGroupName(),
                                first.timeslotCode(),
                                first.timeslotLabel(),
                                first.weekday(),
                                first.period(),
                                first.roomCode(),
                                first.roomName(),
                                first.source(),
                                first.locked(),
                                first.duration(),
                                first.occurrenceKey(),
                                "ACT-PARTIAL",
                                "JOINED",
                                first.studentCount(),
                                Set.of(),
                                Set.of(),
                                50,
                                first.teachingRequirementId(),
                                first.requirementCode(),
                                0,
                                0,
                                null,
                                "JOINED"),
                        assignments.get(1),
                        assignments.get(2));

        List<ScheduleRuleValidator.Violation> violations =
                validator.validate(
                        versionId,
                        new ScheduleVersionView(
                                versionId,
                                "CANDIDATE",
                                "0hard/0soft",
                                false,
                                assignments,
                                0,
                                null,
                                null,
                                false,
                                null,
                                null,
                                "2026-FALL",
                                null,
                                null,
                                null));

        assertThat(violations)
                .anyMatch(
                        item ->
                                item.code().equals("ACTIVITY_MEMBER_MISSING")
                                        && item.occurrenceKey().contains("REQ-2"));
    }

    private void addMember(long groupId, String requirementCode, int memberIndex) {
        jdbc.update(
                "INSERT INTO activity_group_member(activity_group_id,teaching_requirement_id,member_index) VALUES(?,(SELECT id FROM teaching_requirement WHERE code=?),?)",
                groupId,
                requirementCode,
                memberIndex);
    }

    private long addRule(String ruleCode, int intValue, String severity, int weight) {
        long profileId =
                jdbc.queryForObject(
                        "INSERT INTO schedule_rule_profile(term_id,code,name) VALUES((SELECT id FROM academic_term WHERE code='2026-FALL'), ?, ?) RETURNING id",
                        Long.class,
                        "RULE-" + ruleCode + "-" + System.nanoTime(),
                        "规则测试");
        jdbc.update(
                "INSERT INTO schedule_rule_instance(profile_id,rule_code,scope_type,scope_code,int_value,severity,weight) VALUES(?,?, 'TERM','__TERM__',?,?,?)",
                profileId,
                ruleCode,
                intValue,
                severity,
                weight);
        return profileId;
    }

    private long version() {
        Long termId =
                jdbc.queryForObject(
                        "SELECT id FROM academic_term WHERE code='2026-FALL'", Long.class);
        Long scenarioId =
                jdbc.queryForObject(
                        "INSERT INTO schedule_scenario(term_id,owner_user_id,name) VALUES(?,(SELECT id FROM app_user WHERE username='rule-planner'), 'validator-test') RETURNING id",
                        Long.class,
                        termId);
        return jdbc.queryForObject(
                "INSERT INTO schedule_version(scenario_id,owner_user_id,status,score,snapshot_term_code) VALUES(?,(SELECT id FROM app_user WHERE username='rule-planner'), 'CANDIDATE','0hard/0soft','2026-FALL') RETURNING id",
                Long.class,
                scenarioId);
    }

    private List<ScheduleAssignmentView> assignmentsWithoutActivityGroup() {
        return List.of(
                assignment(1, "REQ-1", "MATH", "T001", "G7-1", "MON-1", 1, 1, 101),
                assignment(2, "REQ-2", "CHN", "T002", "G7-1", "MON-2", 1, 2, 102),
                assignment(3, "REQ-3", "ENG", "T003", "G7-2", "TUE-1", 2, 1, 101));
    }

    private ScheduleAssignmentView assignment(
            long id,
            String requirementCode,
            String subjectCode,
            String teacherCode,
            String groupCode,
            String timeslotCode,
            int weekday,
            int period,
            int roomNumber) {
        return new ScheduleAssignmentView(
                id,
                subjectCode,
                subjectCode,
                teacherCode,
                teacherCode,
                groupCode,
                groupCode,
                timeslotCode,
                timeslotCode,
                weekday,
                period,
                "A" + roomNumber,
                "A" + roomNumber,
                "SOLVER",
                false,
                1,
                requirementCode + "-0",
                null,
                null,
                0,
                Set.of(),
                Set.of(),
                50,
                id,
                requirementCode,
                0,
                -1,
                null,
                null);
    }
}
