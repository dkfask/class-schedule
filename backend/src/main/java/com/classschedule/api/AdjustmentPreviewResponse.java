package com.classschedule.api;

import java.util.List;

public record AdjustmentPreviewResponse(
        boolean allowed,
        List<Violation> hardViolations,
        List<Long> affectedAssignmentIds,
        boolean lockedConflict,
        long versionId,
        AdjustmentLocation current,
        AdjustmentLocation target) {
    public record Violation(String code, String message, String resourceCode) {}
    public record AdjustmentLocation(String timeslotCode, String roomCode) {}
}
