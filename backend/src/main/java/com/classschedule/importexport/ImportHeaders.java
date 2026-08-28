package com.classschedule.importexport;

import java.util.Map;

public final class ImportHeaders {
    private ImportHeaders() {}

    /** Legacy import headers kept for existing clients. */
    public static final Map<String, String[]> REQUIRED = Map.of(
            "教师", new String[] {"code", "name"},
            "班级", new String[] {"code", "name"},
            "课程", new String[] {"code", "name"},
            "教室", new String[] {"code", "name", "capacity"},
            "教学需求", new String[] {"code", "termCode", "studentGroupCode", "subjectCode", "teacherCode", "weeklyPeriods", "durationPeriods"});
}
