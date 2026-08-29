package com.classschedule.api;

import com.classschedule.solver.SolveReadinessException;
import com.classschedule.solver.worker.SolveJobDetails;
import com.classschedule.solver.worker.SolveJobHandle;
import com.classschedule.solver.worker.SolveJobRepository;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/solve-jobs")
public class PersistentSolveJobController {
    private final SolveJobRepository jobs;

    public PersistentSolveJobController(SolveJobRepository jobs) { this.jobs = jobs; }

    @PostMapping
    public ResponseEntity<?> enqueue(@RequestBody(required = false) SubmitSolveJobRequest request, Authentication authentication) {
        String key = request == null ? null : request.idempotencyKey();
        String termCode = request == null || request.termCode() == null || request.termCode().isBlank() ? "2026-FALL" : request.termCode().trim();
        try {
            SolveJobHandle handle = jobs.enqueue(key, authentication.getName(), termCode);
            return ResponseEntity.ok(Map.of("jobId", handle.jobId(), "versionId", handle.versionId(), "status", handle.status()));
        } catch (SolveReadinessException exception) {
            return SolveReadinessController.blocked(exception);
        }
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<?> details(@PathVariable long jobId, Authentication authentication) {
        try {
            SolveJobDetails details = jobs.details(jobId, authentication.getName());
            return ResponseEntity.ok(details.asMap());
        } catch (EmptyResultDataAccessException | IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "JOB_NOT_FOUND", "message", "求解任务不存在"));
        }
    }

    @PostMapping("/{jobId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable long jobId, Authentication authentication) {
        try {
            boolean requested = jobs.requestCancel(jobId, authentication.getName());
            SolveJobDetails after = jobs.details(jobId, authentication.getName());
            return ResponseEntity.ok(Map.of("jobId", jobId, "cancelRequested", requested, "jobStatus", after.jobStatus(), "versionStatus", after.versionStatus()));
        } catch (EmptyResultDataAccessException | IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "JOB_NOT_FOUND", "message", "求解任务不存在"));
        }
    }

    public record SubmitSolveJobRequest(@Size(max = 128) String idempotencyKey, @jakarta.validation.constraints.Size(max = 64) String termCode) {}
}
