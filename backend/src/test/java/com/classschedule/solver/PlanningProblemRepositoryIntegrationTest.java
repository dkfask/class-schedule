package com.classschedule.solver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class PlanningProblemRepositoryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("class_schedule_planning")
                    .withUsername("class_schedule")
                    .withPassword("class_schedule");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired PlanningProblemRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void loadsTypedRulesIntoPlanningProblemFacts() {
        jdbc.update(
                "INSERT INTO schedule_rule_profile(term_id,code,name) VALUES((SELECT id FROM academic_term WHERE code='2026-FALL'),'PLANNING-TEST','规划测试')");
        long profileId =
                jdbc.queryForObject(
                        "SELECT id FROM schedule_rule_profile WHERE code='PLANNING-TEST'",
                        Long.class);
        try {
            jdbc.update(
                    "INSERT INTO schedule_rule_instance(profile_id,rule_code,scope_type,scope_code,int_value,severity,weight) VALUES(?,'TEACHER_DAILY_MAX','TERM','__TERM__',4,'MEDIUM',3)",
                    profileId);
            PlanningProblem problem = repository.loadDefault();
            assertThat(problem.rules())
                    .anyMatch(
                            rule ->
                                    "TEACHER_DAILY_MAX".equals(rule.ruleCode())
                                            && rule.limit() == 4
                                            && rule.weight() == 3
                                            && rule.isMedium());
        } finally {
            jdbc.update("DELETE FROM schedule_rule_instance WHERE profile_id = ?", profileId);
            jdbc.update("DELETE FROM schedule_rule_profile WHERE id = ?", profileId);
        }
    }

    @Test
    void loadsDurationAndPinnedPeriodIntoOccurrences() {
        Long requirementId =
                jdbc.queryForObject(
                        "INSERT INTO teaching_requirement (code, term_id, student_group_id, subject_id, teacher_id, weekly_periods, duration_periods, pinned_period_code) VALUES ('REQ-PLANNING', (SELECT id FROM academic_term WHERE code='2026-FALL'), (SELECT id FROM student_group WHERE code='G7-1'), (SELECT id FROM subject WHERE code='MATH'), (SELECT id FROM teacher WHERE code='T001'), 1, 2, 'TUE-1') RETURNING id",
                        Long.class);

        try {
            PlanningProblem problem = repository.loadDefault();
            LessonOccurrence occurrence =
                    problem.occurrences().stream()
                            .filter(item -> item.getId().equals(requirementId * 100))
                            .findFirst()
                            .orElseThrow();
            assertThat(occurrence.getDuration()).isEqualTo(2);
            assertThat(occurrence.isPinned()).isTrue();
            assertThat(occurrence.getTimeslot().getId()).isEqualTo("TUE-1");
            assertThat(occurrence.getRoom()).isNull();
        } finally {
            jdbc.update("DELETE FROM teaching_requirement WHERE id = ?", requirementId);
        }
    }

    @Test
    void loadsRoomAndRequirementFactsForTheWholeProblem() {
        jdbc.update(
                "INSERT INTO room_feature_catalog(code,name) VALUES('BATCH_FEATURE','批量特征') ON CONFLICT (code) DO NOTHING");
        long roomId =
                jdbc.queryForObject(
                        "INSERT INTO room(code,name,capacity,room_type) VALUES('BATCH-ROOM','批量教室',60,'普通教室') RETURNING id",
                        Long.class);
        Long requirementId =
                jdbc.queryForObject(
                        "INSERT INTO teaching_requirement (code, term_id, student_group_id, subject_id, teacher_id, weekly_periods, duration_periods) VALUES ('REQ-BATCH-FACTS', (SELECT id FROM academic_term WHERE code='2026-FALL'), (SELECT id FROM student_group WHERE code='G7-1'), (SELECT id FROM subject WHERE code='MATH'), (SELECT id FROM teacher WHERE code='T001'), 1, 1) RETURNING id",
                        Long.class);
        jdbc.update(
                "INSERT INTO room_feature(room_id,feature_code) VALUES(?, 'BATCH_FEATURE')",
                roomId);
        jdbc.update(
                "INSERT INTO room_availability(room_id,term_id,period_code,available) VALUES(?,(SELECT id FROM academic_term WHERE code='2026-FALL'),'MON-1',FALSE)",
                roomId);
        jdbc.update(
                "INSERT INTO teaching_requirement_feature(teaching_requirement_id,feature_code) VALUES(?, 'BATCH_FEATURE')",
                requirementId);

        try {
            PlanningProblem problem = repository.loadDefault();
            Room room =
                    problem.rooms().stream()
                            .filter(item -> "BATCH-ROOM".equals(item.getId()))
                            .findFirst()
                            .orElseThrow();
            LessonOccurrence occurrence =
                    problem.occurrences().stream()
                            .filter(item -> item.getId().equals(requirementId * 100))
                            .findFirst()
                            .orElseThrow();

            assertThat(room.getFeatures()).containsExactly("BATCH_FEATURE");
            assertThat(room.getUnavailablePeriodCodes()).containsExactly("MON-1");
            assertThat(occurrence.getRequiredFeatures()).containsExactly("BATCH_FEATURE");
        } finally {
            jdbc.update("DELETE FROM teaching_requirement WHERE id = ?", requirementId);
            jdbc.update("DELETE FROM room WHERE id = ?", roomId);
        }
    }
}
