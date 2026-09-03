package com.classschedule.solver;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;

public class TimetableConstraintProvider implements ConstraintProvider {
    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
            teacherConflict(factory),
            studentGroupConflict(factory),
            roomConflict(factory),
            unassignedOccurrence(factory),
            pinnedPeriod(factory),
            roomCapacity(factory),
            roomFeatures(factory),
            resourceAvailability(factory),
            activityGroupSynchronization(factory),
            consecutiveActivity(factory),
            typedDailyMax(factory, "HARD"),
            typedDailyMax(factory, "MEDIUM"),
            typedDailyMax(factory, "SOFT"),
            typedSpread(factory, "HARD"),
            typedSpread(factory, "MEDIUM"),
            typedSpread(factory, "SOFT"),
            typedGap(factory, "HARD"),
            typedGap(factory, "MEDIUM"),
            typedGap(factory, "SOFT"),
            typedPreferred(factory, "HARD"),
            typedPreferred(factory, "MEDIUM"),
            typedPreferred(factory, "SOFT")
        };
    }

    Constraint teacherConflict(ConstraintFactory factory) {
        return factory.forEachUniquePair(LessonOccurrence.class)
                .filter(
                        (left, right) ->
                                overlaps(left, right)
                                        && same(left.getTeacherCode(), right.getTeacherCode())
                                        && !sameJoinedBlock(left, right))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("教师同一时段冲突");
    }

    Constraint studentGroupConflict(ConstraintFactory factory) {
        return factory.forEachUniquePair(LessonOccurrence.class)
                .filter(
                        (left, right) ->
                                overlaps(left, right)
                                        && same(
                                                left.getStudentGroupCode(),
                                                right.getStudentGroupCode())
                                        && !sameJoinedBlock(left, right))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("班级同一时段冲突");
    }

    Constraint roomConflictForTest(ConstraintFactory factory) {
        return roomConflict(factory);
    }

    private Constraint roomConflict(ConstraintFactory factory) {
        return factory.forEachUniquePair(LessonOccurrence.class)
                .filter(
                        (left, right) ->
                                overlaps(left, right)
                                        && left.getRoom() != null
                                        && right.getRoom() != null
                                        && left.getRoom().getId().equals(right.getRoom().getId())
                                        && !sameJoinedBlock(left, right))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("教室同一时段冲突");
    }

    Constraint unassignedOccurrence(ConstraintFactory factory) {
        return factory.forEach(LessonOccurrence.class)
                .filter(
                        occurrence ->
                                occurrence.getTimeslot() == null || occurrence.getRoom() == null)
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("教学任务未分配");
    }

    private Constraint pinnedPeriod(ConstraintFactory factory) {
        return factory.forEach(LessonOccurrence.class)
                .filter(
                        item ->
                                item.getPinnedPeriodCode() != null
                                        && !item.getPinnedPeriodCode().isBlank()
                                        && (item.getTimeslot() == null
                                                || !item.getPinnedPeriodCode()
                                                        .equals(item.getTimeslot().getId())))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("固定节次不可变更");
    }

    private Constraint roomCapacity(ConstraintFactory factory) {
        return factory.forEach(LessonOccurrence.class)
                .filter(
                        item ->
                                item.getRoom() != null
                                        && item.getStudentCount() > 0
                                        && item.getStudentCount() > item.getRoom().getCapacity())
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("教室容量不足");
    }

    private Constraint roomFeatures(ConstraintFactory factory) {
        return factory.forEach(LessonOccurrence.class)
                .filter(
                        item ->
                                item.getRoom() != null
                                        && !item.getRoom()
                                                .getFeatures()
                                                .containsAll(item.getRequiredFeatures()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("教室特征不满足");
    }

    private Constraint resourceAvailability(ConstraintFactory factory) {
        return factory.forEach(LessonOccurrence.class)
                .filter(item -> item.getTimeslot() != null && durationUsesUnavailablePeriod(item))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("资源不可用或连续课越界");
    }

    private boolean durationUsesUnavailablePeriod(LessonOccurrence item) {
        if (item.getTimeslot() == null) return false;
        String code = item.getTimeslot().getId();
        for (int offset = 0; offset < Math.max(1, item.getDuration()); offset++) {
            if (code == null
                    || (!item.getAvailablePeriodCodes().isEmpty()
                            && !item.getAvailablePeriodCodes().contains(code))) return true;
            if (item.getUnavailablePeriodCodes().contains(code)
                    || (item.getRoom() != null
                            && item.getRoom().getUnavailablePeriodCodes().contains(code)))
                return true;
            if (offset < Math.max(1, item.getDuration()) - 1) {
                String next = PeriodContinuity.codeAfter(item.getNextPeriodCodes(), code, 1);
                if (next == null || item.getBreakAfterPeriodCodes().contains(code)) return true;
                code = next;
            }
        }
        return false;
    }

    boolean resourceAvailabilityForTest(LessonOccurrence item) {
        return durationUsesUnavailablePeriod(item);
    }

    private boolean sameTimeslot(LessonOccurrence left, LessonOccurrence right) {
        return left.getTimeslot() != null
                && right.getTimeslot() != null
                && java.util.Objects.equals(
                        left.getTimeslot().getId(), right.getTimeslot().getId());
    }

    private boolean sameRoom(LessonOccurrence left, LessonOccurrence right) {
        return left.getRoom() != null
                && right.getRoom() != null
                && java.util.Objects.equals(left.getRoom().getId(), right.getRoom().getId());
    }

    private boolean sameJoinedBlock(LessonOccurrence left, LessonOccurrence right) {
        return same(left.getActivityGroupCode(), right.getActivityGroupCode())
                && "JOINED".equals(left.getActivityType())
                && "JOINED".equals(right.getActivityType())
                && left.getActivityIndex() == right.getActivityIndex();
    }

    private Constraint activityGroupSynchronization(ConstraintFactory factory) {
        return factory.forEachUniquePair(LessonOccurrence.class)
                .filter(
                        (left, right) ->
                                same(left.getActivityGroupCode(), right.getActivityGroupCode())
                                        && left.getActivityIndex() == right.getActivityIndex()
                                        && left.getActivityType() != null
                                        && ("JOINED".equals(left.getActivityType())
                                                || "SYNCHRONIZED".equals(left.getActivityType()))
                                        && (!sameTimeslot(left, right)
                                                || ("JOINED".equals(left.getActivityType())
                                                        && !sameRoom(left, right))))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("活动组不同步");
    }

    Constraint consecutiveActivityForTest(ConstraintFactory factory) {
        return consecutiveActivity(factory);
    }

    private Constraint consecutiveActivity(ConstraintFactory factory) {
        return factory.forEachUniquePair(LessonOccurrence.class)
                .filter(
                        (left, right) ->
                                same(left.getActivityGroupCode(), right.getActivityGroupCode())
                                        && "CONSECUTIVE".equals(left.getActivityType())
                                        && "CONSECUTIVE".equals(right.getActivityType())
                                        && sameConsecutivePair(left, right)
                                        && !consecutive(left, right))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("连续活动不连续");
    }

    private boolean sameConsecutivePair(LessonOccurrence left, LessonOccurrence right) {
        if (left.getActivityMemberIndex() >= 0 && right.getActivityMemberIndex() >= 0) {
            return left.getActivityIndex() == right.getActivityIndex()
                    && Math.abs(left.getActivityMemberIndex() - right.getActivityMemberIndex())
                            == 1;
        }
        return Math.abs(left.getActivityIndex() - right.getActivityIndex()) == 1;
    }

    private boolean consecutive(LessonOccurrence left, LessonOccurrence right) {
        if (left.getTimeslot() == null || right.getTimeslot() == null) return false;
        LessonOccurrence first = consecutiveOrder(left, right) <= 0 ? left : right;
        LessonOccurrence second = first == left ? right : left;
        return PeriodContinuity.isConsecutive(
                first.getNextPeriodCodes(),
                first.getTimeslot().getId(),
                second.getTimeslot().getId(),
                first.getDuration());
    }

    private int consecutiveOrder(LessonOccurrence left, LessonOccurrence right) {
        if (left.getActivityMemberIndex() >= 0 && right.getActivityMemberIndex() >= 0) {
            return Integer.compare(left.getActivityMemberIndex(), right.getActivityMemberIndex());
        }
        return Integer.compare(left.getActivityIndex(), right.getActivityIndex());
    }

    boolean overlaps(LessonOccurrence left, LessonOccurrence right) {
        if (left.getTimeslot() == null || right.getTimeslot() == null) return false;
        if (left.getTimeslot().getWeekday() != right.getTimeslot().getWeekday()) return false;
        int leftStart = left.getTimeslot().getPeriod();
        int rightStart = right.getTimeslot().getPeriod();
        return leftStart < rightStart + Math.max(1, right.getDuration())
                && rightStart < leftStart + Math.max(1, left.getDuration());
    }

    private boolean same(String left, String right) {
        return left != null && left.equals(right);
    }

    private boolean matchesLayer(TypedScheduleRule rule, String layer) {
        return switch (layer) {
            case "HARD" -> rule.isHard();
            case "MEDIUM" -> rule.isMedium();
            default -> !rule.isHard() && !rule.isMedium();
        };
    }

    private HardMediumSoftScore layerScore(String layer) {
        return switch (layer) {
            case "HARD" -> HardMediumSoftScore.ONE_HARD;
            case "MEDIUM" -> HardMediumSoftScore.ONE_MEDIUM;
            default -> HardMediumSoftScore.ONE_SOFT;
        };
    }

    private boolean isDailyMax(String code) {
        return "TEACHER_DAILY_MAX".equals(code)
                || "STUDENT_GROUP_DAILY_MAX".equals(code)
                || "SUBJECT_DAILY_MAX".equals(code);
    }

    private String dailyScopeResource(TypedScheduleRule rule, LessonOccurrence occurrence) {
        return switch (rule.ruleCode()) {
            case "TEACHER_DAILY_MAX" -> occurrence.getTeacherCode();
            case "STUDENT_GROUP_DAILY_MAX" -> occurrence.getStudentGroupCode();
            default -> occurrence.getSubjectCode();
        };
    }

    private String dailyGroupingResource(TypedScheduleRule rule, LessonOccurrence occurrence) {
        return "SUBJECT_DAILY_MAX".equals(rule.ruleCode())
                ? occurrence.getStudentGroupCode() + "|" + occurrence.getSubjectCode()
                : dailyScopeResource(rule, occurrence);
    }

    private record DailyKey(String ruleCode, int limit, int weight, String resource, int weekday) {}

    Constraint typedDailyMax(ConstraintFactory factory, String layer) {
        return factory.forEach(TypedScheduleRule.class)
                .filter(rule -> matchesLayer(rule, layer) && isDailyMax(rule.ruleCode()))
                .join(LessonOccurrence.class)
                .filter(
                        (rule, occurrence) ->
                                occurrence.getTimeslot() != null
                                        && rule.appliesTo(dailyScopeResource(rule, occurrence)))
                .groupBy(
                        (rule, occurrence) ->
                                new DailyKey(
                                        rule.ruleCode(),
                                        rule.limit(),
                                        rule.weight(),
                                        dailyGroupingResource(rule, occurrence),
                                        occurrence.getTimeslot().getWeekday()),
                        ConstraintCollectors.toList((rule, occurrence) -> occurrence))
                .filter(
                        (key, occurrences) ->
                                occurrences.stream()
                                                .mapToInt(item -> Math.max(1, item.getDuration()))
                                                .sum()
                                        > key.limit())
                .penalize(
                        layerScore(layer),
                        (key, occurrences) ->
                                key.weight()
                                        * (occurrences.stream()
                                                        .mapToInt(
                                                                item ->
                                                                        Math.max(
                                                                                1,
                                                                                item.getDuration()))
                                                        .sum()
                                                - key.limit()))
                .asConstraint("typed-daily-max-" + layer);
    }

    Constraint typedSpread(ConstraintFactory factory, String layer) {
        return factory.forEach(TypedScheduleRule.class)
                .filter(
                        rule ->
                                matchesLayer(rule, layer)
                                        && "SUBJECT_MIN_SPREAD_DAYS".equals(rule.ruleCode()))
                .join(LessonOccurrence.class)
                .filter(
                        (rule, occurrence) ->
                                occurrence.getTimeslot() != null
                                        && rule.appliesTo(occurrence.getSubjectCode()))
                .groupBy(
                        (rule, occurrence) ->
                                new SpreadKey(
                                        rule.ruleCode(),
                                        rule.limit(),
                                        rule.weight(),
                                        occurrence.getStudentGroupCode(),
                                        occurrence.getSubjectCode()),
                        ConstraintCollectors.toList((rule, occurrence) -> occurrence))
                .filter(
                        (key, occurrences) ->
                                occurrences.stream()
                                                .map(item -> item.getTimeslot().getWeekday())
                                                .collect(java.util.stream.Collectors.toSet())
                                                .size()
                                        < key.minimumDays())
                .penalize(
                        layerScore(layer),
                        (key, occurrences) ->
                                key.weight()
                                        * (key.minimumDays()
                                                - (int)
                                                        occurrences.stream()
                                                                .map(
                                                                        item ->
                                                                                item.getTimeslot()
                                                                                        .getWeekday())
                                                                .collect(
                                                                        java.util.stream.Collectors
                                                                                .toSet())
                                                                .size()))
                .asConstraint("typed-spread-" + layer);
    }

    private record SpreadKey(
            String ruleCode,
            int minimumDays,
            int weight,
            String studentGroupCode,
            String subjectCode) {}

    private boolean hasGap(java.util.List<LessonOccurrence> occurrences) {
        if (occurrences.size() < 2) return false;
        java.util.Set<Integer> occupied = new java.util.LinkedHashSet<>();
        for (LessonOccurrence item : occurrences) {
            for (int offset = 0; offset < Math.max(1, item.getDuration()); offset++)
                occupied.add(item.getTimeslot().getPeriod() + offset);
        }
        int min = occupied.stream().mapToInt(Integer::intValue).min().orElse(0);
        int max = occupied.stream().mapToInt(Integer::intValue).max().orElse(0);
        for (int period = min + 1; period < max; period++)
            if (!occupied.contains(period)) return true;
        return false;
    }

    Constraint typedGap(ConstraintFactory factory, String layer) {
        return factory.forEach(TypedScheduleRule.class)
                .filter(
                        rule ->
                                matchesLayer(rule, layer)
                                        && "TEACHER_GAP_POLICY".equals(rule.ruleCode())
                                        && "NO_SINGLE_GAP"
                                                .equalsIgnoreCase(
                                                        rule.textValue() == null
                                                                ? ""
                                                                : rule.textValue().trim()))
                .join(LessonOccurrence.class)
                .filter(
                        (rule, occurrence) ->
                                occurrence.getTimeslot() != null
                                        && rule.appliesTo(occurrence.getTeacherCode()))
                .groupBy(
                        (rule, occurrence) ->
                                rule.ruleCode()
                                        + "|"
                                        + rule.weight()
                                        + "|"
                                        + occurrence.getTeacherCode()
                                        + "|"
                                        + occurrence.getTimeslot().getWeekday(),
                        ConstraintCollectors.toList((rule, occurrence) -> occurrence))
                .filter((key, occurrences) -> hasGap(occurrences))
                .penalize(
                        layerScore(layer),
                        (key, occurrences) -> Integer.parseInt(key.split("\\|")[1]))
                .asConstraint("typed-gap-" + layer);
    }

    Constraint typedPreferred(ConstraintFactory factory, String layer) {
        return factory.forEach(TypedScheduleRule.class)
                .filter(
                        rule ->
                                matchesLayer(rule, layer)
                                        && "TEACHER_PREFERRED_PERIOD".equals(rule.ruleCode()))
                .join(LessonOccurrence.class)
                .filter(
                        (rule, occurrence) ->
                                occurrence.getTimeslot() != null
                                        && rule.appliesTo(occurrence.getTeacherCode())
                                        && !allowedPeriods(rule)
                                                .contains(occurrence.getTimeslot().getId()))
                .penalize(layerScore(layer), (rule, occurrence) -> rule.weight())
                .asConstraint("typed-preferred-" + layer);
    }

    private java.util.Set<String> allowedPeriods(TypedScheduleRule rule) {
        String raw = rule.textValue() == null ? "" : rule.textValue();
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toSet());
    }
}
