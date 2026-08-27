package com.classschedule.schedule;

import java.util.LinkedHashMap;
import java.util.Map;

public record ScheduleAssignmentView(
        long occurrenceId,
        String subjectCode,
        String subjectName,
        String teacherCode,
        String teacherName,
        String studentGroupCode,
        String studentGroupName,
        String timeslotCode,
        String timeslotLabel,
        int weekday,
        int period,
        String roomCode,
        String roomName,
        String source,
        boolean locked,
        int duration,
        String occurrenceKey,
        String activityGroupCode,
        String activityType,
        int studentCount,
        java.util.Set<String> requiredFeatures,
        java.util.Set<String> roomFeatures,
        int roomCapacity,
        Long teachingRequirementId,
        String requirementCode,
        int activityIndex,
        int activityMemberIndex,
        String pinnedPeriodCode,
        String activityTypeSnapshot) {
    public ScheduleAssignmentView(long occurrenceId, String subjectCode, String subjectName,
            String teacherCode, String teacherName, String studentGroupCode, String studentGroupName,
            String timeslotCode, String timeslotLabel, int weekday, int period, String roomCode,
            String roomName, String source, boolean locked, int duration, String occurrenceKey,
            String activityGroupCode, String activityType, int studentCount,
            java.util.Set<String> requiredFeatures, java.util.Set<String> roomFeatures, int roomCapacity) {
        this(occurrenceId, subjectCode, subjectName, teacherCode, teacherName, studentGroupCode,
                studentGroupName, timeslotCode, timeslotLabel, weekday, period, roomCode, roomName,
                source, locked, duration, occurrenceKey, activityGroupCode, activityType, studentCount,
                requiredFeatures, roomFeatures, roomCapacity, null, null, 0, -1, null, activityType);
    }

    public Map<String, Object> asMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("occurrenceId", occurrenceId);
        values.put("subjectCode", subjectCode); values.put("subjectName", subjectName);
        values.put("teacherCode", teacherCode); values.put("teacherName", teacherName);
        values.put("studentGroupCode", studentGroupCode); values.put("studentGroupName", studentGroupName);
        values.put("timeslotCode", timeslotCode == null ? "" : timeslotCode);
        values.put("timeslotLabel", timeslotLabel == null ? "" : timeslotLabel);
        values.put("weekday", weekday); values.put("period", period);
        values.put("roomCode", roomCode == null ? "" : roomCode); values.put("roomName", roomName == null ? "" : roomName);
        values.put("source", source); values.put("locked", locked); values.put("duration", duration);
        values.put("occurrenceKey", occurrenceKey); values.put("activityGroupCode", activityGroupCode); values.put("activityType", activityType);
        values.put("studentCount", studentCount); values.put("requiredFeatures", requiredFeatures); values.put("roomFeatures", roomFeatures); values.put("roomCapacity", roomCapacity);
        values.put("teachingRequirementId", teachingRequirementId); values.put("requirementCode", requirementCode);
        values.put("activityIndex", activityIndex); values.put("activityMemberIndex", activityMemberIndex); values.put("pinnedPeriodCode", pinnedPeriodCode); values.put("activityTypeSnapshot", activityTypeSnapshot);
        return values;
    }
}
