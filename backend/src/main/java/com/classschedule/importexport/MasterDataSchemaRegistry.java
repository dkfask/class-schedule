package com.classschedule.importexport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable schema registry shared by the template writer and the import parser. */
public final class MasterDataSchemaRegistry {
    public static final String TEMPLATE_TYPE = "MASTER_DATA";
    public static final String TEMPLATE_VERSION = "v1";
    public static final String FILE_NAME = "master-data-v1.xlsx";

    public record Sheet(String name, List<String> headers) {}

    public static final List<Sheet> SHEETS = List.of(
            new Sheet("说明", List.of("说明")),
            new Sheet("教师", List.of("code", "name", "active")),
            new Sheet("班级", List.of("code", "name", "groupType", "studentCount", "active")),
            new Sheet("课程", List.of("code", "name", "active")),
            new Sheet("教室", List.of("code", "name", "capacity", "roomType", "active")),
            new Sheet("教学需求", List.of("code", "termCode", "studentGroupCode", "subjectCode", "teacherCode", "weeklyPeriods", "durationPeriods", "studentCount", "pinnedPeriodCode", "active")),
            new Sheet("资源可用性", List.of("resourceType", "resourceCode", "termCode", "periodCode", "available")),
            new Sheet("特征目录", List.of("code", "name", "active")),
            new Sheet("教室特征", List.of("roomCode", "featureCode", "active")),
            new Sheet("教学需求特征", List.of("requirementCode", "featureCode", "active")),
            new Sheet("活动组", List.of("code", "name", "activityType", "termCode", "memberIndex", "requirementCode", "active")));

    private static final Map<String, List<String>> HEADERS;

    static {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Sheet sheet : SHEETS) headers.put(sheet.name(), sheet.headers());
        HEADERS = Map.copyOf(headers);
    }

    private MasterDataSchemaRegistry() {}

    public static List<String> headers(String sheet) {
        List<String> headers = HEADERS.get(sheet);
        if (headers == null) throw new IllegalArgumentException("未知 MASTER_DATA Sheet: " + sheet);
        return headers;
    }

    public static String schemaHash() {
        String canonical = SHEETS.stream()
                .map(sheet -> sheet.name() + "\t" + String.join("\t", sheet.headers()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法计算模板 Schema 摘要", exception);
        }
    }
}
