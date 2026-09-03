package com.classschedule.schedule;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ScheduleValidation {
    private ScheduleValidation() {}

    public static List<String> validate(ScheduleVersionView version) {
        var errors = new java.util.ArrayList<String>();
        if (version.assignments().isEmpty()) errors.add("没有教学任务");
        if (version.assignments().stream().anyMatch(item -> item.timeslotCode() == null))
            errors.add("存在未分配时间的教学任务");
        if (version.assignments().stream().anyMatch(item -> item.roomCode() == null))
            errors.add("存在未分配教室的教学任务");
        errors.addAll(
                overlapErrors(version.assignments(), "教师", ScheduleAssignmentView::teacherCode));
        errors.addAll(
                overlapErrors(
                        version.assignments(), "班级", ScheduleAssignmentView::studentGroupCode));
        errors.addAll(overlapErrors(version.assignments(), "教室", ScheduleAssignmentView::roomCode));
        errors.addAll(activityErrors(version.assignments()));
        return errors;
    }

    private static List<String> overlapErrors(
            List<ScheduleAssignmentView> assignments,
            String label,
            Function<ScheduleAssignmentView, String> key) {
        var errors = new java.util.ArrayList<String>();
        for (int leftIndex = 0; leftIndex < assignments.size(); leftIndex++) {
            ScheduleAssignmentView left = assignments.get(leftIndex);
            if (left.timeslotCode() == null || key.apply(left) == null) continue;
            for (int rightIndex = leftIndex + 1; rightIndex < assignments.size(); rightIndex++) {
                ScheduleAssignmentView right = assignments.get(rightIndex);
                if (right.timeslotCode() == null || !key.apply(left).equals(key.apply(right)))
                    continue;
                if (overlaps(left, right) && !sameJoinedBlock(left, right)) {
                    errors.add(
                            label
                                    + "存在时段冲突: "
                                    + key.apply(left)
                                    + "@"
                                    + left.timeslotCode()
                                    + "/"
                                    + right.timeslotCode());
                }
            }
        }
        return errors;
    }

    private static boolean overlaps(ScheduleAssignmentView left, ScheduleAssignmentView right) {
        if (left.weekday() != right.weekday()) return false;
        int leftStart = left.period();
        int rightStart = right.period();
        return leftStart < rightStart + Math.max(1, right.duration())
                && rightStart < leftStart + Math.max(1, left.duration());
    }

    private static boolean sameJoinedBlock(
            ScheduleAssignmentView left, ScheduleAssignmentView right) {
        return left.activityGroupCode() != null
                && left.activityGroupCode().equals(right.activityGroupCode())
                && "JOINED".equals(activityType(left))
                && "JOINED".equals(activityType(right))
                && left.activityIndex() == right.activityIndex();
    }

    private static String activityType(ScheduleAssignmentView assignment) {
        return assignment.activityTypeSnapshot() != null
                        && !assignment.activityTypeSnapshot().isBlank()
                ? assignment.activityTypeSnapshot()
                : assignment.activityType();
    }

    private static List<String> activityErrors(List<ScheduleAssignmentView> assignments) {
        return assignments.stream()
                .filter(
                        item ->
                                item.activityGroupCode() != null
                                        && !item.activityGroupCode().isBlank())
                .collect(
                        Collectors.groupingBy(
                                item -> item.activityGroupCode() + "#" + item.activityIndex()))
                .entrySet()
                .stream()
                .flatMap(
                        entry -> {
                            List<ScheduleAssignmentView> members = entry.getValue();
                            String type =
                                    members.stream()
                                            .map(ScheduleValidation::activityType)
                                            .filter(value -> value != null && !value.isBlank())
                                            .findFirst()
                                            .orElse("");
                            boolean sameSlot =
                                    members.stream()
                                                    .map(ScheduleAssignmentView::timeslotCode)
                                                    .distinct()
                                                    .count()
                                            <= 1;
                            boolean sameRoom =
                                    members.stream()
                                                    .map(ScheduleAssignmentView::roomCode)
                                                    .distinct()
                                                    .count()
                                            <= 1;
                            if ("JOINED".equals(type) && (!sameSlot || !sameRoom))
                                return java.util.stream.Stream.of("合班活动组不同步: " + entry.getKey());
                            if ("SYNCHRONIZED".equals(type) && !sameSlot)
                                return java.util.stream.Stream.of("同步活动组不同步: " + entry.getKey());
                            return java.util.stream.Stream.<String>empty();
                        })
                .toList();
    }
}
