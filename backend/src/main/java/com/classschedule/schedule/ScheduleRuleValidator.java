package com.classschedule.schedule;

import com.classschedule.solver.PeriodContinuity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ScheduleRuleValidator {
    public record Violation(String code, String message, String resourceCode, String occurrenceKey,
            String severity, int weight, boolean blocking) {
        public Violation(String code, String message, String resourceCode, String occurrenceKey) {
            this(code, message, resourceCode, occurrenceKey, "HARD", 1, true);
        }
    }

    private final JdbcTemplate jdbc;

    public ScheduleRuleValidator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Violation> validateAssignments(long versionId, List<ScheduleAssignmentView> assignments) {
        return validate(versionId, new ScheduleVersionView(versionId, "DRAFT", null, false, assignments));
    }

    public List<Violation> validate(long versionId, ScheduleVersionView version) {
        List<Violation> violations = new ArrayList<>();
        for (String error : ScheduleValidation.validate(version)) {
            violations.add(new Violation("SCHEDULE_VALIDATION", error, "", "", "HARD", 1, true));
        }
        if (version.assignments().isEmpty()) return violations;

        Long termId = jdbc.queryForObject(
                "SELECT s.term_id FROM schedule_version v JOIN schedule_scenario s ON s.id = v.scenario_id WHERE v.id = ?",
                Long.class, versionId);
        Map<Integer, PeriodInfo> periodInfos = new LinkedHashMap<>();
        Map<String, PeriodInfo> periodsByCode = new LinkedHashMap<>();
        Map<String, String> nextPeriodCodes = new LinkedHashMap<>();
        if (termId != null) {
            List<PeriodInfo> loadedPeriods = jdbc.query("SELECT weekday, period_no, code, continuity_group, break_after FROM period_template WHERE term_id = ? ORDER BY weekday, period_no",
                    (rs, rowNum) -> new PeriodInfo(rs.getInt("weekday"), rs.getInt("period_no"), rs.getString("code"), rs.getString("continuity_group"), rs.getBoolean("break_after")), termId);
            loadedPeriods.forEach(item -> {
                periodInfos.put(item.weekday() * 100 + item.period(), item);
                periodsByCode.put(item.code(), item);
            });
            nextPeriodCodes.putAll(PeriodContinuity.nextCodes(loadedPeriods.stream().map(item -> new PeriodContinuity.Segment(item.code(), item.weekday(), item.period(), item.continuityGroup(), item.breakAfter())).toList()));
        }

        Set<String> emitted = new LinkedHashSet<>();
        violations.addAll(validateOccurrenceBaseline(versionId, version.assignments(), emitted));
        for (ScheduleAssignmentView assignment : version.assignments()) {
            if (assignment.timeslotCode() == null) continue;
            PeriodInfo start = periodInfos.get(assignment.weekday() * 100 + assignment.period());
            String startCode = start == null ? null : start.code();
            for (int offset = 0; offset < Math.max(1, assignment.duration()); offset++) {
                PeriodInfo period = periodsByCode.get(occupancyCode(startCode, nextPeriodCodes, offset));
                PeriodInfo previous = offset == 0 ? null : periodsByCode.get(occupancyCode(startCode, nextPeriodCodes, offset - 1));
                if (period == null || start == null
                        || !java.util.Objects.equals(start.continuityGroup(), period.continuityGroup())
                        || (offset > 0 && (previous == null || previous.breakAfter()))) {
                    add(violations, emitted, "DURATION_BOUNDARY", "连堂超出当前学期连续节次边界", assignment.occurrenceKey(), assignment.occurrenceKey());
                    break;
                }
                String periodCode = period.code();
                if (termId != null) {
                    if (unavailable("teacher_availability", "teacher", assignment.teacherCode(), termId, periodCode)) {
                        add(violations, emitted, "TEACHER_UNAVAILABLE", "教师在目标节次不可用", assignment.teacherCode(), assignment.occurrenceKey());
                    }
                    if (unavailable("student_group_availability", "student_group", assignment.studentGroupCode(), termId, periodCode)) {
                        add(violations, emitted, "STUDENT_GROUP_UNAVAILABLE", "班级在目标节次不可用", assignment.studentGroupCode(), assignment.occurrenceKey());
                    }
                    if (assignment.roomCode() != null && unavailable("room_availability", "room", assignment.roomCode(), termId, periodCode)) {
                        add(violations, emitted, "ROOM_UNAVAILABLE", "教室在目标节次不可用", assignment.roomCode(), assignment.occurrenceKey());
                    }
                }
            }
            if (assignment.studentCount() > 0 && assignment.roomCapacity() > 0 && assignment.studentCount() > assignment.roomCapacity()) {
                add(violations, emitted, "ROOM_CAPACITY", "教室容量不足", assignment.roomCode(), assignment.occurrenceKey());
            }
            if (!assignment.roomFeatures().containsAll(assignment.requiredFeatures())) {
                Set<String> missing = new LinkedHashSet<>(assignment.requiredFeatures());
                missing.removeAll(assignment.roomFeatures());
                add(violations, emitted, "ROOM_FEATURE", "教室缺少必需特征: " + String.join(",", missing), assignment.roomCode(), assignment.occurrenceKey());
            }
        }

        Map<String, List<ScheduleAssignmentView>> activities = version.assignments().stream()
                .filter(item -> item.activityGroupCode() != null && !item.activityGroupCode().isBlank())
                .collect(java.util.stream.Collectors.groupingBy(
                        item -> item.activityGroupCode() + "#" + item.activityIndex(), LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        activities.forEach((blockKey, members) -> {
            String code = blockKey.substring(0, blockKey.lastIndexOf('#'));
            String type = members.stream().map(this::activityType)
                    .filter(value -> value != null && !value.isBlank()).findFirst().orElse("");
            boolean sameSlot = members.stream().map(ScheduleAssignmentView::timeslotCode).distinct().count() <= 1;
            boolean sameRoom = members.stream().map(ScheduleAssignmentView::roomCode).distinct().count() <= 1;
            if ("JOINED".equals(type) && (!sameSlot || !sameRoom)) {
                add(violations, emitted, "ACTIVITY_GROUP_NOT_JOINED", "合班成员必须使用同一节次和教室", code, blockKey);
            } else if ("SYNCHRONIZED".equals(type) && !sameSlot) {
                add(violations, emitted, "ACTIVITY_GROUP_NOT_SYNCHRONIZED", "同步课成员必须使用同一节次", code, blockKey);
            } else if ("CONSECUTIVE".equals(type)) {
                List<ScheduleAssignmentView> ordered = members.stream()
                        .sorted(java.util.Comparator.comparingInt(ScheduleAssignmentView::activityMemberIndex)
                                .thenComparingInt(ScheduleAssignmentView::period)).toList();
                for (int i = 1; i < ordered.size(); i++) {
                    ScheduleAssignmentView previous = ordered.get(i - 1);
                    ScheduleAssignmentView current = ordered.get(i);
                    if (previous.activityMemberIndex() < 0 || current.activityMemberIndex() < 0
                            || current.activityMemberIndex() != previous.activityMemberIndex() + 1
                            || previous.activityIndex() != current.activityIndex()
                            || previous.weekday() != current.weekday()
                            || !isAdjacent(previous, current, nextPeriodCodes)) {
                        add(violations, emitted, "ACTIVITY_GROUP_NOT_CONSECUTIVE", "连续活动成员必须在同一天连续节次", code, blockKey);
                        break;
                    }
                }
            }
        });
        if (termId != null) {
            validateActivityGroupCompleteness(termId, version.assignments(), violations, emitted);
        }
        validateDailyAndSpreadRules(versionId, version, violations, emitted);
        return violations;
    }

    private String occupancyCode(String startCode, Map<String, String> nextCodes, int offset) {
        String code = startCode;
        for (int index = 0; index < offset && code != null; index++) code = nextCodes.get(code);
        return code;
    }

    private int occupancyPeriod(ScheduleAssignmentView assignment, Map<String, String> nextCodes, int offset) {
        String code = occupancyCode(assignment.timeslotCode(), nextCodes, offset);
        if (code == null) return -1;
        int separator = code.lastIndexOf('-');
        try { return Integer.parseInt(code.substring(separator + 1)); } catch (RuntimeException exception) { return -1; }
    }

    private record PeriodInfo(int weekday, int period, String code, String continuityGroup, boolean breakAfter) {}
    private List<Violation> validateOccurrenceBaseline(long versionId, List<ScheduleAssignmentView> assignments, Set<String> emitted) {
        List<Violation> violations = new ArrayList<>();
        Integer requirementCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM teaching_requirement r JOIN schedule_scenario s ON s.term_id = r.term_id JOIN schedule_version v ON v.scenario_id = s.id WHERE v.id = ? AND r.active = TRUE",
                Integer.class, versionId);
        if (requirementCount == null || requirementCount == 0) return violations;

        Map<Long, RequirementBaseline> requirements = new LinkedHashMap<>();
        jdbc.query("SELECT r.id, r.code, r.weekly_periods, r.duration_periods, r.pinned_period_code FROM teaching_requirement r JOIN schedule_scenario s ON s.term_id = r.term_id JOIN schedule_version v ON v.scenario_id = s.id WHERE v.id = ? AND r.active = TRUE ORDER BY r.id",
                (rs, rowNum) -> {
                    requirements.put(rs.getLong("id"), new RequirementBaseline(rs.getLong("id"), rs.getString("code"),
                            rs.getInt("weekly_periods"), rs.getInt("duration_periods"), rs.getString("pinned_period_code")));
                    return null;
                }, versionId);
        boolean hasIdentity = assignments.stream().anyMatch(item -> item.teachingRequirementId() != null || item.requirementCode() != null);
        if (!hasIdentity) return violations;

        Map<String, List<ScheduleAssignmentView>> byRequirement = assignments.stream()
                .collect(java.util.stream.Collectors.groupingBy(this::requirementIdentity, LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        for (RequirementBaseline requirement : requirements.values()) {
            List<ScheduleAssignmentView> matched = new ArrayList<>();
            matched.addAll(byRequirement.getOrDefault(String.valueOf(requirement.id()), List.of()));
            matched.addAll(byRequirement.getOrDefault(requirement.code(), List.of()));
            if (matched.isEmpty()) {
                add(violations, emitted, "OCCURRENCE_MISSING", "教学需求缺少全部排课课次", requirement.code(), requirement.code());
                continue;
            }
            Set<String> occurrenceKeys = matched.stream().map(ScheduleAssignmentView::occurrenceKey)
                    .collect(java.util.stream.Collectors.toSet());
            if (occurrenceKeys.size() != matched.size()) {
                add(violations, emitted, "OCCURRENCE_DUPLICATE", "同一教学需求存在重复课次", requirement.code(), requirement.code());
            }
            if (matched.size() != requirement.weeklyPeriods()) {
                add(violations, emitted, "WEEKLY_PERIOD_MISMATCH", "教学需求周课时与排课课次数量不一致", requirement.code(), requirement.code());
            }
            for (ScheduleAssignmentView assignment : matched) {
                if (assignment.teachingRequirementId() == null || requirement.id() != assignment.teachingRequirementId()
                        || !java.util.Objects.equals(requirement.code(), assignment.requirementCode())) {
                    add(violations, emitted, "REQUIREMENT_IDENTITY_MISMATCH", "排课课次的教学需求身份与当前需求不一致", requirement.code(), assignment.occurrenceKey());
                }
                if (assignment.duration() != requirement.durationPeriods()) {
                    add(violations, emitted, "DURATION_MISMATCH", "排课时长与教学需求不一致", requirement.code(), assignment.occurrenceKey());
                }
                if (!java.util.Objects.equals(requirement.pinnedPeriodCode(), assignment.pinnedPeriodCode())) {
                    add(violations, emitted, "PINNED_PERIOD_MISMATCH", "固定节次快照与教学需求不一致", requirement.code(), assignment.occurrenceKey());
                }
                if (requirement.pinnedPeriodCode() != null && !requirement.pinnedPeriodCode().isBlank()
                        && !requirement.pinnedPeriodCode().equals(assignment.timeslotCode())) {
                    add(violations, emitted, "PINNED_PERIOD_CHANGED", "固定课不能修改节次", requirement.code(), assignment.occurrenceKey());
                }
            }
        }
        byRequirement.forEach((identity, matched) -> {
            boolean known = requirements.values().stream().anyMatch(item -> String.valueOf(item.id()).equals(identity) || item.code().equals(identity));
            if (!known) add(violations, emitted, "OCCURRENCE_EXTRA", "排课包含当前学期不存在的教学需求", identity, identity);
        });
        return violations;
    }

    private String requirementIdentity(ScheduleAssignmentView assignment) {
        if (assignment.teachingRequirementId() != null) return String.valueOf(assignment.teachingRequirementId());
        return assignment.requirementCode() == null ? "" : assignment.requirementCode();
    }

    private record RequirementBaseline(long id, String code, int weeklyPeriods, int durationPeriods, String pinnedPeriodCode) {}
    private boolean isAdjacent(ScheduleAssignmentView previous, ScheduleAssignmentView current, Map<String, String> nextPeriodCodes) {
        String code = occupancyCode(previous.timeslotCode(), nextPeriodCodes, Math.max(1, previous.duration()));
        return code != null && code.equals(current.timeslotCode());
    }

    private void validateActivityGroupCompleteness(long termId, List<ScheduleAssignmentView> assignments,
            List<Violation> violations, Set<String> emitted) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT g.code AS group_code, g.activity_type, m.member_index, r.code AS requirement_code, r.weekly_periods "
                        + "FROM activity_group g JOIN activity_group_member m ON m.activity_group_id=g.id "
                        + "JOIN teaching_requirement r ON r.id=m.teaching_requirement_id "
                        + "WHERE g.term_id=? AND g.active=TRUE AND r.active=TRUE "
                        + "ORDER BY g.code, m.member_index", termId);
        Map<String, List<ActivityMemberBaseline>> expected = new LinkedHashMap<>();
        Map<String, String> expectedTypes = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String code = String.valueOf(row.get("group_code"));
            expected.computeIfAbsent(code, ignored -> new ArrayList<>()).add(new ActivityMemberBaseline(
                    String.valueOf(row.get("requirement_code")),
                    ((Number) row.get("member_index")).intValue(),
                    ((Number) row.get("weekly_periods")).intValue()));
            expectedTypes.put(code, String.valueOf(row.get("activity_type")));
        }

        Map<String, List<ScheduleAssignmentView>> actualGroups = assignments.stream()
                .filter(item -> item.activityGroupCode() != null && !item.activityGroupCode().isBlank())
                .collect(java.util.stream.Collectors.groupingBy(ScheduleAssignmentView::activityGroupCode,
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        expected.forEach((groupCode, members) -> {
            List<ScheduleAssignmentView> actual = actualGroups.getOrDefault(groupCode, List.of());
            if (actual.isEmpty()) {
                add(violations, emitted, "ACTIVITY_GROUP_MISSING", "活动组在当前版本中完全缺失", groupCode, groupCode);
                return;
            }
            String expectedType = expectedTypes.get(groupCode);
            if (members.stream().map(ActivityMemberBaseline::weeklyPeriods).distinct().count() > 1) {
                add(violations, emitted, "ACTIVITY_GROUP_WEEKLY_MISMATCH", "活动组成员周课次数量不一致", groupCode, groupCode);
            }
            actual.stream().filter(item -> !java.util.Objects.equals(expectedType, activityType(item))).forEach(item ->
                    add(violations, emitted, "ACTIVITY_TYPE_SNAPSHOT_MISMATCH", "活动组类型快照与当前活动组不一致", groupCode, item.occurrenceKey()));

            int maxWeeklyPeriods = members.stream().mapToInt(ActivityMemberBaseline::weeklyPeriods).max().orElse(0);
            for (int activityIndex = 0; activityIndex < maxWeeklyPeriods; activityIndex++) {
                final int index = activityIndex;
                List<ActivityMemberBaseline> expectedBlock = members.stream()
                        .filter(member -> member.weeklyPeriods() > index).toList();
                List<ScheduleAssignmentView> actualBlock = actual.stream()
                        .filter(item -> item.activityIndex() == index).toList();
                String blockKey = groupCode + "#" + index;
                if (actualBlock.isEmpty()) {
                    add(violations, emitted, "ACTIVITY_GROUP_BLOCK_MISSING", "活动组缺少对应周次课次", groupCode, blockKey);
                    continue;
                }
                Map<String, List<ScheduleAssignmentView>> actualByRequirement = actualBlock.stream()
                        .collect(java.util.stream.Collectors.groupingBy(this::assignmentRequirementIdentity,
                                LinkedHashMap::new, java.util.stream.Collectors.toList()));
                for (ActivityMemberBaseline expectedMember : expectedBlock) {
                    List<ScheduleAssignmentView> matches = actualByRequirement.getOrDefault(expectedMember.requirementCode(), List.of());
                    if (matches.isEmpty()) {
                        add(violations, emitted, "ACTIVITY_MEMBER_MISSING", "活动组缺少期望成员", groupCode, blockKey + "|" + expectedMember.requirementCode());
                    } else if (matches.size() > 1) {
                        add(violations, emitted, "ACTIVITY_MEMBER_DUPLICATE", "活动组成员重复", groupCode, blockKey + "|" + expectedMember.requirementCode());
                    }
                    matches.stream().filter(item -> item.activityMemberIndex() != expectedMember.memberIndex()).forEach(item ->
                            add(violations, emitted, "ACTIVITY_MEMBER_INDEX_MISMATCH", "活动组成员索引快照不一致", groupCode, item.occurrenceKey()));
                }
                actualByRequirement.forEach((requirementCode, matches) -> {
                    boolean known = expectedBlock.stream().anyMatch(member -> member.requirementCode().equals(requirementCode));
                    if (!known) add(violations, emitted, "ACTIVITY_MEMBER_EXTRA", "活动组包含额外成员", groupCode, blockKey + "|" + requirementCode);
                });
            }
            actual.stream().filter(item -> item.activityIndex() < 0).forEach(item ->
                    add(violations, emitted, "ACTIVITY_INDEX_INVALID", "活动课次索引不能为负数", groupCode, item.occurrenceKey()));
        });
        actualGroups.keySet().stream().filter(groupCode -> !expected.containsKey(groupCode)).forEach(groupCode ->
                add(violations, emitted, "ACTIVITY_GROUP_EXTRA", "排课包含当前学期不存在的活动组", groupCode, groupCode));
    }

    private record ActivityMemberBaseline(String requirementCode, int memberIndex, int weeklyPeriods) {}

    private String activityType(ScheduleAssignmentView assignment) {
        return assignment.activityTypeSnapshot() != null && !assignment.activityTypeSnapshot().isBlank()
                ? assignment.activityTypeSnapshot() : assignment.activityType();
    }

    private String assignmentRequirementIdentity(ScheduleAssignmentView assignment) {
        return assignment.requirementCode() != null && !assignment.requirementCode().isBlank()
                ? assignment.requirementCode()
                : assignment.teachingRequirementId() == null ? "" : String.valueOf(assignment.teachingRequirementId());
    }

    private void validateDailyAndSpreadRules(long versionId, ScheduleVersionView version, List<Violation> violations, Set<String> emitted) {
        Long termId = jdbc.queryForObject("SELECT s.term_id FROM schedule_version v JOIN schedule_scenario s ON s.id=v.scenario_id WHERE v.id=?", Long.class, versionId);
        if (termId == null) return;
        List<Map<String, Object>> rules = jdbc.queryForList("SELECT rule_code, scope_type, scope_code, int_value, text_value, severity, weight FROM schedule_rule_instance i JOIN schedule_rule_profile p ON p.id=i.profile_id WHERE p.term_id=? AND i.active=TRUE AND p.active=TRUE", termId);
        for (Map<String, Object> rule : rules) {
            String code = String.valueOf(rule.get("rule_code"));
            int limit = rule.get("int_value") == null ? 0 : ((Number) rule.get("int_value")).intValue();
            String scopeCode = (String) rule.get("scope_code");
            String effectiveScopeCode = "__TERM__".equals(scopeCode) ? null : scopeCode;
            String textValue = rule.get("text_value") == null ? "" : String.valueOf(rule.get("text_value"));
            String severity = rule.get("severity") == null ? "HARD" : String.valueOf(rule.get("severity"));
            int weight = rule.get("weight") == null ? 1 : ((Number) rule.get("weight")).intValue();
            if ("TEACHER_GAP_POLICY".equals(code)) {
                validateTeacherGaps(version, effectiveScopeCode, textValue, severity, weight, violations, emitted);
                continue;
            }
            if ("TEACHER_PREFERRED_PERIOD".equals(code)) {
                validateTeacherPreferredPeriods(version, effectiveScopeCode, textValue, severity, weight, violations, emitted);
                continue;
            }
            if ("SUBJECT_MIN_SPREAD_DAYS".equals(code)) {
                validateSubjectSpread(version, effectiveScopeCode, limit, severity, weight, violations, emitted);
                continue;
            }
            if (limit <= 0) continue;
            if ("TEACHER_DAILY_MAX".equals(code) || "STUDENT_GROUP_DAILY_MAX".equals(code) || "SUBJECT_DAILY_MAX".equals(code)) {
                String field = "TEACHER_DAILY_MAX".equals(code) ? "teacherCode" : "STUDENT_GROUP_DAILY_MAX".equals(code) ? "studentGroupCode" : "subjectCode";
                Map<String, Integer> counts = version.assignments().stream().filter(a -> a.timeslotCode() != null)
                        .filter(a -> effectiveScopeCode == null || effectiveScopeCode.equals("TEACHER_DAILY_MAX".equals(code) ? a.teacherCode() : "STUDENT_GROUP_DAILY_MAX".equals(code) ? a.studentGroupCode() : a.subjectCode()))
                        .collect(java.util.stream.Collectors.groupingBy(a -> ("TEACHER_DAILY_MAX".equals(code) ? a.teacherCode() : "STUDENT_GROUP_DAILY_MAX".equals(code) ? a.studentGroupCode() : a.subjectCode()) + "|" + a.weekday(), java.util.stream.Collectors.summingInt(a -> Math.max(1, a.duration()))));
                counts.forEach((key, count) -> {
                    if (count > limit) addRuleViolation(violations, emitted, code, field + "每日课时超过上限 " + limit, key.split("\\|")[0], key, severity, weight);
                });
            }
        }
    }

    private void validateSubjectSpread(ScheduleVersionView version, String scopeCode, int minimumDays, String severity, int weight,
            List<Violation> violations, Set<String> emitted) {
        if (minimumDays <= 0) return;
        Map<String, Set<Integer>> days = new LinkedHashMap<>();
        version.assignments().stream().filter(a -> a.timeslotCode() != null)
                .filter(a -> scopeCode == null || scopeCode.equals(a.subjectCode()))
                .forEach(a -> days.computeIfAbsent(a.subjectCode(), ignored -> new LinkedHashSet<>()).add(a.weekday()));
        days.forEach((subject, occupiedDays) -> {
            if (occupiedDays.size() < minimumDays) addRuleViolation(violations, emitted, "SUBJECT_MIN_SPREAD_DAYS", "科目至少应分散到 " + minimumDays + " 个工作日，当前为 " + occupiedDays.size(), subject, subject, severity, weight);
        });
    }

    private void validateTeacherGaps(ScheduleVersionView version, String scopeCode, String policy, String severity, int weight,
            List<Violation> violations, Set<String> emitted) {
        if (!"NO_SINGLE_GAP".equalsIgnoreCase(policy.trim())) return;
        Map<String, Set<Integer>> occupied = new LinkedHashMap<>();
        version.assignments().stream().filter(a -> a.timeslotCode() != null)
                .filter(a -> scopeCode == null || scopeCode.equals(a.teacherCode()))
                .forEach(a -> {
                    Set<Integer> periods = occupied.computeIfAbsent(a.teacherCode() + "|" + a.weekday(), ignored -> new LinkedHashSet<>());
                    for (int i = 0; i < Math.max(1, a.duration()); i++) periods.add(a.period() + i);
                });
        occupied.forEach((key, periods) -> {
            if (periods.size() < 2) return;
            int min = periods.stream().mapToInt(Integer::intValue).min().orElse(0);
            int max = periods.stream().mapToInt(Integer::intValue).max().orElse(0);
            for (int period = min + 1; period < max; period++) {
                if (!periods.contains(period)) {
                    addRuleViolation(violations, emitted, "TEACHER_GAP_POLICY", "教师存在单节空档", key.split("\\|")[0], key, severity, weight);
                    break;
                }
            }
        });
    }

    private void validateTeacherPreferredPeriods(ScheduleVersionView version, String scopeCode, String preferred, String severity, int weight,
            List<Violation> violations, Set<String> emitted) {
        Set<String> allowed = java.util.Arrays.stream(preferred.split(",")).map(String::trim).filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toSet());
        if (allowed.isEmpty()) return;
        version.assignments().stream().filter(a -> a.timeslotCode() != null)
                .filter(a -> scopeCode == null || scopeCode.equals(a.teacherCode()))
                .filter(a -> !allowed.contains(a.timeslotCode()))
                .forEach(a -> addRuleViolation(violations, emitted, "TEACHER_PREFERRED_PERIOD", "教师未使用偏好节次", a.teacherCode(), a.occurrenceKey(), severity, weight));
    }

    private void addRuleViolation(List<Violation> violations, Set<String> emitted, String code, String message, String resource, String key, String severity, int weight) {
        if (emitted.add(code + "|" + resource + "|" + key)) {
            boolean blocking = "HARD".equalsIgnoreCase(severity);
            violations.add(new Violation(code, message, resource, key, severity.toUpperCase(), Math.max(1, weight), blocking));
        }
    }

    private boolean unavailable(String availabilityTable, String resourceTable, String resourceCode, long termId, String periodCode) {
        String sql = "SELECT COUNT(*) FROM " + availabilityTable + " a JOIN " + resourceTable + " r ON r.id = a." + resourceTable + "_id WHERE r.code = ? AND a.term_id = ? AND a.period_code = ? AND a.available = FALSE";
        Integer count = jdbc.queryForObject(sql, Integer.class, resourceCode, termId, periodCode);
        return count != null && count > 0;
    }

    private void add(List<Violation> violations, Set<String> emitted, String code, String message, String resource, String key) {
        addRuleViolation(violations, emitted, code, message, resource, key, "HARD", 1);
    }
}
