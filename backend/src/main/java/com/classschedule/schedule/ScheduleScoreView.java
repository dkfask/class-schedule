package com.classschedule.schedule;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ScheduleScoreView(
        String score, Integer hardScore, Integer mediumScore, Integer softScore) {
    private static final Pattern COMPONENT =
            Pattern.compile("^(-?\\d+)(hard|medium|soft)$", Pattern.CASE_INSENSITIVE);

    public static ScheduleScoreView parse(String raw) {
        if (raw == null || raw.isBlank() || "等待结果".equals(raw)) {
            return new ScheduleScoreView(raw, null, null, null);
        }
        Integer hard = null;
        Integer medium = null;
        Integer soft = null;
        String[] components = raw.trim().split("/");
        if (components.length != 2 && components.length != 3) {
            return new ScheduleScoreView(raw, null, null, null);
        }
        for (String component : components) {
            Matcher matcher = COMPONENT.matcher(component.trim());
            if (!matcher.matches()) return new ScheduleScoreView(raw, null, null, null);
            int value;
            try {
                value = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException exception) {
                return new ScheduleScoreView(raw, null, null, null);
            }
            switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "hard" -> hard = hard == null ? value : null;
                case "medium" -> medium = medium == null ? value : null;
                case "soft" -> soft = soft == null ? value : null;
                default -> {
                    return new ScheduleScoreView(raw, null, null, null);
                }
            }
        }
        if (hard == null
                || (components.length == 3 && (medium == null || soft == null))
                || (components.length == 2 && (medium != null || soft == null))) {
            return new ScheduleScoreView(raw, null, null, null);
        }
        return new ScheduleScoreView(raw, hard, medium == null ? 0 : medium, soft);
    }

    public static ScheduleScoreView from(
            ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore score) {
        return score == null ? parse(null) : parse(score.toString());
    }

    public boolean valid() {
        return hardScore != null && mediumScore != null && softScore != null;
    }

    public boolean hardFeasible() {
        return valid() && hardScore == 0;
    }
}
