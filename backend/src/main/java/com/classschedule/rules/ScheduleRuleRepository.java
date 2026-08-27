package com.classschedule.rules;

import com.classschedule.api.ScheduleRuleRequest;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ScheduleRuleRepository {
    private final JdbcTemplate jdbc;
    public ScheduleRuleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> list(String termCode) {
        return jdbc.queryForList("SELECT p.code AS profile_code, i.id, i.rule_code, i.scope_type, NULLIF(i.scope_code, '__TERM__') AS scope_code, i.int_value, i.text_value, i.severity, i.weight FROM schedule_rule_instance i JOIN schedule_rule_profile p ON p.id=i.profile_id JOIN academic_term t ON t.id=p.term_id WHERE t.code=? AND i.active=TRUE ORDER BY i.rule_code, i.scope_type, i.scope_code", termCode);
    }

    public List<Map<String, Object>> catalog() {
        return List.of(
                Map.of("ruleCode", "TEACHER_DAILY_MAX", "label", "教师每日课时上限", "valueType", "INTEGER", "scopes", List.of("TERM", "TEACHER")),
                Map.of("ruleCode", "STUDENT_GROUP_DAILY_MAX", "label", "班级每日课时上限", "valueType", "INTEGER", "scopes", List.of("TERM", "STUDENT_GROUP")),
                Map.of("ruleCode", "SUBJECT_DAILY_MAX", "label", "科目每日课时上限", "valueType", "INTEGER", "scopes", List.of("TERM", "SUBJECT")),
                Map.of("ruleCode", "SUBJECT_MIN_SPREAD_DAYS", "label", "科目最少分散天数", "valueType", "INTEGER", "scopes", List.of("TERM", "SUBJECT")),
                Map.of("ruleCode", "TEACHER_GAP_POLICY", "label", "教师空档策略", "valueType", "TEXT", "scopes", List.of("TERM", "TEACHER")),
                Map.of("ruleCode", "TEACHER_PREFERRED_PERIOD", "label", "教师偏好节次", "valueType", "TEXT", "scopes", List.of("TEACHER")));
    }

    @Transactional
    public void upsert(ScheduleRuleRequest request) {
        String scopeType = request.scopeType().trim().toUpperCase();
        String ruleCode = request.ruleCode().trim().toUpperCase();
        if (!List.of("TERM", "TEACHER", "STUDENT_GROUP", "SUBJECT", "TEACHING_REQUIREMENT").contains(scopeType)) throw new IllegalArgumentException("不支持的规则作用域");
        Map<String, List<String>> supportedScopes = Map.of(
                "TEACHER_DAILY_MAX", List.of("TERM", "TEACHER"),
                "STUDENT_GROUP_DAILY_MAX", List.of("TERM", "STUDENT_GROUP"),
                "SUBJECT_DAILY_MAX", List.of("TERM", "SUBJECT"),
                "SUBJECT_MIN_SPREAD_DAYS", List.of("TERM", "SUBJECT"),
                "TEACHER_GAP_POLICY", List.of("TERM", "TEACHER"),
                "TEACHER_PREFERRED_PERIOD", List.of("TEACHER"));
        if (!supportedScopes.containsKey(ruleCode)) throw new IllegalArgumentException("不支持的规则编码");
        if (!supportedScopes.get(ruleCode).contains(scopeType)) throw new IllegalArgumentException("规则编码与作用域不匹配");
        if ("TERM".equals(scopeType) && request.scopeCode() != null && !request.scopeCode().isBlank()) throw new IllegalArgumentException("TERM 作用域不能填写资源编码");
        if (!"TERM".equals(scopeType) && (request.scopeCode() == null || request.scopeCode().isBlank())) throw new IllegalArgumentException("资源作用域必须填写资源编码");
        Long termId = jdbc.query("SELECT id FROM academic_term WHERE code=? AND status <> 'ARCHIVED'", (rs, row) -> rs.getLong("id"), request.termCode())
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("学期不存在或已归档: " + request.termCode()));
        String persistedScopeCode = "TERM".equals(scopeType) ? "__TERM__" : blankToNull(request.scopeCode());
        validateScopeResource(scopeType, request.scopeCode(), termId);
        if (List.of("TEACHER_DAILY_MAX", "STUDENT_GROUP_DAILY_MAX", "SUBJECT_DAILY_MAX", "SUBJECT_MIN_SPREAD_DAYS").contains(ruleCode)
                && request.normalizedIntValue() <= 0) throw new IllegalArgumentException("该规则需要正整数参数");
        if (List.of("TEACHER_GAP_POLICY", "TEACHER_PREFERRED_PERIOD").contains(ruleCode)
                && (request.textValue() == null || request.textValue().isBlank())) throw new IllegalArgumentException("该规则需要文本参数");
        String severity = request.normalizedSeverity();
        if (!List.of("HARD", "MEDIUM", "SOFT").contains(severity)) throw new IllegalArgumentException("不支持的规则级别");
        Long profile = jdbc.queryForObject("INSERT INTO schedule_rule_profile(term_id,code,name) VALUES(?,'DEFAULT','默认规则配置') ON CONFLICT(term_id,code) DO UPDATE SET active=TRUE RETURNING id", Long.class, termId);
        jdbc.update("INSERT INTO schedule_rule_instance(profile_id,rule_code,scope_type,scope_code,int_value,text_value,severity,weight) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(profile_id,rule_code,scope_type,scope_code) DO UPDATE SET int_value=EXCLUDED.int_value,text_value=EXCLUDED.text_value,severity=EXCLUDED.severity,weight=EXCLUDED.weight,active=TRUE", profile, ruleCode, scopeType, persistedScopeCode, request.normalizedIntValue() == 0 ? null : request.normalizedIntValue(), blankToNull(request.textValue()), severity, request.normalizedWeight());
    }

    private void validateScopeResource(String scopeType, String scopeCode, long termId) {
        if ("TERM".equals(scopeType)) return;
        String table = switch (scopeType) {
            case "TEACHER" -> "teacher";
            case "STUDENT_GROUP" -> "student_group";
            case "SUBJECT" -> "subject";
            case "TEACHING_REQUIREMENT" -> "teaching_requirement";
            default -> throw new IllegalArgumentException("不支持的规则作用域");
        };
        String sql = "TEACHING_REQUIREMENT".equals(scopeType)
                ? "SELECT COUNT(*) FROM teaching_requirement WHERE code=? AND term_id=? AND active=TRUE"
                : "SELECT COUNT(*) FROM " + table + " WHERE code=? AND active=TRUE";
        int count = "TEACHING_REQUIREMENT".equals(scopeType)
                ? jdbc.queryForObject(sql, Integer.class, scopeCode, termId)
                : jdbc.queryForObject(sql, Integer.class, scopeCode);
        if (count == 0) throw new IllegalArgumentException("规则作用域资源不存在或已停用: " + scopeCode);
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    @Transactional
    public void delete(long id) {
        if (jdbc.update("UPDATE schedule_rule_instance SET active=FALSE WHERE id=?", id) != 1) throw new IllegalArgumentException("规则不存在: " + id);
    }
}
