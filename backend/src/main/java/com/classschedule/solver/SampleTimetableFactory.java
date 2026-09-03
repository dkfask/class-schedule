package com.classschedule.solver;

import java.util.List;

public final class SampleTimetableFactory {
    private SampleTimetableFactory() {}

    public static Timetable create() {
        List<Timeslot> timeslots =
                List.of(
                        new Timeslot("MON-1", 1, 1, "周一 第1节"),
                        new Timeslot("MON-2", 1, 2, "周一 第2节"),
                        new Timeslot("TUE-1", 2, 1, "周二 第1节"),
                        new Timeslot("TUE-2", 2, 2, "周二 第2节"));
        List<Room> rooms =
                List.of(new Room("A101", "教学楼 A101", 50), new Room("A102", "教学楼 A102", 50));
        List<LessonOccurrence> occurrences =
                List.of(
                        new LessonOccurrence(1L, "MATH", "数学", "T001", "张老师", "G7-1", "七年级1班"),
                        new LessonOccurrence(2L, "CHN", "语文", "T002", "李老师", "G7-1", "七年级1班"),
                        new LessonOccurrence(3L, "ENG", "英语", "T003", "王老师", "G7-2", "七年级2班"));
        return new Timetable(timeslots, rooms, occurrences);
    }
}
