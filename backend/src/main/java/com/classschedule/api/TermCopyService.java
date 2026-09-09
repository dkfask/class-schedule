package com.classschedule.api;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TermCopyService {
    private final JdbcTemplate jdbc;

    public TermCopyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> preview(TermCopyRequest request) {
        String source = required(request.sourceTermCode(), "来源学期不能为空");
        String target = required(request.targetTermCode(), "目标学期编码不能为空");
        requireTerm(source, "来源学期不存在: " + source);
        List<String> missing = missingMappings(source, request);
        Map<String, Object> counts = copyCounts(source);
        List<String> willCopy = List.of(
                "作息与节次模板",
                "教学计划草稿",
                "规则配置",
                "教师、班级和教室可用性",
                "活动组与教学需求特征");
        List<String> willNotCopy = List.of("已发布课表事实", "历史审计记录", "已完成求解任务", "上一学期的问题处理状态");
        return Map.of(
                "sourceTermCode", source,
                "targetTermCode", target,
                "targetExists", termExists(target),
                "missingMappings", missing,
                "canCopy", !termExists(target) && missing.isEmpty(),
                "counts", counts,
                "willCopy", willCopy,
                "willNotCopy", willNotCopy,
                "needsConfirmation", List.of("新学期仍需重新执行数据健康检查和发布门禁"));
    }

    @Transactional
    public Map<String, Object> copy(TermCopyRequest request) {
        Map<String, Object> plan = preview(request);
        if (Boolean.TRUE.equals(plan.get("targetExists")))
            throw new IllegalArgumentException("目标学期已存在: " + request.targetTermCode());
        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) plan.get("missingMappings");
        if (!missing.isEmpty()) throw new IllegalArgumentException("存在未匹配资源: " + String.join("、", missing));

        String source = required(request.sourceTermCode(), "来源学期不能为空");
        String target = required(request.targetTermCode(), "目标学期编码不能为空");
        Long sourceId = termId(source);
        Long targetId = jdbc.queryForObject(
                "INSERT INTO academic_term(code,name,start_date,end_date,status) VALUES(?,?,?,?, 'DRAFT') RETURNING id",
                Long.class,
                target,
                blankToDefault(request.targetName(), target),
                request.startDate(),
                request.endDate());

        int periods = jdbc.update(
                "INSERT INTO period_template(term_id,code,weekday,period_no,label,start_time,end_time,continuity_group,break_after) SELECT ?,code,weekday,period_no,label,start_time,end_time,continuity_group,break_after FROM period_template WHERE term_id=?",
                targetId,
                sourceId);
        Map<String, String> teacherMap = safeMap(request.teacherMappings());
        Map<String, String> groupMap = safeMap(request.studentGroupMappings());
        Map<String, String> subjectMap = safeMap(request.subjectMappings());
        Map<String, String> roomMap = safeMap(request.roomMappings());
        Map<String, String> requirementCodes = new HashMap<>();
        Map<String, String> activityCodes = new HashMap<>();
        int requirements = 0;
        int requirementFeatures = 0;
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT r.code,g.code AS group_code,s.code AS subject_code,t.code AS teacher_code,r.weekly_periods,r.duration_periods,r.student_count,r.pinned_period_code FROM teaching_requirement r JOIN student_group g ON g.id=r.student_group_id JOIN subject s ON s.id=r.subject_id JOIN teacher t ON t.id=r.teacher_id WHERE r.term_id=? AND r.active=TRUE ORDER BY r.id",
                sourceId)) {
            String oldCode = (String) row.get("code");
            String newCode = copyCode(target, oldCode);
            requirementCodes.put(oldCode, newCode);
            Long newId = jdbc.queryForObject(
                    "INSERT INTO teaching_requirement(code,term_id,student_group_id,subject_id,teacher_id,weekly_periods,duration_periods,student_count,pinned_period_code) VALUES(?,?,(SELECT id FROM student_group WHERE code=?),(SELECT id FROM subject WHERE code=?),(SELECT id FROM teacher WHERE code=?),?,?,?,?) RETURNING id",
                    Long.class,
                    newCode,
                    targetId,
                    mapped(groupMap, (String) row.get("group_code")),
                    mapped(subjectMap, (String) row.get("subject_code")),
                    mapped(teacherMap, (String) row.get("teacher_code")),
                    row.get("weekly_periods"),
                    row.get("duration_periods"),
                    row.get("student_count"),
                    row.get("pinned_period_code"));
            requirementFeatures += jdbc.update(
                    "INSERT INTO teaching_requirement_feature(teaching_requirement_id,feature_code) SELECT ?,feature_code FROM teaching_requirement_feature WHERE teaching_requirement_id=(SELECT id FROM teaching_requirement WHERE code=?)",
                    newId,
                    oldCode);
            requirements++;
        }
        int groups = 0;
        int groupMembers = 0;
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT code,name,activity_type FROM activity_group WHERE term_id=? AND active=TRUE ORDER BY id",
                sourceId)) {
            String oldCode = (String) row.get("code");
            String newCode = copyCode(target, oldCode);
            activityCodes.put(oldCode, newCode);
            Long newGroupId = jdbc.queryForObject(
                    "INSERT INTO activity_group(code,name,activity_type,term_id) VALUES(?,?,?,?) RETURNING id",
                    Long.class,
                    newCode,
                    row.get("name"),
                    row.get("activity_type"),
                    targetId);
            groupMembers += jdbc.update(
                    "INSERT INTO activity_group_member(activity_group_id,teaching_requirement_id,member_index) SELECT ?,nr.id,m.member_index FROM activity_group_member m JOIN teaching_requirement oldr ON oldr.id=m.teaching_requirement_id JOIN teaching_requirement nr ON nr.code=? || '-' || oldr.code WHERE m.activity_group_id=(SELECT id FROM activity_group WHERE code=?)",
                    newGroupId,
                    target,
                    oldCode);
            groups++;
        }
        int availability = copyAvailability(sourceId, targetId, teacherMap, groupMap, roomMap);
        int rules = copyRules(sourceId, targetId, target, teacherMap, groupMap, subjectMap, requirementCodes);
        return Map.of(
                "status", "COPIED",
                "sourceTermCode", source,
                "targetTermCode", target,
                "targetTermId", targetId,
                "copied", Map.of(
                        "periods", periods,
                        "requirements", requirements,
                        "requirementFeatures", requirementFeatures,
                        "activityGroups", groups,
                        "activityMembers", groupMembers,
                        "availability", availability,
                        "rules", rules),
                "needsRecheck", true);
    }

    private int copyAvailability(
            long sourceId,
            long targetId,
            Map<String, String> teacherMap,
            Map<String, String> groupMap,
            Map<String, String> roomMap) {
        int count = 0;
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT t.code AS resource_code,a.period_code,a.available FROM teacher_availability a JOIN teacher t ON t.id=a.teacher_id WHERE a.term_id=?",
                sourceId)) {
            count += jdbc.update(
                    "INSERT INTO teacher_availability(teacher_id,term_id,period_code,available) VALUES((SELECT id FROM teacher WHERE code=?),?,?,?) ON CONFLICT DO NOTHING",
                    mapped(teacherMap, (String) row.get("resource_code")), targetId, row.get("period_code"), row.get("available"));
        }
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT r.code AS resource_code,a.period_code,a.available FROM room_availability a JOIN room r ON r.id=a.room_id WHERE a.term_id=?",
                sourceId)) {
            count += jdbc.update(
                    "INSERT INTO room_availability(room_id,term_id,period_code,available) VALUES((SELECT id FROM room WHERE code=?),?,?,?) ON CONFLICT DO NOTHING",
                    mapped(roomMap, (String) row.get("resource_code")), targetId, row.get("period_code"), row.get("available"));
        }
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT g.code AS resource_code,a.period_code,a.available FROM student_group_availability a JOIN student_group g ON g.id=a.student_group_id WHERE a.term_id=?",
                sourceId)) {
            count += jdbc.update(
                    "INSERT INTO student_group_availability(student_group_id,term_id,period_code,available) VALUES((SELECT id FROM student_group WHERE code=?),?,?,?) ON CONFLICT DO NOTHING",
                    mapped(groupMap, (String) row.get("resource_code")), targetId, row.get("period_code"), row.get("available"));
        }
        return count;
    }

    private int copyRules(
            long sourceId,
            long targetId,
            String target,
            Map<String, String> teacherMap,
            Map<String, String> groupMap,
            Map<String, String> subjectMap,
            Map<String, String> requirementCodes) {
        Long profile = jdbc.queryForObject(
                "INSERT INTO schedule_rule_profile(term_id,code,name) VALUES(?, 'DEFAULT', ?) RETURNING id",
                Long.class,
                targetId,
                "从上一学期复制的规则配置");
        int count = 0;
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT i.rule_code,i.scope_type,i.scope_code,i.int_value,i.text_value,i.severity,i.weight FROM schedule_rule_instance i JOIN schedule_rule_profile p ON p.id=i.profile_id WHERE p.term_id=? AND p.active=TRUE AND i.active=TRUE",
                sourceId)) {
            String scope = (String) row.get("scope_type");
            String sourceScope = (String) row.get("scope_code");
            String targetScope = switch (scope) {
                case "TERM" -> "__TERM__";
                case "TEACHER" -> mapped(teacherMap, sourceScope);
                case "STUDENT_GROUP" -> mapped(groupMap, sourceScope);
                case "SUBJECT" -> mapped(subjectMap, sourceScope);
                case "TEACHING_REQUIREMENT" -> requirementCodes.getOrDefault(sourceScope, copyCode(target, sourceScope));
                default -> sourceScope;
            };
            count += jdbc.update(
                    "INSERT INTO schedule_rule_instance(profile_id,rule_code,scope_type,scope_code,int_value,text_value,severity,weight) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                    profile,
                    row.get("rule_code"),
                    scope,
                    targetScope,
                    row.get("int_value"),
                    row.get("text_value"),
                    row.get("severity"),
                    row.get("weight"));
        }
        return count;
    }

    private List<String> missingMappings(String source, TermCopyRequest request) {
        Long sourceId = termId(source);
        List<String> missing = new ArrayList<>();
        checkMapped("TEACHER", jdbc.queryForList(
                "SELECT DISTINCT t.code FROM teaching_requirement r JOIN teacher t ON t.id=r.teacher_id WHERE r.term_id=? AND r.active=TRUE UNION SELECT DISTINCT t.code FROM teacher_availability a JOIN teacher t ON t.id=a.teacher_id WHERE a.term_id=?",
                sourceId,
                sourceId), request.teacherMappings(), "teacher", missing);
        checkMapped("STUDENT_GROUP", jdbc.queryForList(
                "SELECT DISTINCT g.code FROM teaching_requirement r JOIN student_group g ON g.id=r.student_group_id WHERE r.term_id=? AND r.active=TRUE UNION SELECT DISTINCT g.code FROM student_group_availability a JOIN student_group g ON g.id=a.student_group_id WHERE a.term_id=?",
                sourceId,
                sourceId), request.studentGroupMappings(), "student_group", missing);
        checkMapped("SUBJECT", jdbc.queryForList(
                "SELECT DISTINCT s.code FROM teaching_requirement r JOIN subject s ON s.id=r.subject_id WHERE r.term_id=? AND r.active=TRUE",
                sourceId), request.subjectMappings(), "subject", missing);
        checkMapped("ROOM", jdbc.queryForList(
                "SELECT DISTINCT r.code FROM room_availability a JOIN room r ON r.id=a.room_id WHERE a.term_id=?",
                sourceId), request.roomMappings(), "room", missing);
        return missing;
    }

    private void checkMapped(
            String label,
            List<Map<String, Object>> rows,
            Map<String, String> mappings,
            String table,
            List<String> missing) {
        for (Map<String, Object> row : rows) {
            String source = String.valueOf(row.values().iterator().next());
            String target = mapped(mappings, source);
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE code=? AND active=TRUE", Integer.class, target);
            if (count == null || count == 0) missing.add(label + ": " + source + " → " + target);
        }
    }

    private Map<String, Object> copyCounts(String source) {
        Long id = termId(source);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("periods", count("SELECT COUNT(*) FROM period_template WHERE term_id=?", id));
        counts.put("requirements", count("SELECT COUNT(*) FROM teaching_requirement WHERE term_id=? AND active=TRUE", id));
        counts.put("activityGroups", count("SELECT COUNT(*) FROM activity_group WHERE term_id=? AND active=TRUE", id));
        counts.put("rules", count("SELECT COUNT(*) FROM schedule_rule_instance i JOIN schedule_rule_profile p ON p.id=i.profile_id WHERE p.term_id=? AND p.active=TRUE AND i.active=TRUE", id));
        return counts;
    }

    private long count(String sql, Object arg) {
        Number value = jdbc.queryForObject(sql, Number.class, arg);
        return value == null ? 0 : value.longValue();
    }

    private String copyCode(String target, String source) {
        return target + "-" + source;
    }

    private String mapped(Map<String, String> mappings, String source) {
        String target = safeMap(mappings).get(source);
        return target == null || target.isBlank() ? source : target.trim();
    }

    private Map<String, String> safeMap(Map<String, String> value) {
        return value == null ? Map.of() : value;
    }

    private boolean termExists(String code) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM academic_term WHERE code=?", Integer.class, code) > 0;
    }

    private Long termId(String code) {
        return jdbc.queryForObject("SELECT id FROM academic_term WHERE code=?", Long.class, code);
    }

    private void requireTerm(String code, String message) {
        if (!termExists(code)) throw new IllegalArgumentException(message);
    }

    private String required(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
