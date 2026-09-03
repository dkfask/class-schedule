package com.classschedule.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
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
@WithMockUser(username = "immutability-planner", roles = "PLANNER")
class ScheduleVersionImmutabilityIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("class_schedule_immutability")
                    .withUsername("class_schedule")
                    .withPassword("class_schedule");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;

    @BeforeEach
    void seedOwner() {
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,display_name) VALUES('immutability-planner','{noop}test','测试排课员') ON CONFLICT (username) DO NOTHING");
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM adjustment_command_event");
        jdbc.update("DELETE FROM adjustment_command");
        jdbc.update("DELETE FROM adjustment_command_group");
        jdbc.update(
                "DELETE FROM schedule_assignment WHERE schedule_version_id IN (SELECT id FROM schedule_version WHERE status IN ('DRAFT','CANDIDATE','SOLVING','FAILED','CANCELLED'))");
        jdbc.update(
                "DELETE FROM schedule_version WHERE status IN ('DRAFT','CANDIDATE','SOLVING','FAILED','CANCELLED')");
        jdbc.update(
                "DELETE FROM schedule_scenario WHERE name LIKE 'immutability-%' AND id NOT IN (SELECT scenario_id FROM schedule_version)");
    }

    private long publishedWithAssignment(boolean legacyIdentityUnverified) {
        Long termId =
                jdbc.queryForObject(
                        "SELECT id FROM academic_term WHERE code='2026-FALL'", Long.class);
        Long scenarioId =
                jdbc.queryForObject(
                        "INSERT INTO schedule_scenario(term_id,owner_user_id,name) VALUES(?,(SELECT id FROM app_user WHERE username='immutability-planner'),?) RETURNING id",
                        Long.class,
                        termId,
                        "immutability-published-" + System.nanoTime());
        long versionId =
                jdbc.queryForObject(
                        "INSERT INTO schedule_version(scenario_id,owner_user_id,status,score,legacy_identity_unverified) VALUES(?,(SELECT id FROM app_user WHERE username='immutability-planner'), 'SOLVING', NULL, ?) RETURNING id",
                        Long.class,
                        scenarioId,
                        legacyIdentityUnverified);
        seedAssignment(versionId, 1);
        jdbc.update(
                "UPDATE schedule_version SET status = 'PUBLISHED', score = '0hard/0medium/0soft', published_at = CURRENT_TIMESTAMP WHERE id = ?",
                versionId);
        return versionId;
    }

    @Test
    void terminalAssignmentCannotBeInsertedUpdatedOrDeleted() {
        long published = publishedWithAssignment(false);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE schedule_assignment SET room_code='A102' WHERE schedule_version_id=?",
                                        published))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("VERSION_IMMUTABLE");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM schedule_assignment WHERE schedule_version_id=?",
                                        published))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("VERSION_IMMUTABLE");
        assertThatThrownBy(() -> seedAssignment(published, 2))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("VERSION_IMMUTABLE");
    }

    @Test
    void publishedVersionAllowsOnlyArchiveTransitionAndArchivedCannotChange() {
        long published = publishedWithAssignment(false);
        int archived =
                jdbc.update(
                        "UPDATE schedule_version SET status='ARCHIVED', archived_at=CURRENT_TIMESTAMP, revision=revision+1, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                        published);
        assertThat(archived).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE schedule_version SET score='-1hard/-1medium/-1soft' WHERE id=?",
                                        published))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("VERSION_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM schedule_version WHERE id=?", published))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("VERSION_IMMUTABLE");
    }

    @Test
    void terminalCommandHistoryCannotBeWritten() {
        long published = publishedWithAssignment(false);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO adjustment_command_group(id,schedule_version_id,command_type,base_revision,result_revision,actor,reason) VALUES(gen_random_uuid(),?,'ADJUST',0,1,'planner','blocked')",
                                        published))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("VERSION_IMMUTABLE");
    }

    @Test
    void legacyCandidatePublishReturnsStructuredConflict() throws Exception {
        long candidate = seedCandidate();
        mockMvc.perform(
                        post("/api/schedule-versions/" + candidate + "/publish")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_IDENTITY_UNVERIFIED"));
    }

    private long seedCandidate() {
        Long termId =
                jdbc.queryForObject(
                        "SELECT id FROM academic_term WHERE code='2026-FALL'", Long.class);
        Long scenarioId =
                jdbc.queryForObject(
                        "INSERT INTO schedule_scenario(term_id,owner_user_id,name) VALUES(?,(SELECT id FROM app_user WHERE username='immutability-planner'),?) RETURNING id",
                        Long.class,
                        termId,
                        "immutability-candidate-" + System.nanoTime());
        long versionId =
                jdbc.queryForObject(
                        "INSERT INTO schedule_version(scenario_id,owner_user_id,status,score,legacy_identity_unverified) VALUES(?,(SELECT id FROM app_user WHERE username='immutability-planner'), 'SOLVING', NULL, TRUE) RETURNING id",
                        Long.class,
                        scenarioId);
        seedAssignment(versionId, 1);
        jdbc.update(
                "UPDATE schedule_version SET status = 'CANDIDATE', score = '0hard/0medium/0soft' WHERE id = ?",
                versionId);
        return versionId;
    }

    private void seedAssignment(long versionId, long occurrenceId) {
        jdbc.update(
                "INSERT INTO schedule_assignment(schedule_version_id,occurrence_id,teaching_requirement_id,requirement_code,occurrence_key,subject_code,subject_name,teacher_code,teacher_name,student_group_code,student_group_name,timeslot_code,timeslot_label,weekday,period_no,room_code,room_name,source,locked,duration,student_count,required_features,room_features,room_capacity) VALUES(?,?,(SELECT id FROM teaching_requirement WHERE code='REQ-1'),'REQ-1',?,'MATH','数学','T001','张老师','G7-1','七年级1班','MON-1','周一 第1节',1,1,'A101','教学楼 A101','SOLVER',FALSE,1,0,'{}','{}',50)",
                versionId,
                occurrenceId,
                "REQ-1-" + occurrenceId);
    }
}
