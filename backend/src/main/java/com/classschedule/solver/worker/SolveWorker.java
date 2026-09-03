package com.classschedule.solver.worker;

import ai.timefold.solver.core.api.solver.SolverFactory;
import com.classschedule.solver.PlanningProblemRepository;
import com.classschedule.solver.SolverDataNotReadyException;
import com.classschedule.solver.Timetable;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("worker")
public class SolveWorker {
    private final SolveJobRepository jobs;
    private final PlanningProblemRepository problems;
    private final SolverFactory<Timetable> solverFactory;
    private final String workerId = "worker-" + UUID.randomUUID();
    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "solve-worker-heartbeat");
                        thread.setDaemon(true);
                        return thread;
                    });

    public SolveWorker(
            SolveJobRepository jobs,
            PlanningProblemRepository problems,
            SolverFactory<Timetable> solverFactory) {
        this.jobs = jobs;
        this.problems = problems;
        this.solverFactory = solverFactory;
    }

    @Scheduled(fixedDelayString = "${solver.worker.poll-ms:500}")
    public void poll() {
        jobs.recoverExpired();
        Long jobId = jobs.claim(workerId, Duration.ofSeconds(30));
        if (jobId == null) return;
        try {
            SolveJobDetails details = jobs.details(jobId);
            if (details.cancelRequested()) {
                jobs.finishCancelled(jobId, details.versionId());
                return;
            }
            ScheduledFuture<?> heartbeat =
                    heartbeatExecutor.scheduleAtFixedRate(
                            () -> {
                                try {
                                    jobs.heartbeat(jobId, workerId, 50);
                                } catch (RuntimeException ignored) {
                                    // The owner thread will decide the final job state.
                                }
                            },
                            10,
                            10,
                            TimeUnit.SECONDS);
            try {
                Timetable solved =
                        solverFactory
                                .buildSolver()
                                .solve(problems.loadForVersion(details.versionId()).toTimetable());
                if (!jobs.complete(
                        jobId, details.versionId(), String.valueOf(solved.getScore()), solved)) {
                    jobs.fail(jobId, details.versionId(), "STALE_JOB", "任务状态已改变，忽略迟到结果");
                }
            } finally {
                heartbeat.cancel(false);
            }
        } catch (Exception exception) {
            try {
                SolveJobDetails details = jobs.details(jobId);
                String code =
                        exception instanceof SolverDataNotReadyException
                                ? "SOLVER_DATA_NOT_READY"
                                : "SOLVER_ERROR";
                jobs.fail(jobId, details.versionId(), code, exception.getMessage());
            } catch (Exception ignored) {
                // 保留原始求解错误，下一次恢复扫描会处理租约过期任务。
            }
        }
    }

    @PreDestroy
    void shutdown() {
        heartbeatExecutor.shutdownNow();
    }
}
