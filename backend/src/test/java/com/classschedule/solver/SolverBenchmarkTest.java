package com.classschedule.solver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.solver.Solver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@Tag("benchmark")
@EnabledIfSystemProperty(named = "run.solver.benchmark", matches = "true")
class SolverBenchmarkTest {
    @Test
    @Timeout(20)
    void solvesARepresentativeLargeTimetableWithinConfiguredTermination() {
        long terminationMillis = Long.parseLong(System.getProperty("solver.benchmark.termination-ms", "3000"));
        assertThat(terminationMillis).isPositive();
        Timetable input = representativeTimetable();
        Solver<Timetable> solver = new SolverConfiguration(Duration.ofMillis(terminationMillis)).solverFactory().buildSolver();

        long started = System.nanoTime();
        Timetable solved = solver.solve(input);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(solved.getScore()).isNotNull();
        assertThat(solved.getOccurrences()).hasSize(240).allSatisfy(item -> {
            assertThat(item.getTimeslot()).as("timeslot for occurrence %s", item.getId()).isNotNull();
            assertThat(item.getRoom()).as("room for occurrence %s", item.getId()).isNotNull();
        });
        if (Boolean.parseBoolean(System.getProperty("solver.benchmark.require-zero-hard", "false"))) {
            assertThat(solved.getScore().hardScore()).as("hard score after %d ms", terminationMillis).isZero();
        }
        System.out.printf("solver benchmark: occurrences=%d, terminationMs=%d, elapsedMs=%d, score=%s%n",
                solved.getOccurrences().size(), terminationMillis, elapsedMillis, solved.getScore());
    }

    private Timetable representativeTimetable() {
        List<Timeslot> timeslots = new ArrayList<>();
        for (int weekday = 1; weekday <= 5; weekday++) {
            for (int period = 1; period <= 6; period++) {
                Timeslot slot = new Timeslot("DAY" + weekday + "-" + period, weekday, period,
                        "第" + weekday + "教学日 第" + period + "节");
                slot.setContinuityGroup("REGULAR_DAY");
                slot.setBreakAfter(period == 3);
                timeslots.add(slot);
            }
        }

        List<Room> rooms = new ArrayList<>();
        for (int index = 1; index <= 12; index++) {
            Room room = new Room("R" + String.format("%02d", index), "教学楼 " + index, 42 + (index % 3) * 8);
            if (index % 3 == 0) room.setFeatures(new LinkedHashSet<>(List.of("LAB")));
            rooms.add(room);
        }

        Map<String, String> nextPeriodCodes = PeriodContinuity.nextCodesFromTimeslots(timeslots);
        List<LessonOccurrence> occurrences = new ArrayList<>();
        for (int index = 0; index < 240; index++) {
            int subjectNumber = index % 12;
            LessonOccurrence occurrence = new LessonOccurrence(index + 1L, "SUB" + subjectNumber,
                    "课程 " + subjectNumber, "T" + String.format("%02d", index % 60),
                    "教师 " + (index % 60), "G" + String.format("%02d", index % 24),
                    "班级 " + (index % 24));
            occurrence.setOccurrenceKey("benchmark-" + index);
            occurrence.setTeachingRequirementId(index + 1L);
            occurrence.setRequirementCode("BENCH-" + index);
            occurrence.setDuration(index % 10 == 0 ? 2 : 1);
            occurrence.setStudentCount(28 + index % 18);
            occurrence.setAvailablePeriodCodes(new LinkedHashSet<>(timeslots.stream().map(Timeslot::getId).toList()));
            occurrence.setNextPeriodCodes(nextPeriodCodes);
            if (subjectNumber == 0) occurrence.setRequiredFeatures(new LinkedHashSet<>(List.of("LAB")));

            if (index < 40) {
                int pair = index / 2;
                occurrence.setActivityGroupCode("CON-BENCH-" + pair);
                occurrence.setActivityType("CONSECUTIVE");
                occurrence.setActivityIndex(0);
                occurrence.setActivityMemberIndex(index % 2);
            }
            occurrences.add(occurrence);
        }

        List<TypedScheduleRule> rules = List.of(
                new TypedScheduleRule("TEACHER_DAILY_MAX", "TERM", TypedScheduleRule.TERM_SCOPE_SENTINEL, 3, null, "HARD", 1),
                new TypedScheduleRule("STUDENT_GROUP_DAILY_MAX", "TERM", TypedScheduleRule.TERM_SCOPE_SENTINEL, 5, null, "HARD", 1),
                new TypedScheduleRule("SUBJECT_DAILY_MAX", "TERM", TypedScheduleRule.TERM_SCOPE_SENTINEL, 2, null, "SOFT", 2),
                new TypedScheduleRule("SUBJECT_MIN_SPREAD_DAYS", "TERM", TypedScheduleRule.TERM_SCOPE_SENTINEL, 3, null, "SOFT", 2));
        return new Timetable(timeslots, rooms, occurrences, List.of(), rules);
    }
}
