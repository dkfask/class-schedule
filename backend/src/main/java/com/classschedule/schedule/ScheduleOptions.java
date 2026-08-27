package com.classschedule.schedule;

import java.util.List;

public record ScheduleOptions(
        List<ScheduleOptionTimeslot> timeslots,
        List<ScheduleOptionRoom> rooms,
        List<ScheduleOptionResource> studentGroups,
        List<ScheduleOptionResource> teachers) {
    public record ScheduleOptionTimeslot(String code, String label, int weekday, int period) {}
    public record ScheduleOptionRoom(String code, String name, int capacity, String roomType) {}
    public record ScheduleOptionResource(String code, String name) {}
}
