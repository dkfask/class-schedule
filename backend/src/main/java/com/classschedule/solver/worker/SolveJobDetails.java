package com.classschedule.solver.worker;

import com.classschedule.schedule.ScheduleScoreView;
import java.util.LinkedHashMap;
import java.util.Map;

public record SolveJobDetails(
        long jobId,
        long versionId,
        String jobStatus,
        String versionStatus,
        int progress,
        String score,
        String errorCode,
        String errorMessage,
        int attempt,
        String startedAt,
        String heartbeatAt,
        String finishedAt,
        boolean cancelRequested,
        String deadlineAt) {
    public ScheduleScoreView scoreView() {
        return ScheduleScoreView.parse(score);
    }

    public Map<String, Object> asMap() {
        ScheduleScoreView scoreView = scoreView();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("jobId", jobId);
        values.put("versionId", versionId);
        values.put("jobStatus", jobStatus);
        values.put("versionStatus", versionStatus);
        values.put("progress", progress);
        values.put("score", score == null ? "等待结果" : score);
        values.put("hardScore", scoreView.hardScore());
        values.put("mediumScore", scoreView.mediumScore());
        values.put("softScore", scoreView.softScore());
        values.put("scoreValid", scoreView.valid());
        values.put("errorCode", errorCode == null ? "" : errorCode);
        values.put("errorMessage", errorMessage == null ? "" : errorMessage);
        values.put("attempt", attempt);
        values.put("startedAt", startedAt == null ? "" : startedAt);
        values.put("heartbeatAt", heartbeatAt == null ? "" : heartbeatAt);
        values.put("finishedAt", finishedAt == null ? "" : finishedAt);
        values.put("cancelRequested", cancelRequested);
        values.put("deadlineAt", deadlineAt == null ? "" : deadlineAt);
        return values;
    }
}
