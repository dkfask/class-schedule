package com.classschedule.solver;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PeriodContinuity {
    private PeriodContinuity() {}

    public record Segment(
            String code, int weekday, int period, String continuityGroup, boolean breakAfter) {}

    public static Map<String, String> nextCodes(Collection<Segment> segments) {
        Map<String, String> next = new LinkedHashMap<>();
        Map<Integer, List<Segment>> byWeekday =
                segments.stream()
                        .collect(
                                java.util.stream.Collectors.groupingBy(
                                        Segment::weekday,
                                        LinkedHashMap::new,
                                        java.util.stream.Collectors.toList()));
        byWeekday
                .values()
                .forEach(
                        items -> {
                            List<Segment> ordered =
                                    items.stream()
                                            .sorted(Comparator.comparingInt(Segment::period))
                                            .toList();
                            for (int index = 0; index + 1 < ordered.size(); index++) {
                                Segment current = ordered.get(index);
                                Segment following = ordered.get(index + 1);
                                if (current.period() + 1 == following.period()
                                        && Objects.equals(
                                                current.continuityGroup(),
                                                following.continuityGroup())
                                        && !current.breakAfter()) {
                                    next.put(current.code(), following.code());
                                }
                            }
                        });
        return next;
    }

    public static Map<String, String> nextCodesFromTimeslots(Collection<Timeslot> timeslots) {
        return nextCodes(
                timeslots.stream()
                        .map(
                                slot ->
                                        new Segment(
                                                slot.getId(),
                                                slot.getWeekday(),
                                                slot.getPeriod(),
                                                slot.getContinuityGroup(),
                                                slot.isBreakAfter()))
                        .toList());
    }

    public static String codeAfter(Map<String, String> nextCodes, String startCode, int periods) {
        String code = startCode;
        for (int index = 0; index < Math.max(0, periods) && code != null; index++) {
            code = nextCodes.get(code);
            if (code == null && nextCodes.isEmpty()) {
                code = fallbackNextCode(startCode, index + 1);
            }
        }
        return code;
    }

    public static boolean isConsecutive(
            Map<String, String> nextCodes, String startCode, String targetCode, int duration) {
        return targetCode != null
                && Objects.equals(
                        codeAfter(nextCodes, startCode, Math.max(1, duration)), targetCode);
    }

    public static boolean adjacent(Segment current, Segment following) {
        return current != null
                && following != null
                && current.period() + 1 == following.period()
                && current.weekday() == following.weekday()
                && Objects.equals(current.continuityGroup(), following.continuityGroup())
                && !current.breakAfter();
    }

    private static String fallbackNextCode(String startCode, int periods) {
        if (startCode == null) return null;
        int separator = startCode.lastIndexOf('-');
        if (separator < 0) return null;
        try {
            return startCode.substring(0, separator + 1)
                    + (Integer.parseInt(startCode.substring(separator + 1)) + periods);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
