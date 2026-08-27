package com.classschedule.masterdata;

import java.util.Map;

public enum MasterDataResource {
    TEACHERS("teacher", "教师"),
    STUDENT_GROUPS("student_group", "班级"),
    SUBJECTS("subject", "课程"),
    ROOMS("room", "教室");

    private final String table;
    private final String label;

    MasterDataResource(String table, String label) {
        this.table = table;
        this.label = label;
    }

    public String table() { return table; }
    public String label() { return label; }

    public static MasterDataResource parse(String value) {
        return switch (value) {
            case "teachers" -> TEACHERS;
            case "student-groups" -> STUDENT_GROUPS;
            case "subjects" -> SUBJECTS;
            case "rooms" -> ROOMS;
            default -> throw new IllegalArgumentException("不支持的主数据资源: " + value);
        };
    }
}
