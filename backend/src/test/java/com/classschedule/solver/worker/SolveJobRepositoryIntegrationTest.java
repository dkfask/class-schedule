package com.classschedule.solver.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.classschedule.solver.SampleTimetableFactory;
import com.classschedule.solver.Timetable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
class SolveJobRepositoryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("class_schedule_jobs")
                    .withUsername("class_schedule")
                    .withPassword("class_schedule");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired SolveJobRepository jobs;
    @Autowired JdbcTemplate jdbc;

    @Test
    void readinessFailureDoesNotCreateScenarioVersionOrJob() {
        String termCode = "READINESS-BLOCK-" + System.nanoTime();
        jdbc.update(
                "INSERT INTO academic_term(code,name,status) VALUES(?,?, 'DRAFT')",
                termCode,
                "readiness block");
        int scenariosBefore =
                jdbc.queryForObject("SELECT COUNT(*) FROM schedule_scenario", Integer.class);
        int versionsBefore =
                jdbc.queryForObject("SELECT COUNT(*) FROM schedule_version", Integer.class);
        int jobsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM solve_job", Integer.class);

        try {
            assertThatThrownBy(
                            () ->
                                    jobs.enqueue(
                                            "readiness-block-" + System.nanoTime(), null, termCode))
                    .isInstanceOf(com.classschedule.solver.SolveReadinessException.class)
                    .hasMessageContaining("尚未配置节次")
                    .hasMessageContaining("没有启用的教学需求");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schedule_scenario", Integer.class))
                    .isEqualTo(scenariosBefore);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schedule_version", Integer.class))
                    .isEqualTo(versionsBefore);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM solve_job", Integer.class))
                    .isEqualTo(jobsBefore);
        } finally {
            jdbc.update("DELETE FROM academic_term WHERE code = ?", termCode);
        }
    }

    @Test
    void idempotencyAndSkipLockedClaimAllowOnlyOneWorker() {
        SolveJobHandle first = jobs.enqueue("job-idempotency-1");
        SolveJobHandle duplicate = jobs.enqueue("job-idempotency-1");

        assertThat(duplicate.jobId()).isEqualTo(first.jobId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM solve_job WHERE idempotency_key = 'job-idempotency-1'",
                                Integer.class))
                .isEqualTo(1);

        Long claimed = jobs.claim("worker-a", java.time.Duration.ofSeconds(30));
        Long secondClaim = jobs.claim("worker-b", java.time.Duration.ofSeconds(30));

        assertThat(claimed).isEqualTo(first.jobId());
        assertThat(secondClaim).isNull();
        assertThat(jobs.details(first.jobId()).jobStatus()).isEqualTo("RUNNING");
        assertThat(jobs.details(first.jobId()).attempt()).isEqualTo(1);
    }

    @Test
    void concurrentSameOwnerSubmissionReturnsOneActiveHandle() throws Exception {
        String username = "concurrent-planner-" + System.nanoTime();
        String key = "concurrent-idempotency-" + System.nanoTime();
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,display_name) VALUES(?,?,?)",
                username,
                "{noop}test",
                username);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first =
                    executor.submit(
                            () -> {
                                start.await();
                                return jobs.enqueue(key, username, "2026-FALL");
                            });
            var second =
                    executor.submit(
                            () -> {
                                start.await();
                                return jobs.enqueue(key, username, "2026-FALL");
                            });
            start.countDown();

            SolveJobHandle firstHandle = first.get();
            SolveJobHandle secondHandle = second.get();
            assertThat(secondHandle).isEqualTo(firstHandle);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM solve_job WHERE submitted_by_user_id = (SELECT id FROM app_user WHERE username = ?) AND idempotency_key = ? AND status IN ('QUEUED','RUNNING')",
                                    Integer.class,
                                    username,
                                    key))
                    .isEqualTo(1);
            assertThat(jobs.requestCancel(firstHandle.jobId())).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void terminalJobCanReuseItsIdempotencyKeyForANewJob() {
        String key = "terminal-idempotency-" + System.nanoTime();
        SolveJobHandle first = jobs.enqueue(key);
        assertThat(jobs.claim("terminal-worker", java.time.Duration.ofSeconds(30)))
                .isEqualTo(first.jobId());
        assertThat(
                        jobs.complete(
                                first.jobId(),
                                first.versionId(),
                                "0hard/0soft",
                                SampleTimetableFactory.create()))
                .isTrue();

        SolveJobHandle second = jobs.enqueue(key);

        assertThat(second.jobId()).isNotEqualTo(first.jobId());
        assertThat(second.versionId()).isNotEqualTo(first.versionId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM solve_job WHERE idempotency_key = ?",
                                Integer.class,
                                key))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM solve_job WHERE idempotency_key = ? AND status IN ('QUEUED','RUNNING')",
                                Integer.class,
                                key))
                .isEqualTo(1);
        assertThat(jobs.requestCancel(second.jobId())).isTrue();
    }

    @Test
    void queuedCancellationImmediatelyFinishesJobAndVersion() {
        SolveJobHandle handle = jobs.enqueue("job-cancel-queued-1");

        assertThat(jobs.requestCancel(handle.jobId())).isTrue();
        assertThat(jobs.details(handle.jobId()).jobStatus()).isEqualTo("CANCELLED");
        assertThat(jobs.details(handle.jobId()).versionStatus()).isEqualTo("CANCELLED");
        assertThat(jobs.claim("worker-after-cancel", java.time.Duration.ofSeconds(30))).isNull();
    }

    @Test
    void completionPersistsV10AssignmentFields() {
        SolveJobHandle handle = jobs.enqueue("job-v10-complete-1");
        assertThat(jobs.claim("worker-v10", java.time.Duration.ofSeconds(30)))
                .isEqualTo(handle.jobId());
        Timetable solution = SampleTimetableFactory.create();
        solution.getOccurrences().get(0).setTimeslot(solution.getTimeslots().get(0));
        solution.getOccurrences().get(0).setRoom(solution.getRooms().get(0));
        solution.getOccurrences().get(0).setPinned(true);
        solution.getOccurrences().get(0).setDuration(2);

        assertThat(jobs.complete(handle.jobId(), handle.versionId(), "0hard/0soft", solution))
                .isTrue();
        var row =
                jdbc.queryForMap(
                        "SELECT source, locked, duration FROM schedule_assignment WHERE schedule_version_id = ? AND occurrence_id = ?",
                        handle.versionId(),
                        1L);
        assertThat(row.get("source")).isEqualTo("SOLVER");
        assertThat(row.get("locked")).isEqualTo(true);
        assertThat(row.get("duration")).isEqualTo(2);
    }

    @Test
    void cancellationIsPersistentAndCompletionCannotOverwriteIt() {
        SolveJobHandle handle = jobs.enqueue("job-cancel-1");
        Long claimed = jobs.claim("worker-cancel", java.time.Duration.ofSeconds(30));
        assertThat(claimed).isEqualTo(handle.jobId());

        assertThat(jobs.requestCancel(handle.jobId())).isTrue();
        assertThat(jobs.requestCancel(handle.jobId())).isFalse();
        assertThat(jobs.details(handle.jobId()).cancelRequested()).isTrue();

        Timetable solution = SampleTimetableFactory.create();
        assertThat(jobs.complete(handle.jobId(), handle.versionId(), "0hard/0soft", solution))
                .isFalse();
        assertThat(jobs.finishCancelled(handle.jobId(), handle.versionId())).isTrue();
        assertThat(jobs.details(handle.jobId()).jobStatus()).isEqualTo("CANCELLED");
        assertThat(jobs.details(handle.jobId()).versionStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void assignmentWriteFailureRollsBackTheWholeCompletionTransaction() {
        SolveJobHandle handle = jobs.enqueue("job-rollback-1");
        assertThat(jobs.claim("worker-rollback", java.time.Duration.ofSeconds(30)))
                .isEqualTo(handle.jobId());
        Timetable solution = SampleTimetableFactory.create();
        solution.getOccurrences().get(0).setTimeslot(solution.getTimeslots().get(0));
        solution.getOccurrences().get(0).setRoom(solution.getRooms().get(0));
        solution.getOccurrences().get(1).setTimeslot(solution.getTimeslots().get(1));
        solution.getOccurrences().get(1).setRoom(solution.getRooms().get(0));
        solution.getOccurrences().get(1).setSubjectName("X".repeat(129));

        assertThatThrownBy(
                        () ->
                                jobs.complete(
                                        handle.jobId(),
                                        handle.versionId(),
                                        "0hard/0soft",
                                        solution))
                .isInstanceOf(RuntimeException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM schedule_assignment WHERE schedule_version_id = ?",
                                Integer.class,
                                handle.versionId()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM solve_job WHERE id = ?",
                                String.class,
                                handle.jobId()))
                .isEqualTo("RUNNING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM schedule_version WHERE id = ?",
                                String.class,
                                handle.versionId()))
                .isEqualTo("SOLVING");
    }

    @Test
    void lateCompletionAgainstPublishedVersionRollsBackWholeTransaction() {
        SolveJobHandle handle = jobs.enqueue("job-late-publish-1");
        assertThat(jobs.claim("worker-late", java.time.Duration.ofSeconds(30)))
                .isEqualTo(handle.jobId());
        jdbc.update(
                "UPDATE schedule_version SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP, legacy_identity_unverified = FALSE WHERE id = ?",
                handle.versionId());
        Timetable solution = SampleTimetableFactory.create();
        solution.getOccurrences().get(0).setTimeslot(solution.getTimeslots().get(0));
        solution.getOccurrences().get(0).setRoom(solution.getRooms().get(0));

        assertThatThrownBy(
                        () ->
                                jobs.complete(
                                        handle.jobId(),
                                        handle.versionId(),
                                        "0hard/0soft",
                                        solution))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("VERSION_IMMUTABLE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM solve_job WHERE id = ?",
                                String.class,
                                handle.jobId()))
                .isEqualTo("RUNNING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM schedule_version WHERE id = ?",
                                String.class,
                                handle.versionId()))
                .isEqualTo("PUBLISHED");
    }

    @Test
    void expiredDeadlineFailsRunningJobAndWritesAudit() {
        SolveJobHandle handle = jobs.enqueue("job-deadline-1");
        assertThat(jobs.claim("worker-deadline", java.time.Duration.ofSeconds(30)))
                .isEqualTo(handle.jobId());
        jdbc.update(
                "UPDATE solve_job SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 minute', deadline_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = ?",
                handle.jobId());

        int failed = jobs.recoverExpired();

        assertThat(failed).isEqualTo(1);
        assertThat(jobs.details(handle.jobId()).jobStatus()).isEqualTo("FAILED");
        assertThat(jobs.details(handle.jobId()).errorCode()).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM schedule_version WHERE id = ?",
                                String.class,
                                handle.versionId()))
                .isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE action = 'SOLVE_DEADLINE' AND aggregate_id = ?",
                                Integer.class,
                                String.valueOf(handle.jobId())))
                .isEqualTo(1);
    }

    @Test
    void claimSkipsQueuedJobPastDeadline() {
        SolveJobHandle first = jobs.enqueue("job-deadline-skip-1");
        jdbc.update(
                "UPDATE solve_job SET deadline_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = ?",
                first.jobId());
        SolveJobHandle second = jobs.enqueue("job-deadline-skip-2");

        assertThat(jobs.claim("worker-deadline-skip", java.time.Duration.ofSeconds(30)))
                .isEqualTo(second.jobId());
        assertThat(jobs.details(first.jobId()).jobStatus()).isEqualTo("QUEUED");
    }
}
