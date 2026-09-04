package com.classschedule.solver;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@PlanningSolution
public class Timetable {
    @ProblemFactCollectionProperty private List<Timeslot> timeslots;

    @ProblemFactCollectionProperty
    private List<Room> rooms;

    @PlanningEntityCollectionProperty private List<LessonOccurrence> occurrences;

    @ProblemFactCollectionProperty private List<ResourceAvailability> availabilities;

    @ProblemFactCollectionProperty private List<TypedScheduleRule> rules;

    @PlanningScore private HardMediumSoftScore score;

    public Timetable() {}

    public Timetable(
            List<Timeslot> timeslots, List<Room> rooms, List<LessonOccurrence> occurrences) {
        this(timeslots, rooms, occurrences, List.of(), List.of());
    }

    public Timetable(
            List<Timeslot> timeslots,
            List<Room> rooms,
            List<LessonOccurrence> occurrences,
            List<ResourceAvailability> availabilities) {
        this(timeslots, rooms, occurrences, availabilities, List.of());
    }

    public Timetable(
            List<Timeslot> timeslots,
            List<Room> rooms,
            List<LessonOccurrence> occurrences,
            List<ResourceAvailability> availabilities,
            List<TypedScheduleRule> rules) {
        this.timeslots = timeslots;
        this.rooms = rooms;
        this.occurrences = occurrences;
        this.availabilities = availabilities;
        this.rules = rules == null ? List.of() : rules;
        configureTimeslotRanges();
        configureRoomRanges();
    }

    public List<Timeslot> getTimeslots() {
        return timeslots;
    }

    public void setTimeslots(List<Timeslot> timeslots) {
        this.timeslots = timeslots;
        configureTimeslotRanges();
    }

    public List<Room> getRooms() {
        return rooms;
    }

    public void setRooms(List<Room> rooms) {
        this.rooms = rooms;
        configureRoomRanges();
    }

    public List<LessonOccurrence> getOccurrences() {
        return occurrences;
    }

    public void setOccurrences(List<LessonOccurrence> occurrences) {
        this.occurrences = occurrences;
        configureTimeslotRanges();
        configureRoomRanges();
    }

    public List<ResourceAvailability> getAvailabilities() {
        return availabilities;
    }

    public void setAvailabilities(List<ResourceAvailability> availabilities) {
        this.availabilities = availabilities;
    }

    public List<TypedScheduleRule> getRules() {
        return rules;
    }

    public void setRules(List<TypedScheduleRule> rules) {
        this.rules = rules == null ? List.of() : rules;
    }

    public HardMediumSoftScore getScore() {
        return score;
    }

    public void setScore(HardMediumSoftScore score) {
        this.score = score;
    }

    void initializeGreedyAssignments() {
        if (timeslots == null || rooms == null || occurrences == null) return;
        List<LessonOccurrence> ordered =
                occurrences.stream()
                        .sorted(
                                Comparator.comparing(LessonOccurrence::isPinned)
                                        .reversed()
                                        .thenComparing(
                                                item ->
                                                        item.getId() == null
                                                                ? Long.MAX_VALUE
                                                                : item.getId()))
                        .toList();
        List<LessonOccurrence> assigned = new ArrayList<>();
        for (LessonOccurrence occurrence : ordered) {
            Timeslot selectedTimeslot = null;
            Room selectedRoom = null;
            for (Timeslot timeslot : occurrence.getTimeslotRange()) {
                for (Room room : occurrence.getRoomRange()) {
                    if (validResourcePlacement(occurrence, timeslot, room)
                            && doesNotConflict(occurrence, timeslot, room, assigned)) {
                        selectedTimeslot = timeslot;
                        selectedRoom = room;
                        break;
                    }
                }
                if (selectedTimeslot != null) break;
            }
            if (selectedTimeslot == null) {
                selectedTimeslot = occurrence.getTimeslotRange().stream().findFirst().orElse(null);
                selectedRoom = occurrence.getRoomRange().stream().findFirst().orElse(null);
            }
            occurrence.setTimeslot(selectedTimeslot);
            occurrence.setRoom(selectedRoom);
            assigned.add(occurrence);
        }
    }

    private void configureTimeslotRanges() {
        if (timeslots == null || occurrences == null) return;
        occurrences.forEach(occurrence -> occurrence.setTimeslotPool(timeslots));
    }

    private void configureRoomRanges() {
        if (rooms == null || occurrences == null) return;
        occurrences.forEach(
                occurrence -> {
                    List<Room> eligible =
                            rooms.stream()
                                    .filter(
                                            room ->
                                                    (occurrence.getStudentCount() <= 0
                                                                    || room.getCapacity()
                                                                            >= occurrence
                                                                                    .getStudentCount())
                                                            && room.getFeatures()
                                                                    .containsAll(
                                                                            occurrence
                                                                                    .getRequiredFeatures()))
                                    .toList();
                    occurrence.setRoomRange(eligible.isEmpty() ? rooms : eligible);
                });
    }

    private boolean validResourcePlacement(
            LessonOccurrence occurrence, Timeslot start, Room room) {
        String code = start == null ? null : start.getId();
        if (code == null) return false;
        for (int offset = 0; offset < Math.max(1, occurrence.getDuration()); offset++) {
            if ((!occurrence.getAvailablePeriodCodes().isEmpty()
                            && !occurrence.getAvailablePeriodCodes().contains(code))
                    || occurrence.getUnavailablePeriodCodes().contains(code)
                    || room.getUnavailablePeriodCodes().contains(code)) return false;
            if (offset < Math.max(1, occurrence.getDuration()) - 1) {
                String next = occurrence.getNextPeriodCodes().get(code);
                if (next == null || occurrence.getBreakAfterPeriodCodes().contains(code)) return false;
                code = next;
            }
        }
        return true;
    }

    private boolean doesNotConflict(
            LessonOccurrence candidate,
            Timeslot timeslot,
            Room room,
            List<LessonOccurrence> assigned) {
        for (LessonOccurrence other : assigned) {
            if (!overlaps(candidate, timeslot, other)) continue;
            boolean joined =
                    sameJoinedBlock(candidate, other)
                            && "JOINED".equals(candidate.getActivityType())
                            && "JOINED".equals(other.getActivityType());
            if (!joined
                    && (same(candidate.getTeacherCode(), other.getTeacherCode())
                            || same(candidate.getStudentGroupCode(), other.getStudentGroupCode())
                            || (other.getRoom() != null
                                    && room.getId().equals(other.getRoom().getId())))) return false;
        }
        return true;
    }

    private boolean overlaps(LessonOccurrence left, Timeslot leftTimeslot, LessonOccurrence right) {
        if (leftTimeslot == null || right.getTimeslot() == null) return false;
        if (leftTimeslot.getWeekday() != right.getTimeslot().getWeekday()) return false;
        int leftStart = leftTimeslot.getPeriod();
        int rightStart = right.getTimeslot().getPeriod();
        return leftStart < rightStart + Math.max(1, right.getDuration())
                && rightStart < leftStart + Math.max(1, left.getDuration());
    }

    private boolean sameJoinedBlock(LessonOccurrence left, LessonOccurrence right) {
        return same(left.getActivityGroupCode(), right.getActivityGroupCode())
                && left.getActivityIndex() == right.getActivityIndex();
    }

    private boolean same(String left, String right) {
        return left != null && left.equals(right);
    }
}
