package com.classschedule.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ScheduleOwnershipMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("class_schedule_migration_test")
                    .withUsername("class_schedule")
                    .withPassword("class_schedule");

    @BeforeEach
    void migrateToV28() {
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        flyway().target(MigrationVersion.fromVersion("28")).load().migrate();
    }

    @Test
    void migratesPublishedVersionFromV28WithoutChangingItsSnapshot() {
        JdbcTemplate jdbc = jdbc();
        long scenarioId =
                jdbc.queryForObject(
                        "INSERT INTO schedule_scenario (term_id, name, status) SELECT id, 'V28 published fixture', 'PUBLISHED' FROM academic_term WHERE code = '2026-FALL' RETURNING id",
                        Long.class);
        long versionId =
                jdbc.queryForObject(
                        "INSERT INTO schedule_version (scenario_id, status, score, input_snapshot_hash, solver_version, random_seed, revision) VALUES (?, 'DRAFT', '0hard/2soft', 'input-before-ownership', 'legacy-test', 17, 3) RETURNING id",
                        Long.class,
                        scenarioId);
        jdbc.update(
                "INSERT INTO schedule_assignment (schedule_version_id, occurrence_id, subject_code, subject_name, teacher_code, teacher_name, student_group_code, student_group_name, timeslot_code, timeslot_label, weekday, period_no, room_code, room_name, source, locked, duration, occurrence_key, activity_group_code, teaching_requirement_id, requirement_code, activity_index, pinned_period_code, activity_type_snapshot, activity_member_index, student_count, required_features, room_features, room_capacity) VALUES (?, 9001, 'MATH', '数学', 'T001', '张老师', 'G7-1', '七年级1班', 'MON-1', '周一 第1节', 1, 1, 'A101', '教学楼 A101', 'SOLVER', TRUE, 1, 'legacy-occurrence-9001', NULL, NULL, NULL, 0, NULL, NULL, -1, 30, '{}', '{}', 50)",
                versionId);
        jdbc.update(
                "UPDATE schedule_version SET status = 'PUBLISHED', legacy_identity_unverified = FALSE, published_at = CURRENT_TIMESTAMP WHERE id = ?",
                versionId);

        var before =
                jdbc.queryForMap(
                        "SELECT status, score, input_snapshot_hash, revision, timeslot_code, room_code, locked FROM schedule_version v JOIN schedule_assignment a ON a.schedule_version_id = v.id WHERE v.id = ?",
                        versionId);

        flyway().load().migrate();

        var after =
                jdbc.queryForMap(
                        "SELECT v.status, v.score, v.input_snapshot_hash, v.revision, a.timeslot_code, a.room_code, a.locked, u.username AS owner FROM schedule_version v JOIN schedule_assignment a ON a.schedule_version_id = v.id JOIN app_user u ON u.id = v.owner_user_id WHERE v.id = ?",
                        versionId);
        assertThat(after)
                .containsEntry("status", before.get("status"))
                .containsEntry("score", before.get("score"))
                .containsEntry("input_snapshot_hash", before.get("input_snapshot_hash"))
                .containsEntry("revision", before.get("revision"))
                .containsEntry("timeslot_code", before.get("timeslot_code"))
                .containsEntry("room_code", before.get("room_code"))
                .containsEntry("locked", before.get("locked"))
                .containsEntry("owner", "system");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT tgenabled FROM pg_trigger WHERE tgname = 'schedule_version_state_protection'",
                                String.class))
                .isEqualTo("O");
    }

    @Test
    void refusesV30WhenLegacyRequirementBelongsToMultipleActivityGroups() {
        JdbcTemplate jdbc = jdbc();
        long requirementId =
                jdbc.queryForObject(
                        "SELECT id FROM teaching_requirement WHERE code = 'REQ-MATH' OR (student_group_id = (SELECT id FROM student_group WHERE code = 'G7-1') AND subject_id = (SELECT id FROM subject WHERE code = 'MATH')) ORDER BY id LIMIT 1",
                        Long.class);
        long termId =
                jdbc.queryForObject(
                        "SELECT term_id FROM teaching_requirement WHERE id = ?",
                        Long.class,
                        requirementId);
        long firstGroup =
                jdbc.queryForObject(
                        "INSERT INTO activity_group (code, name, activity_type, term_id) VALUES ('LEGACY-A', '旧活动组 A', 'JOINED', ?) RETURNING id",
                        Long.class,
                        termId);
        long secondGroup =
                jdbc.queryForObject(
                        "INSERT INTO activity_group (code, name, activity_type, term_id) VALUES ('LEGACY-B', '旧活动组 B', 'JOINED', ?) RETURNING id",
                        Long.class,
                        termId);
        jdbc.update(
                "INSERT INTO activity_group_member (activity_group_id, teaching_requirement_id, member_index) VALUES (?, ?, 0), (?, ?, 0)",
                firstGroup,
                requirementId,
                secondGroup,
                requirementId);

        assertThatThrownBy(() -> flyway().load().migrate())
                .hasMessageContaining("V30__activity_group_term_identity.sql")
                .hasStackTraceContaining(
                        "duplicate teaching_requirement_id values: " + requirementId);
    }

    private FluentConfiguration flyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
    }

    private JdbcTemplate jdbc() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        return new JdbcTemplate(dataSource);
    }
}
