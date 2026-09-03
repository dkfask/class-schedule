package com.classschedule.solver.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
class SolveJobRepositoryRecoveryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("class_schedule_recovery")
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
    void heartbeatExtendsLeaseForActiveWorker() {
        SolveJobHandle handle = jobs.enqueue("job-heartbeat");
        assertThat(jobs.claim("worker-heartbeat", Duration.ofSeconds(30)))
                .isEqualTo(handle.jobId());
        jdbc.update(
                "UPDATE solve_job SET lease_until = CURRENT_TIMESTAMP + INTERVAL '1 second' WHERE id = ?",
                handle.jobId());
        jobs.heartbeat(handle.jobId(), "worker-heartbeat", 50);

        Boolean leaseExtended =
                jdbc.queryForObject(
                        "SELECT lease_until > CURRENT_TIMESTAMP + INTERVAL '20 seconds' FROM solve_job WHERE id = ?",
                        Boolean.class,
                        handle.jobId());
        assertThat(leaseExtended).isTrue();
        assertThat(jobs.details(handle.jobId()).progress()).isEqualTo(50);
    }

    @Test
    void expiredLeaseCanBeReclaimedByAnotherWorker() {
        SolveJobHandle handle = jobs.enqueue("job-lease-recovery");
        assertThat(jobs.claim("worker-a", Duration.ofSeconds(30))).isEqualTo(handle.jobId());
        jdbc.update(
                "UPDATE solve_job SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?",
                handle.jobId());

        assertThat(jobs.recoverExpired()).isEqualTo(1);
        assertThat(jobs.claim("worker-b", Duration.ofSeconds(30))).isEqualTo(handle.jobId());
        assertThat(jobs.details(handle.jobId()).attempt()).isEqualTo(2);
    }

    @Test
    void failedJobsRetryWithBackoffThenBecomeFailedAtMaxAttempts() {
        SolveJobHandle handle = jobs.enqueue("job-retry-policy");
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(jobs.claim("worker-retry-" + attempt, Duration.ofSeconds(30)))
                    .isEqualTo(handle.jobId());
            jobs.fail(handle.jobId(), handle.versionId(), "TEST_FAILURE", "controlled failure");
            if (attempt < 3) {
                jdbc.update(
                        "UPDATE solve_job SET next_attempt_at = CURRENT_TIMESTAMP WHERE id = ?",
                        handle.jobId());
                assertThat(jobs.details(handle.jobId()).jobStatus()).isEqualTo("QUEUED");
            }
        }
        assertThat(jobs.details(handle.jobId()).jobStatus()).isEqualTo("FAILED");
        assertThat(jobs.details(handle.jobId()).versionStatus()).isEqualTo("FAILED");
    }
}
