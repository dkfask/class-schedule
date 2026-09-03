package com.classschedule.schedule;

import com.classschedule.solver.LessonOccurrence;
import com.classschedule.solver.Timetable;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;

public record ScheduleVersionView(
        long id,
        String status,
        String score,
        boolean publishable,
        List<ScheduleAssignmentView> assignments,
        long revision,
        OffsetDateTime updatedAt,
        OffsetDateTime archivedAt,
        boolean editLocked,
        String editLockOwner,
        String editLockReason,
        String termCode,
        String inputSnapshotHash,
        String ruleSnapshotHash,
        OffsetDateTime inputSnapshotAt,
        boolean legacyIdentityUnverified) {
    @JsonProperty("hardScore")
    public Integer hardScore() {
        return ScheduleScoreView.parse(score).hardScore();
    }

    @JsonProperty("mediumScore")
    public Integer mediumScore() {
        return ScheduleScoreView.parse(score).mediumScore();
    }

    @JsonProperty("softScore")
    public Integer softScore() {
        return ScheduleScoreView.parse(score).softScore();
    }

    @JsonProperty("scoreValid")
    public boolean scoreValid() {
        return ScheduleScoreView.parse(score).valid();
    }

    public ScheduleVersionView(
            long id,
            String status,
            String score,
            boolean publishable,
            List<ScheduleAssignmentView> assignments) {
        this(
                id,
                status,
                score,
                publishable,
                assignments,
                0L,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                false);
    }

    public ScheduleVersionView(
            long id,
            String status,
            String score,
            boolean publishable,
            List<ScheduleAssignmentView> assignments,
            long revision,
            OffsetDateTime updatedAt,
            OffsetDateTime archivedAt,
            boolean editLocked,
            String editLockOwner,
            String editLockReason,
            String termCode,
            String inputSnapshotHash,
            String ruleSnapshotHash,
            OffsetDateTime inputSnapshotAt) {
        this(
                id,
                status,
                score,
                publishable,
                assignments,
                revision,
                updatedAt,
                archivedAt,
                editLocked,
                editLockOwner,
                editLockReason,
                termCode,
                inputSnapshotHash,
                ruleSnapshotHash,
                inputSnapshotAt,
                false);
    }

    public static ScheduleVersionView from(long id, String status, Timetable timetable) {
        List<ScheduleAssignmentView> assignments =
                timetable.getOccurrences().stream().map(ScheduleVersionView::toAssignment).toList();
        boolean publishable =
                timetable.getScore() != null
                        && timetable.getScore().isFeasible()
                        && assignments.stream()
                                .allMatch(
                                        item ->
                                                item.timeslotCode() != null
                                                        && item.roomCode() != null)
                        && ScheduleValidation.validate(
                                        new ScheduleVersionView(
                                                id,
                                                status,
                                                String.valueOf(timetable.getScore()),
                                                false,
                                                assignments))
                                .isEmpty();
        return new ScheduleVersionView(
                id,
                status,
                String.valueOf(timetable.getScore()),
                publishable,
                assignments,
                0L,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                false);
    }

    private static ScheduleAssignmentView toAssignment(LessonOccurrence occurrence) {
        var timeslot = occurrence.getTimeslot();
        var room = occurrence.getRoom();
        return new ScheduleAssignmentView(
                occurrence.getId(),
                occurrence.getSubjectCode(),
                occurrence.getSubjectName(),
                occurrence.getTeacherCode(),
                occurrence.getTeacherName(),
                occurrence.getStudentGroupCode(),
                occurrence.getStudentGroupName(),
                timeslot == null ? null : timeslot.getId(),
                timeslot == null ? null : timeslot.getLabel(),
                timeslot == null ? 0 : timeslot.getWeekday(),
                timeslot == null ? 0 : timeslot.getPeriod(),
                room == null ? null : room.getId(),
                room == null ? null : room.getName(),
                "SOLVER",
                occurrence.isPinned(),
                occurrence.getDuration(),
                occurrence.getOccurrenceKey(),
                occurrence.getActivityGroupCode(),
                occurrence.getActivityType(),
                occurrence.getStudentCount(),
                occurrence.getRequiredFeatures(),
                room == null ? java.util.Set.of() : room.getFeatures(),
                room == null ? 0 : room.getCapacity(),
                occurrence.getTeachingRequirementId(),
                occurrence.getRequirementCode(),
                occurrence.getActivityIndex(),
                occurrence.getActivityMemberIndex(),
                occurrence.getPinnedPeriodCode(),
                occurrence.getActivityType());
    }
}
