package com.classschedule.api;

import java.util.List;

public record ExchangeCandidatesResponse(
        boolean allowedWithoutExchange,
        List<Candidate> candidates,
        List<AdjustmentPreviewResponse.Violation> hardViolations) {
    public record Candidate(
            long occurrenceId,
            String occurrenceKey,
            String subjectName,
            String studentGroupCode,
            String teacherCode,
            String roomCode,
            String timeslotCode) {}
}
