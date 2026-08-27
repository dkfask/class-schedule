package com.classschedule.masterdata;

public record TeachingRequirementItem(long id, String code, String termCode, String studentGroupCode,
        String subjectCode, String teacherCode, int weeklyPeriods, int durationPeriods,
        int studentCount, String requiredFeatures, String pinnedPeriodCode, boolean active) {
}
