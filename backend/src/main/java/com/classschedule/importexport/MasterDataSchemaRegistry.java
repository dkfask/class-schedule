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
            new Sheet("教师", List.of("编码", "名称", "是否启用")),
            new Sheet("班级", List.of("编码", "名称", "班级类型", "学生人数", "是否启用")),
            new Sheet("课程", List.of("编码", "名称", "是否启用")),
            new Sheet("教室", List.of("编码", "名称", "容量", "教室类型", "是否启用")),
            new Sheet("教学需求", List.of("编码", "学期编码", "班级编码", "课程编码", "教师编码", "每周课时", "单次节数", "学生人数", "固定节次编码", "是否启用")),
            new Sheet("资源可用性", List.of("资源类型", "资源编码", "学期编码", "节次编码", "是否可用")),
            new Sheet("特征目录", List.of("编码", "名称", "是否启用")),
            new Sheet("教室特征", List.of("教室编码", "特征编码", "是否启用")),
            new Sheet("教学需求特征", List.of("教学需求编码", "特征编码", "是否启用")),
            new Sheet("活动组", List.of("编码", "名称", "活动组类型", "学期编码", "成员序号", "教学需求编码", "是否启用")));

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
