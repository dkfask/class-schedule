package com.classschedule.aiassist;

import static org.assertj.core.api.Assertions.assertThat;

import com.classschedule.schedule.ScheduleAssignmentView;
import com.classschedule.schedule.ScheduleVersionView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiDiagnosticsServiceTest {
    private final AiDiagnosticsService service = new AiDiagnosticsService();

    private ScheduleAssignmentView assignment(
            String subject, String teacher, String group, int weekday, int period, String room) {
        return new ScheduleAssignmentView(
                subject.hashCode() * 10L + period,
                subject,
                subject + "课",
                teacher,
                teacher + "师",
                group,
                group + "班",
                room == null ? null : "SLOT-" + weekday + "-" + period,
                room == null ? null : "周" + weekday + " 第" + period + "节",
                weekday,
                period,
                room,
                room,
                "SOLVER",
                false,
                1,
                "K-" + subject + "-" + weekday + "-" + period,
                null,
                null,
                0,
                java.util.Set.of(),
                java.util.Set.of(),
                50);
    }

    @Test
    void cleanScheduleReportsInfoOnly() {
        ScheduleVersionView version =
                new ScheduleVersionView(1, "CANDIDATE", "0hard/0medium/0soft", true, List.of(
                        assignment("CHN", "T001", "G7-1", 1, 1, "A101"),
                        assignment("ENG", "T002", "G7-1", 1, 2, "A101"),
                        assignment("MATH", "T001", "G7-2", 2, 1, "A102")));
        var result = service.diagnose(version);
        assertThat(result.get("metrics"))
                .hasFieldOrPropertyWithValue("occurrences", 3)
                .hasFieldOrPropertyWithValue("roomless", 0)
                .hasFieldOrPropertyWithValue("unplaced", 0);
        var findings = (List<?>) result.get("findings");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).toString()).contains("未发现明显问题");
    }

    @Test
    void roomlessAndHardConflictsAreFlaggedHigh() {
        ScheduleVersionView version =
                new ScheduleVersionView(2, "CANDIDATE", "2hard/0medium/0soft", false, List.of(
                        assignment("CHN", "T001", "G7-1", 1, 1, null),
                        assignment("ENG", "T002", "G7-2", 1, 1, "A101")));
        var result = service.diagnose(version);
        var findings = (List<Map<String, Object>>) result.get("findings");
        assertThat(findings).extracting(f -> f.get("severity")).contains("HIGH");
        assertThat(findings).extracting(f -> f.get("title")).anyMatch(title -> ((String) title).contains("未分配教室"));
        var metrics = (java.util.Map<String, Object>) result.get("metrics");
        assertThat(metrics.get("roomless")).isEqualTo(1);
    }

    @Test
    void teacherSameDayGapsAreFlaggedLow() {
        ScheduleVersionView version =
                new ScheduleVersionView(3, "CANDIDATE", "0hard/0medium/0soft", true, List.of(
                        assignment("CHN", "T001", "G7-1", 1, 1, "A101"),
                        assignment("MATH", "T001", "G7-1", 1, 4, "A101"),
                        assignment("ENG", "T002", "G7-2", 2, 1, "A102")));
        var result = service.diagnose(version);
        var findings = (List<Map<String, Object>>) result.get("findings");
        assertThat(findings)
                .extracting(f -> f.get("title"))
                .anyMatch(title -> ((String) title).contains("单日空档 2 节"));
    }
}
