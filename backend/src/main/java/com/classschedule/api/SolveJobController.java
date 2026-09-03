package com.classschedule.api;

import ai.timefold.solver.core.api.solver.SolverManager;
import com.classschedule.schedule.ScheduleRepository;
import com.classschedule.schedule.ScheduleScoreView;
import com.classschedule.schedule.ScheduleVersionView;
import com.classschedule.solver.PlanningProblemRepository;
import com.classschedule.solver.Timetable;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("legacy-solver")
@RequestMapping("/api/legacy-solve-jobs")
public class SolveJobController {
    private final SolverManager<Timetable, Long> solverManager;
    private final ScheduleRepository repository;
    private final PlanningProblemRepository planningProblemRepository;

    public SolveJobController(
            SolverManager<Timetable, Long> solverManager,
            ScheduleRepository repository,
            PlanningProblemRepository planningProblemRepository) {
        this.solverManager = solverManager;
        this.repository = repository;
        this.planningProblemRepository = planningProblemRepository;
    }

    @PostMapping
    public Map<String, Object> submit() {
        long versionId = repository.createScenarioAndVersion();
        long jobId = repository.createJob(versionId);
        solverManager.solve(
                jobId,
                planningProblemRepository.loadDefault().toTimetable(),
                solution -> {
                    try {
                        repository.markCompleted(
                                jobId, versionId, String.valueOf(solution.getScore()), solution);
                    } catch (RuntimeException exception) {
                        repository.markFailed(jobId, versionId, exception.getMessage());
                    }
                });
        return Map.of("jobId", jobId, "versionId", versionId, "status", "RUNNING");
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<?> status(@PathVariable Long jobId) {
        try {
            ScheduleVersionView version = repository.findVersionByJob(jobId);
            ScheduleScoreView score = ScheduleScoreView.parse(version.score());
            return ResponseEntity.ok(
                    Map.of(
                            "jobId", jobId,
                            "versionId", version.id(),
                            "status", version.status(),
                            "score", version.score() == null ? "等待结果" : version.score(),
                            "hardScore", score.hardScore(),
                            "mediumScore", score.mediumScore(),
                            "softScore", score.softScore(),
                            "scoreValid", score.valid(),
                            "publishable", version.publishable(),
                            "occurrences", version.assignments()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/{jobId}/cancel")
    public Map<String, Object> cancel(@PathVariable Long jobId) {
        solverManager.terminateEarly(jobId);
        repository.markCancelled(jobId);
        return Map.of("jobId", jobId, "status", "CANCELLED");
    }
}
