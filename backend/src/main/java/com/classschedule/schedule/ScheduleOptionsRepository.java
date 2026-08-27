package com.classschedule.schedule;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ScheduleOptionsRepository {
    private final JdbcTemplate jdbc;
    public ScheduleOptionsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public ScheduleOptions options(long versionId) {
        Long termId = jdbc.queryForObject("SELECT s.term_id FROM schedule_scenario s JOIN schedule_version v ON v.scenario_id=s.id WHERE v.id=?", Long.class, versionId);
        var timeslots = jdbc.query("SELECT code,label,weekday,period_no FROM period_template WHERE term_id=? ORDER BY weekday,period_no", (rs,row)->new ScheduleOptions.ScheduleOptionTimeslot(rs.getString("code"),rs.getString("label"),rs.getInt("weekday"),rs.getInt("period_no")), termId);
        var rooms = jdbc.query("SELECT code,name,capacity,room_type FROM room WHERE active=TRUE ORDER BY code", (rs,row)->new ScheduleOptions.ScheduleOptionRoom(rs.getString("code"),rs.getString("name"),rs.getInt("capacity"),rs.getString("room_type")));
        var groups = jdbc.query("SELECT DISTINCT student_group_code AS code,student_group_name AS name FROM schedule_assignment WHERE schedule_version_id=? ORDER BY student_group_code", (rs,row)->new ScheduleOptions.ScheduleOptionResource(rs.getString("code"),rs.getString("name")),versionId);
        var teachers = jdbc.query("SELECT DISTINCT teacher_code AS code,teacher_name AS name FROM schedule_assignment WHERE schedule_version_id=? ORDER BY teacher_code", (rs,row)->new ScheduleOptions.ScheduleOptionResource(rs.getString("code"),rs.getString("name")),versionId);
        return new ScheduleOptions(timeslots, rooms, groups, teachers);
    }
}
