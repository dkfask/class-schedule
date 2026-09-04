package com.classschedule.solver;

import java.util.List;

public record PlanningProblem(
        List<Timeslot> timeslots,
        List<Room> rooms,
        List<LessonOccurrence> occurrences,
        List<ResourceAvailability> availabilities,
        List<TypedScheduleRule> rules) {
    public PlanningProblem(
            List<Timeslot> timeslots,
            List<Room> rooms,
            List<LessonOccurrence> occurrences,
            List<ResourceAvailability> availabilities) {
        this(timeslots, rooms, occurrences, availabilities, List.of());
    }

    public Timetable toTimetable() {
        Timetable timetable = new Timetable(timeslots, rooms, occurrences, availabilities, rules);
        timetable.initializeGreedyAssignments();
        return timetable;
    }
}
