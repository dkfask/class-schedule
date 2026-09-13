package com.classschedule.api;

import com.classschedule.rules.ScheduleRuleRepository;
import com.classschedule.schedule.ScheduleRepository;
import com.classschedule.solver.worker.SolveJobHandle;
import com.classschedule.solver.worker.SolveJobRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrialSolveService {
    private static final DateTimeFormatter DRAFT_SUFFIX = DateTimeFormatter.ofPattern("MMdd-HHmm");

    private final ScheduleRepository schedules;
    private final ScheduleRuleRepository rules;
    private final SolveJobRepository jobs;

    public TrialSolveService(
            ScheduleRepository schedules, ScheduleRuleRepository rules, SolveJobRepository jobs) {
        this.schedules = schedules;
        this.rules = rules;
        this.jobs = jobs;
    }

    @Transactional
    public Map<String, Object> submit(
            long versionId, TrialSolveRequest request, String submittedByUsername) {
        String idempotencyKey = request.idempotencyKey();
        jobs.lockIdempotencyKeyForOwner(idempotencyKey, submittedByUsername);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = jobs.findActiveByIdempotencyKey(idempotencyKey, submittedByUsername);
            if (existing.isPresent()) return response(existing.get(), request.rule().ruleCode());
        }

        String versionTerm = schedules.termCodeForVersion(versionId);
        if (!versionTerm.equals(request.rule().termCode().trim()))
            throw new IllegalArgumentException("试排规则学期必须与版本学期一致: " + versionTerm);

        // fork first so an invalid version or readiness failure cannot persist a rule change.
        long draftId =
                schedules.fork(
                        versionId,
                        "试排-"
                                + request.rule().ruleCode().trim().toUpperCase()
                                + "-"
                                + LocalDateTime.now().format(DRAFT_SUFFIX),
                        submittedByUsername);
        rules.upsert(request.rule());
        SolveJobHandle handle = jobs.enqueueForVersion(idempotencyKey, submittedByUsername, draftId);
        return response(handle, request.rule().ruleCode());
    }

    private Map<String, Object> response(SolveJobHandle handle, String requestedRuleCode) {
        return Map.of(
                "versionId", handle.versionId(),
                "jobId", handle.jobId(),
                "status", handle.status(),
                "ruleCode", requestedRuleCode.trim().toUpperCase());
    }
}
