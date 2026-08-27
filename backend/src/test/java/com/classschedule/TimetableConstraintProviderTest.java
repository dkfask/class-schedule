package com.classschedule.solver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.test.api.score.stream.ConstraintVerifier;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimetableConstraintProviderTest {
    private final ConstraintVerifier<TimetableConstraintProvider, Timetable> verifier =
            ConstraintVerifier.build(new TimetableConstraintProvider(), Timetable.class, LessonOccurrence.class);
    private final TimetableConstraintProvider provider = new TimetableConstraintProvider();

    @Test
    void sameTeacherAndGroupAtSameTimeAreHardConflicts() {
        Timeslot timeslot = new Timeslot("MON-1", 1, 1, "周一 第1节");
        Room room = new Room("A101", "教学楼 A101", 50);
        LessonOccurrence left = occurrence(1L, "T001", "G7-1");
        LessonOccurrence right = occurrence(2L, "T001", "G7-1");
        left.setTimeslot(timeslot);
        right.setTimeslot(timeslot);
        left.setRoom(room);
        right.setRoom(new Room("A102", "教学楼 A102", 50));

        verifier.verifyThat(TimetableConstraintProvider::teacherConflict)
                .given(left, right)
                .penalizesBy(1);
        verifier.verifyThat(TimetableConstraintProvider::studentGroupConflict)
                .given(left, right)
                .penalizesBy(1);
    }

    @Test
    void assignedTasksHaveNoUnassignedPenalty() {
        Timeslot timeslot = new Timeslot("MON-1", 1, 1, "周一 第1节");
        Room room = new Room("A101", "教学楼 A101", 50);
        LessonOccurrence occurrence = occurrence(1L, "T001", "G7-1");
        occurrence.setTimeslot(timeslot);
        occurrence.setRoom(room);

        verifier.verifyThat(TimetableConstraintProvider::unassignedOccurrence)
                .given(occurrence)
                .penalizesBy(0);
    }

    @Test
    void durationOccupiesAdjacentPeriods() {
        Timeslot first = new Timeslot("MON-1", 1, 1, "周一 第1节");
        Timeslot second = new Timeslot("MON-2", 1, 2, "周一 第2节");
        LessonOccurrence left = occurrence(1L, "T001", "G7-1");
        LessonOccurrence right = occurrence(2L, "T001", "G7-2");
        left.setDuration(2);
        left.setTimeslot(first);
        right.setTimeslot(second);
        assertThat(new TimetableConstraintProvider().overlaps(left, right)).isTrue();
        Room room = new Room("A101", "教学楼 A101", 50);
        Room otherRoom = new Room("A102", "教学楼 A102", 50);
        left.setRoom(room);
        right.setRoom(otherRoom);
        left.setPinned(true);
        right.setPinned(true);
        Timetable overlap = new Timetable(List.of(first, second), List.of(room, otherRoom), List.of(left, right));
        Timetable solved = SolverConfigurationForTest.createSolver().solve(overlap);
        assertThat(solved.getScore().getHardScore()).isEqualTo(-1);
    }

    @Test
    void availabilityUsesExactWeekdayPeriodCodesForDuration() {
        Timeslot monday = new Timeslot("MON-1", 1, 1, "周一 第1节");
        Room room = new Room("A101", "教学楼 A101", 50);
        LessonOccurrence occurrence = occurrence(1L, "T001", "G7-1");
        occurrence.setTimeslot(monday);
        occurrence.setRoom(room);
        occurrence.setDuration(2);
        occurrence.setAvailablePeriodCodes(new java.util.LinkedHashSet<>(List.of("MON-1", "MON-2", "TUE-2")));
        assertThat(new TimetableConstraintProvider().resourceAvailabilityForTest(occurrence)).isFalse();
        occurrence.setAvailablePeriodCodes(new java.util.LinkedHashSet<>(List.of("MON-1", "TUE-2")));
        assertThat(new TimetableConstraintProvider().resourceAvailabilityForTest(occurrence)).isTrue();
    }

    @Test
    void joinedMembersAtSameIndexMayShareTeacherAndRoom() {
        Timeslot timeslot = new Timeslot("MON-1", 1, 1, "周一 第1节");
        Room room = new Room("A101", "教学楼 A101", 50);
        LessonOccurrence left = occurrence(1L, "T001", "G7-1");
        LessonOccurrence right = occurrence(2L, "T001", "G7-2");
        left.setActivityGroupCode("JOIN-1");
        right.setActivityGroupCode("JOIN-1");
        left.setActivityType("JOINED");
        right.setActivityType("JOINED");
        left.setActivityIndex(0);
        right.setActivityIndex(0);
        left.setTimeslot(timeslot);
        right.setTimeslot(timeslot);
        left.setRoom(room);
        right.setRoom(room);
        verifier.verifyThat(TimetableConstraintProvider::teacherConflict).given(left, right).penalizesBy(0);
        verifier.verifyThat(TimetableConstraintProvider::roomConflictForTest).given(left, right).penalizesBy(0);
    }

    @Test
    void consecutiveActivityMustUseAdjacentPeriods() {
        Timeslot first = new Timeslot("MON-1", 1, 1, "周一 第1节");
        Timeslot third = new Timeslot("MON-3", 1, 3, "周一 第3节");
        Room room = new Room("A101", "教学楼 A101", 50);
        LessonOccurrence left = occurrence(1L, "T001", "G7-1");
        LessonOccurrence right = occurrence(2L, "T002", "G7-1");
        left.setActivityGroupCode("CON-1");
        right.setActivityGroupCode("CON-1");
        left.setActivityType("CONSECUTIVE");
        right.setActivityType("CONSECUTIVE");
        left.setActivityIndex(0);
        right.setActivityIndex(1);
        left.setTimeslot(first);
        right.setTimeslot(third);
        left.setRoom(room);
        right.setRoom(room);
        verifier.verifyThat(TimetableConstraintProvider::consecutiveActivityForTest).given(left, right).penalizesBy(1);
    }

    @Test
    void explicitBreakBlocksDurationAcrossPeriods() {
        Timeslot first = new Timeslot("MON-1", 1, 1, "周一 第1节");
        Room room = new Room("A101", "教学楼 A101", 50);
        LessonOccurrence item = occurrence(1L, "T001", "G7-1");
        item.setTimeslot(first);
        item.setRoom(room);
        item.setDuration(2);
        item.setAvailablePeriodCodes(new java.util.LinkedHashSet<>(List.of("MON-1", "MON-2")));
        item.setBreakAfterPeriodCodes(new java.util.LinkedHashSet<>(List.of("MON-1")));

        assertThat(new TimetableConstraintProvider().resourceAvailabilityForTest(item)).isTrue();
    }

    @Test
    void consecutiveMembersPairByMemberIndexWithinOneWeek() {
        Timeslot first = new Timeslot("MON-1", 1, 1, "周一 第1节");
        Timeslot third = new Timeslot("MON-3", 1, 3, "周一 第3节");
        Room room = new Room("A101", "教学楼 A101", 50);
        LessonOccurrence left = occurrence(1L, "T001", "G7-1");
        LessonOccurrence right = occurrence(2L, "T002", "G7-2");
        left.setActivityGroupCode("CON-INDEX");
        right.setActivityGroupCode("CON-INDEX");
        left.setActivityType("CONSECUTIVE");
        right.setActivityType("CONSECUTIVE");
        left.setActivityIndex(0);
        right.setActivityIndex(0);
        left.setActivityMemberIndex(0);
        right.setActivityMemberIndex(1);
        left.setTimeslot(first);
        right.setTimeslot(third);
        left.setRoom(room);
        right.setRoom(room);

        verifier.verifyThat(TimetableConstraintProvider::consecutiveActivityForTest)
                .given(left, right)
                .penalizesBy(1);
    }

    @Test
    void consecutiveMembersFromDifferentWeeksAreNotPaired() {
        Timeslot first = new Timeslot("MON-1", 1, 1, "周一 第1节");
        Timeslot third = new Timeslot("MON-3", 1, 3, "周一 第3节");
        Room room = new Room("A101", "教学楼 A101", 50);
        LessonOccurrence left = occurrence(1L, "T001", "G7-1");
        LessonOccurrence right = occurrence(2L, "T002", "G7-2");
        left.setActivityGroupCode("CON-WEEK");
        right.setActivityGroupCode("CON-WEEK");
        left.setActivityType("CONSECUTIVE");
        right.setActivityType("CONSECUTIVE");
        left.setActivityIndex(0);
        right.setActivityIndex(1);
        left.setActivityMemberIndex(0);
        right.setActivityMemberIndex(1);
        left.setTimeslot(first);
        right.setTimeslot(third);
        left.setRoom(room);
        right.setRoom(room);

        verifier.verifyThat(TimetableConstraintProvider::consecutiveActivityForTest)
                .given(left, right)
                .penalizesBy(0);
    }
    @Test
    void typedDailyMaxPenalizesByWeightOnMediumLayer() {
        TypedScheduleRule rule = new TypedScheduleRule("TEACHER_DAILY_MAX", "TERM", "__TERM__", 1, null, "MEDIUM", 5);
        Timeslot first = new Timeslot("MON-1", 1, 1, "周一 第1节");
        Timeslot second = new Timeslot("MON-2", 1, 2, "周一 第2节");
        Room room = new Room("A101", "教学楼 A101", 50);
        LessonOccurrence left = occurrence(1L, "T001", "G7-1");
        LessonOccurrence right = occurrence(2L, "T001", "G7-2");
        left.setTimeslot(first);
        left.setRoom(room);
        right.setTimeslot(second);
        right.setRoom(room);

        verifier.verifyThat((p, factory) -> p.typedDailyMax(factory, "MEDIUM"))
                .given(rule, left, right)
                .penalizesBy(5);
    }

    @Test
    void typedSpreadPenalizesSubjectsConcentratedOnFewerDays() {
        TypedScheduleRule rule = new TypedScheduleRule("SUBJECT_MIN_SPREAD_DAYS", "TERM", "__TERM__", 2, null, "SOFT", 3);
        Timeslot first = new Timeslot("MON-1", 1, 1, "周一 第1节");
        Room room = new Room("A101", "教学楼 A101", 50);
        LessonOccurrence left = occurrence(1L, "T001", "G7-1");
        LessonOccurrence right = occurrence(2L, "T002", "G7-2");
        left.setSubjectCode("MATH");
        right.setSubjectCode("MATH");
        left.setTimeslot(first);
        left.setRoom(room);
        right.setTimeslot(first);
        right.setRoom(room);

        verifier.verifyThat((p, factory) -> p.typedSpread(factory, "SOFT"))
                .given(rule, left, right)
                .penalizesBy(3);
    }

    @Test
    void typedGapPenalizesTeacherWithSingleGap() {
        TypedScheduleRule rule = new TypedScheduleRule("TEACHER_GAP_POLICY", "TERM", "__TERM__", null, "NO_SINGLE_GAP", "MEDIUM", 2);
        Timeslot first = new Timeslot("MON-1", 1, 1, "周一 第1节");
        Timeslot third = new Timeslot("MON-3", 1, 3, "周一 第3节");
        Room room = new Room("A101", "教学楼 A101", 50);
        LessonOccurrence left = occurrence(1L, "T001", "G7-1");
        LessonOccurrence right = occurrence(2L, "T001", "G7-2");
        left.setTimeslot(first);
        left.setRoom(room);
        right.setTimeslot(third);
        right.setRoom(room);

        verifier.verifyThat((p, factory) -> p.typedGap(factory, "MEDIUM"))
                .given(rule, left, right)
                .penalizesBy(2);
    }

    @Test
    void typedPreferredPenalizesTeacherOutsidePreferredPeriodsOnHardLayer() {
        TypedScheduleRule rule = new TypedScheduleRule("TEACHER_PREFERRED_PERIOD", "TEACHER", "T001", null, "MON-1,TUE-1", "HARD", 4);
        Timeslot slot = new Timeslot("WED-1", 3, 1, "周三 第1节");
        Room room = new Room("A101", "教学楼 A101", 50);
        LessonOccurrence left = occurrence(1L, "T001", "G7-1");
        left.setTimeslot(slot);
        left.setRoom(room);

        verifier.verifyThat((p, factory) -> p.typedPreferred(factory, "HARD"))
                .given(rule, left)
                .penalizesBy(4);
    }

    @Test
    void pinnedOccurrenceKeepsItsPlanningValueDuringSolve() {
        Timetable sample = SampleTimetableFactory.create();
        LessonOccurrence pinned = sample.getOccurrences().get(0);
        Timeslot fixed = sample.getTimeslots().get(2);
        pinned.setTimeslot(fixed);
        pinned.setRoom(sample.getRooms().get(0));
        pinned.setPinned(true);

        Timetable solved = SolverConfigurationForTest.createSolver().solve(sample);
        LessonOccurrence solvedPinned = solved.getOccurrences().stream()
                .filter(item -> item.getId().equals(pinned.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(solvedPinned.getTimeslot().getId()).isEqualTo("TUE-1");
    }


    @Test
    void sampleProblemCanBeSolvedWithoutHardConflicts() {
        Timetable sample = SampleTimetableFactory.create();
        var solver = SolverConfigurationForTest.createSolver();
        Timetable solved = solver.solve(sample);

        assertThat(solved.getScore()).isNotNull();
        assertThat(solved.getScore().getHardScore()).isZero();
    }

    private LessonOccurrence occurrence(long id, String teacher, String group) {
        return new LessonOccurrence(id, "SUBJECT-" + id, "课程" + id, teacher, teacher, group, group);
    }
}
