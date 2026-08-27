package com.classschedule.solver;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PeriodContinuity {
    private PeriodContinuity() {}

    public record Segment(String code, int weekday, int period, String continuityGroup, boolean breakAfter) {}

    public static Map<String, String> nextCodes(Collection<Segment> segments) {
        Map<String, String> next = new LinkedHashMap<>();
        Map<Integer, List<Segment>> byWeekday = segments.stream()
                .collect(java.util.stream.Collectors.groupingBy(Segment::weekday, LinkedHashMap::new, java.util.stream.Collectors.toList()));
        byWeekday.values().forEach(items -> {
            List<Segment> ordered = items.stream().sorted(Comparator.comparingInt(Segment::period)).toList();
            for (int index = 0; index + 1 < ordered.size(); index++) {
                Segment current = ordered.get(index);
                Segment following = ordered.get(index + 1);
                if (current.period() + 1 == following.period()
                        && Objects.equals(current.continuityGroup(), following.continuityGroup())
                        && !current.breakAfter()) {
                    next.put(current.code(), following.code());
                }
            }
        });
        return next;
    }

    public static Map<String, String> nextCodesFromTimeslots(Collection<Timeslot> timeslots) {
        return nextCodes(timeslots.stream()
                .map(slot -> new Segment(slot.getId(), slot.getWeekday(), slot.getPeriod(), slot.getContinuityGroup(), slot.isBreakAfter()))
                .toList());
    }

    public static boolean adjacent(Segment current, Segment following) {
        return current != null && following != null
                && current.period() + 1 == following.period()
                && current.weekday() == following.weekday()
                && Objects.equals(current.continuityGroup(), following.continuityGroup())
                && !current.breakAfter();
    }
}
