package com.classschedule.api;

import com.classschedule.rules.ScheduleRuleRepository;
import com.classschedule.schedule.ScheduleAssignmentView;
import com.classschedule.schedule.ScheduleRepository;
import com.classschedule.schedule.ScheduleVersionView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RuleImpactPreviewService {
    private final ScheduleRepository schedules;
    private final ScheduleRuleRepository rules;
    private final JdbcTemplate jdbc;

    public RuleImpactPreviewService(
            ScheduleRepository schedules, ScheduleRuleRepository rules, JdbcTemplate jdbc) {
        this.schedules = schedules;
        this.rules = rules;
        this.jdbc = jdbc;
    }

    public Map<String, Object> preview(long versionId, ScheduleRuleRequest request, String actor) {
        if (!schedules.canAccessVersion(versionId, actor))
            throw new IllegalArgumentException("版本不存在或无权访问: " + versionId);
        String versionTerm = schedules.termCodeForVersion(versionId);
        if (!versionTerm.equals(request.termCode().trim()))
            throw new IllegalArgumentException("预估规则学期必须与版本学期一致: " + versionTerm);
        rules.validateForPreview(request);

        ScheduleVersionView version = schedules.findVersion(versionId);
        String ruleCode = request.ruleCode().trim().toUpperCase();
        String scopeCode = "TERM".equals(request.scopeType().trim().toUpperCase())
                ? null
                : request.scopeCode() == null ? null : request.scopeCode().trim();
        List<Map<String, Object>> violations = new ArrayList<>();
        Set<Long> affectedIds = new LinkedHashSet<>();
        switch (ruleCode) {
            case "TEACHER_DAILY_MAX", "STUDENT_GROUP_DAILY_MAX", "SUBJECT_DAILY_MAX" ->
                    previewDailyMax(
                            version.assignments(),
                            ruleCode,
                            scopeCode,
                            request.normalizedIntValue(),
                            request.normalizedSeverity(),
                            request.normalizedWeight(),
                            violations,
                            affectedIds);
            case "SUBJECT_MIN_SPREAD_DAYS" ->
                    previewSubjectSpread(
                            version.assignments(),
                            scopeCode,
                            request.normalizedIntValue(),
                            request.normalizedSeverity(),
                            request.normalizedWeight(),
                            violations,
                            affectedIds);
            case "TEACHER_GAP_POLICY" ->
                    previewTeacherGap(
                            version.assignments(),
                            scopeCode,
                            request.textValue(),
                            request.normalizedSeverity(),
                            request.normalizedWeight(),
                            violations,
                            affectedIds);
            case "TEACHER_PREFERRED_PERIOD" ->
                    previewTeacherPreferred(
                            version.assignments(),
                            scopeCode,
                            request.textValue(),
                            request.normalizedSeverity(),
                            request.normalizedWeight(),
                            violations,
                            affectedIds);
            case "PREFER_ORIGINAL_SLOT" ->
                    previewOriginalSlots(
                            versionTerm,
                            version.assignments(),
                            request.normalizedSeverity(),
                            request.normalizedWeight(),
                            violations,
                            affectedIds);
            default -> throw new IllegalArgumentException("不支持的规则编码: " + ruleCode);
        }

        int blockingCount = "HARD".equalsIgnoreCase(request.normalizedSeverity())
                ? violations.size()
                : 0;
        String summary = violations.isEmpty()
                ? "当前版本没有命中这条规则"
                : "当前版本有 " + affectedIds.size() + " 个课次会受到影响";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("versionId", versionId);
        result.put("termCode", versionTerm);
        result.put("ruleCode", ruleCode);
        result.put("severity", request.normalizedSeverity());
        result.put("weight", request.normalizedWeight());
        result.put("valid", true);
        result.put("canTrialSolve", true);
        result.put("affectedCount", affectedIds.size());
        result.put("blockingCount", blockingCount);
        result.put("violations", violations);
        result.put("summary", summary);
        return result;
    }

    private void previewDailyMax(
            List<ScheduleAssignmentView> assignments,
            String code,
            String scopeCode,
            int limit,
            String severity,
            int weight,
            List<Map<String, Object>> violations,
            Set<Long> affectedIds) {
        Map<String, List<ScheduleAssignmentView>> groups = assignments.stream()
                .filter(item -> item.timeslotCode() != null)
                .filter(item -> scopeCode == null || scopeCode.equals(dailyResource(code, item)))
                .collect(Collectors.groupingBy(
                        item -> dailyResource(code, item) + "|" + item.weekday(),
                        LinkedHashMap::new,
                        Collectors.toList()));
        groups.forEach((key, items) -> {
            int count = items.stream().mapToInt(item -> Math.max(1, item.duration())).sum();
            if (count <= limit) return;
            String resource = key.substring(0, key.lastIndexOf('|'));
            for (ScheduleAssignmentView item : items)
                addViolation(
                        code,
                        dailyMessage(code, limit, count),
                        resource,
                        item,
                        severity,
                        weight * Math.max(1, count - limit),
                        violations,
                        affectedIds);
        });
    }

    private void previewSubjectSpread(
            List<ScheduleAssignmentView> assignments,
            String scopeCode,
            int minimumDays,
            String severity,
            int weight,
            List<Map<String, Object>> violations,
            Set<Long> affectedIds) {
        Map<String, List<ScheduleAssignmentView>> groups = assignments.stream()
                .filter(item -> item.timeslotCode() != null)
                .filter(item -> scopeCode == null || scopeCode.equals(item.subjectCode()))
                .collect(Collectors.groupingBy(
                        item -> item.studentGroupCode() + "|" + item.subjectCode(),
                        LinkedHashMap::new,
                        Collectors.toList()));
        groups.forEach((key, items) -> {
            long days = items.stream().map(ScheduleAssignmentView::weekday).distinct().count();
            if (days >= minimumDays) return;
            for (ScheduleAssignmentView item : items)
                addViolation(
                        "SUBJECT_MIN_SPREAD_DAYS",
                        "科目至少应分散到 " + minimumDays + " 个工作日，当前为 " + days,
                        item.subjectCode(),
                        item,
                        severity,
                        weight * Math.max(1, minimumDays - (int) days),
                        violations,
                        affectedIds);
        });
    }

    private void previewTeacherGap(
            List<ScheduleAssignmentView> assignments,
            String scopeCode,
            String policy,
            String severity,
            int weight,
            List<Map<String, Object>> violations,
            Set<Long> affectedIds) {
        if (policy == null || !"NO_SINGLE_GAP".equalsIgnoreCase(policy.trim())) return;
        Map<String, List<ScheduleAssignmentView>> groups = assignments.stream()
                .filter(item -> item.timeslotCode() != null)
                .filter(item -> scopeCode == null || scopeCode.equals(item.teacherCode()))
                .collect(Collectors.groupingBy(
                        item -> item.teacherCode() + "|" + item.weekday(),
                        LinkedHashMap::new,
                        Collectors.toList()));
        groups.forEach((key, items) -> {
            Set<Integer> periods = new LinkedHashSet<>();
            for (ScheduleAssignmentView item : items)
                for (int offset = 0; offset < Math.max(1, item.duration()); offset++)
                    periods.add(item.period() + offset);
            if (periods.size() < 2) return;
            int min = periods.stream().min(Comparator.naturalOrder()).orElse(0);
            int max = periods.stream().max(Comparator.naturalOrder()).orElse(0);
            boolean gap = false;
            for (int period = min + 1; period < max; period++)
                if (!periods.contains(period)) gap = true;
            if (!gap) return;
            for (ScheduleAssignmentView item : items)
                addViolation(
                        "TEACHER_GAP_POLICY",
                        "教师存在单节空档",
                        key.substring(0, key.lastIndexOf('|')),
                        item,
                        severity,
                        weight,
                        violations,
                        affectedIds);
        });
    }

    private void previewTeacherPreferred(
            List<ScheduleAssignmentView> assignments,
            String scopeCode,
            String preferred,
            String severity,
            int weight,
            List<Map<String, Object>> violations,
            Set<Long> affectedIds) {
        Set<String> allowed = splitCodes(preferred);
        if (allowed.isEmpty()) return;
        assignments.stream()
                .filter(item -> item.timeslotCode() != null)
                .filter(item -> scopeCode == null || scopeCode.equals(item.teacherCode()))
                .filter(item -> !allowed.contains(item.timeslotCode()))
                .forEach(item -> addViolation(
                        "TEACHER_PREFERRED_PERIOD",
                        "教师未使用偏好节次",
                        item.teacherCode(),
                        item,
                        severity,
                        weight,
                        violations,
                        affectedIds));
    }

    private void previewOriginalSlots(
            String termCode,
            List<ScheduleAssignmentView> assignments,
            String severity,
            int weight,
            List<Map<String, Object>> violations,
            Set<Long> affectedIds) {
        Long termId = jdbc.queryForObject(
                "SELECT id FROM academic_term WHERE code=?", Long.class, termCode);
        Map<String, String> preferredByRequirement = new LinkedHashMap<>();
        jdbc.query(
                "SELECT code, preferred_period_codes FROM teaching_requirement WHERE term_id=? AND active=TRUE",
                (rs, rowNum) -> {
                    preferredByRequirement.put(rs.getString("code"), rs.getString("preferred_period_codes"));
                    return null;
                },
                termId);
        assignments.stream()
                .filter(item -> item.timeslotCode() != null)
                .forEach(item -> {
                    String preferred = preferredAt(
                            preferredByRequirement.get(item.requirementCode()), item.activityIndex());
                    if (preferred != null && !preferred.equals(item.timeslotCode()))
                        addViolation(
                                "PREFER_ORIGINAL_SLOT",
                                "课次未落在期望节次 " + preferred,
                                item.requirementCode(),
                                item,
                                severity,
                                weight,
                                violations,
                                affectedIds);
                });
    }

    private String dailyResource(String code, ScheduleAssignmentView item) {
        return switch (code) {
            case "TEACHER_DAILY_MAX" -> item.teacherCode();
            case "STUDENT_GROUP_DAILY_MAX" -> item.studentGroupCode();
            default -> item.subjectCode();
        };
    }

    private String dailyMessage(String code, int limit, int count) {
        String label = switch (code) {
            case "TEACHER_DAILY_MAX" -> "教师";
            case "STUDENT_GROUP_DAILY_MAX" -> "班级";
            default -> "科目";
        };
        return label + "每日课时超过上限 " + limit + "，当前为 " + count;
    }

    private Set<String> splitCodes(String value) {
        if (value == null) return Set.of();
        return Arrays.stream(value.split("[;,；，]"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String preferredAt(String codes, int index) {
        if (codes == null || codes.isBlank() || index < 0) return null;
        String[] values = codes.split(";");
        if (index >= values.length) return null;
        String value = values[index].trim();
        return value.isBlank() ? null : value;
    }

    private void addViolation(
            String code,
            String message,
            String resourceCode,
            ScheduleAssignmentView item,
            String severity,
            int weight,
            List<Map<String, Object>> violations,
            Set<Long> affectedIds) {
        if (!affectedIds.add(item.occurrenceId())) return;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", code);
        row.put("message", message);
        row.put("resourceCode", resourceCode == null ? "" : resourceCode);
        row.put("occurrenceId", item.occurrenceId());
        row.put("occurrenceKey", item.occurrenceKey() == null ? "" : item.occurrenceKey());
        row.put("subjectName", item.subjectName());
        row.put("teacherName", item.teacherName());
        row.put("studentGroupName", item.studentGroupName());
        row.put("timeslotCode", item.timeslotCode());
        row.put("severity", severity);
        row.put("weight", Math.max(1, weight));
        violations.add(row);
    }
}
