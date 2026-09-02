package com.classschedule.solver;

import java.util.List;
import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;

@PlanningSolution
public class Timetable {
    @ProblemFactCollectionProperty
    private List<Timeslot> timeslots;

    @ProblemFactCollectionProperty
    @ValueRangeProvider(id = "roomRange")
    private List<Room> rooms;

    @PlanningEntityCollectionProperty
    private List<LessonOccurrence> occurrences;

    @ProblemFactCollectionProperty
    private List<ResourceAvailability> availabilities;

    @ProblemFactCollectionProperty
    private List<TypedScheduleRule> rules;

    @PlanningScore
    private HardMediumSoftScore score;

    public Timetable() {}

    public Timetable(List<Timeslot> timeslots, List<Room> rooms, List<LessonOccurrence> occurrences) {
        this(timeslots, rooms, occurrences, List.of(), List.of());
    }

    public Timetable(List<Timeslot> timeslots, List<Room> rooms, List<LessonOccurrence> occurrences, List<ResourceAvailability> availabilities) {
        this(timeslots, rooms, occurrences, availabilities, List.of());
    }

    public Timetable(List<Timeslot> timeslots, List<Room> rooms, List<LessonOccurrence> occurrences,
            List<ResourceAvailability> availabilities, List<TypedScheduleRule> rules) {
        this.timeslots = timeslots;
        this.rooms = rooms;
        this.occurrences = occurrences;
        this.availabilities = availabilities;
        this.rules = rules == null ? List.of() : rules;
        configureTimeslotRanges();
    }

    public List<Timeslot> getTimeslots() { return timeslots; }
    public void setTimeslots(List<Timeslot> timeslots) { this.timeslots = timeslots; configureTimeslotRanges(); }
    public List<Room> getRooms() { return rooms; }
    public void setRooms(List<Room> rooms) { this.rooms = rooms; }
    public List<LessonOccurrence> getOccurrences() { return occurrences; }
    public void setOccurrences(List<LessonOccurrence> occurrences) { this.occurrences = occurrences; configureTimeslotRanges(); }
    public List<ResourceAvailability> getAvailabilities() { return availabilities; }
    public void setAvailabilities(List<ResourceAvailability> availabilities) { this.availabilities = availabilities; }
    public List<TypedScheduleRule> getRules() { return rules; }
    public void setRules(List<TypedScheduleRule> rules) { this.rules = rules == null ? List.of() : rules; }
    public HardMediumSoftScore getScore() { return score; }
    public void setScore(HardMediumSoftScore score) { this.score = score; }

    private void configureTimeslotRanges() {
        if (timeslots == null || occurrences == null) return;
        occurrences.forEach(occurrence -> occurrence.setTimeslotPool(timeslots));
    }
}
