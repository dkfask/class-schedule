package com.classschedule.schedule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ScheduleSnapshotHashService {
    private final JdbcTemplate jdbc;

    public ScheduleSnapshotHashService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Snapshot snapshot(String termCode) {
        Long termId = jdbc.queryForObject("SELECT id FROM academic_term WHERE code = ?", Long.class, termCode);
        if (termId == null) throw new IllegalArgumentException("学期不存在: " + termCode);
        String input = hash(String.join("\n",
                "TERM|" + termCode,
                rows("SELECT code, name, start_date, end_date, status FROM academic_term WHERE id=?", termId, "code", "name", "start_date", "end_date", "status"),
                rows("SELECT code, weekday, period_no, label, start_time, end_time, continuity_group, break_after FROM period_template WHERE term_id=? ORDER BY weekday, period_no, code", termId, "code", "weekday", "period_no", "label", "start_time", "end_time", "continuity_group", "break_after"),
                rows("SELECT code, name, capacity, room_type, active FROM room ORDER BY code", null, "code", "name", "capacity", "room_type", "active"),
                rows("SELECT r.code, f.feature_code FROM room_feature f JOIN room r ON r.id=f.room_id ORDER BY r.code, f.feature_code", null, "code", "feature_code"),
                rows("SELECT t.code, a.period_code, a.available FROM teacher_availability a JOIN teacher t ON t.id=a.teacher_id WHERE a.term_id=? ORDER BY t.code, a.period_code", termId, "code", "period_code", "available"),
                rows("SELECT r.code, a.period_code, a.available FROM room_availability a JOIN room r ON r.id=a.room_id WHERE a.term_id=? ORDER BY r.code, a.period_code", termId, "code", "period_code", "available"),
                rows("SELECT g.code, a.period_code, a.available FROM student_group_availability a JOIN student_group g ON g.id=a.student_group_id WHERE a.term_id=? ORDER BY g.code, a.period_code", termId, "code", "period_code", "available"),
                rows("SELECT r.code, r.student_group_id, r.subject_id, r.teacher_id, r.weekly_periods, r.duration_periods, r.student_count, r.pinned_period_code, r.active FROM teaching_requirement r WHERE r.term_id=? ORDER BY r.code", termId, "code", "student_group_id", "subject_id", "teacher_id", "weekly_periods", "duration_periods", "student_count", "pinned_period_code", "active"),
                rows("SELECT r.code, f.feature_code FROM teaching_requirement_feature f JOIN teaching_requirement r ON r.id=f.teaching_requirement_id WHERE r.term_id=? ORDER BY r.code, f.feature_code", termId, "code", "feature_code"),
                rows("SELECT g.code, g.name, g.activity_type, m.member_index, r.code AS requirement_code FROM activity_group g JOIN activity_group_member m ON m.activity_group_id=g.id JOIN teaching_requirement r ON r.id=m.teaching_requirement_id WHERE g.term_id=? AND g.active=TRUE ORDER BY g.code, m.member_index", termId, "code", "name", "activity_type", "member_index", "requirement_code")));
        String rules = hash(String.join("\n",
                rows("SELECT p.code, p.name, i.rule_code, i.scope_type, i.scope_code, i.int_value, i.text_value, i.severity, i.weight, i.active FROM schedule_rule_profile p JOIN schedule_rule_instance i ON i.profile_id=p.id WHERE p.term_id=? AND p.active=TRUE ORDER BY p.code, i.rule_code, i.scope_type, i.scope_code", termId, "code", "name", "rule_code", "scope_type", "scope_code", "int_value", "text_value", "severity", "weight", "active")));
        return new Snapshot(termCode, input, rules);
    }

    private String rows(String sql, Long parameter, String... columns) {
        List<Map<String, Object>> rows = parameter == null ? jdbc.queryForList(sql) : jdbc.queryForList(sql, parameter);
        StringBuilder result = new StringBuilder();
        for (Map<String, Object> row : rows) {
            for (String column : columns) result.append(column).append('=').append(String.valueOf(row.get(column))).append('|');
            result.append('\n');
        }
        return result.toString();
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    public record Snapshot(String termCode, String inputHash, String ruleHash) {}
}
