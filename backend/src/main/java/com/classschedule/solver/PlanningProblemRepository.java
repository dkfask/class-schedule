package com.classschedule.solver;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PlanningProblemRepository {
    private final JdbcTemplate jdbc;

    public PlanningProblemRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public PlanningProblem loadDefault() { return load("2026-FALL"); }

    public PlanningProblem loadForVersion(long versionId) {
        String termCode = jdbc.queryForObject(
                "SELECT t.code FROM schedule_version v JOIN schedule_scenario s ON s.id=v.scenario_id JOIN academic_term t ON t.id=s.term_id WHERE v.id=?",
                String.class, versionId);
        if (termCode == null || termCode.isBlank()) throw new IllegalArgumentException("版本学期不存在: " + versionId);
        return load(termCode);
    }

    public PlanningProblem load(String termCode) {
        Long termId = jdbc.queryForObject("SELECT id FROM academic_term WHERE code=? AND status <> 'ARCHIVED'", Long.class, termCode);
        if (termId == null) throw new IllegalArgumentException("学期不存在或已归档: " + termCode);
        var timeslots = jdbc.query(
                "SELECT code, weekday, period_no, label, continuity_group, break_after FROM period_template WHERE term_id=? ORDER BY weekday, period_no",
                (rs, rowNum) -> {
                    Timeslot slot = new Timeslot(rs.getString("code"), rs.getInt("weekday"), rs.getInt("period_no"), rs.getString("label"));
                    slot.setContinuityGroup(rs.getString("continuity_group"));
                    slot.setBreakAfter(rs.getBoolean("break_after"));
                    return slot;
                }, termId);
        var rooms = jdbc.query("SELECT id, code, name, capacity FROM room WHERE active=TRUE ORDER BY code", (rs, rowNum) -> {
            Room room = new Room(rs.getString("code"), rs.getString("name"), rs.getInt("capacity"));
            long roomId = rs.getLong("id");
            room.setFeatures(new LinkedHashSet<>(jdbc.query("SELECT feature_code FROM room_feature WHERE room_id=? ORDER BY feature_code", (r, n) -> r.getString("feature_code"), roomId)));
            room.setUnavailablePeriodCodes(new LinkedHashSet<>(jdbc.query("SELECT period_code FROM room_availability WHERE room_id=? AND term_id=? AND available=FALSE", (r, n) -> r.getString("period_code"), roomId, termId)));
            return room;
        });
        List<ResourceAvailability> availabilities = new ArrayList<>();
        availabilities.addAll(jdbc.query("SELECT 'TEACHER' resource_type,t.code resource_code,a.period_code,a.available FROM teacher_availability a JOIN teacher t ON t.id=a.teacher_id WHERE a.term_id=?", this::availability, termId));
        availabilities.addAll(jdbc.query("SELECT 'ROOM' resource_type,r.code resource_code,a.period_code,a.available FROM room_availability a JOIN room r ON r.id=a.room_id WHERE a.term_id=?", this::availability, termId));
        availabilities.addAll(jdbc.query("SELECT 'STUDENT_GROUP' resource_type,g.code resource_code,a.period_code,a.available FROM student_group_availability a JOIN student_group g ON g.id=a.student_group_id WHERE a.term_id=?", this::availability, termId));
        var requirements = jdbc.query(
                "SELECT r.id,r.code requirement_code,s.code subject_code,s.name subject_name,t.code teacher_code,t.name teacher_name,g.code group_code,g.name group_name,COALESCE(NULLIF(r.student_count,0),g.student_count,0) student_count,r.weekly_periods,r.duration_periods,r.pinned_period_code,ag.code activity_group_code,ag.activity_type,COALESCE(ag.member_index,-1) activity_member_index FROM teaching_requirement r JOIN subject s ON s.id=r.subject_id JOIN teacher t ON t.id=r.teacher_id JOIN student_group g ON g.id=r.student_group_id JOIN academic_term term ON term.id=r.term_id LEFT JOIN LATERAL (SELECT ag.code,ag.activity_type,agm.member_index FROM activity_group_member agm JOIN activity_group ag ON ag.id=agm.activity_group_id WHERE agm.teaching_requirement_id=r.id AND ag.term_id=term.id AND ag.active=TRUE LIMIT 1) ag ON TRUE WHERE term.code=? AND r.active=TRUE ORDER BY r.id",
                (rs, rowNum) -> new RequirementRow(rs.getLong("id"), rs.getString("requirement_code"), rs.getString("subject_code"), rs.getString("subject_name"), rs.getString("teacher_code"), rs.getString("teacher_name"), rs.getString("group_code"), rs.getString("group_name"), rs.getInt("student_count"), rs.getInt("weekly_periods"), rs.getInt("duration_periods"), rs.getString("pinned_period_code"), rs.getString("activity_group_code"), rs.getString("activity_type"), rs.getInt("activity_member_index")), termCode);
        var nextPeriodCodes = PeriodContinuity.nextCodesFromTimeslots(timeslots);
        var occurrences = requirements.stream().flatMap(row -> java.util.stream.IntStream.range(0, row.weeklyPeriods()).mapToObj(index -> createOccurrence(row, index, timeslots, termId, nextPeriodCodes))).toList();
        var rules = jdbc.query("SELECT i.rule_code, i.scope_type, i.scope_code, i.int_value, i.text_value, i.severity, i.weight FROM schedule_rule_instance i JOIN schedule_rule_profile p ON p.id=i.profile_id WHERE p.term_id=? AND i.active=TRUE AND p.active=TRUE",
                (rs, rowNum) -> new TypedScheduleRule(rs.getString("rule_code"), rs.getString("scope_type"), rs.getString("scope_code"),
                        (Integer) rs.getObject("int_value"), rs.getString("text_value"), rs.getString("severity"), rs.getInt("weight")), termId);
        if (timeslots.isEmpty() || rooms.isEmpty() || occurrences.isEmpty()) throw new SolverDataNotReadyException("排课基础数据未就绪：需要当前学期节次、至少一间启用教室和至少一条启用教学需求");
        return new PlanningProblem(timeslots, rooms, occurrences, availabilities, rules);
    }

    private ResourceAvailability availability(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ResourceAvailability(rs.getString("resource_type"), rs.getString("resource_code"), rs.getString("period_code"), rs.getBoolean("available"));
    }

    private LessonOccurrence createOccurrence(RequirementRow row, int index, List<Timeslot> timeslots, long termId, java.util.Map<String, String> nextPeriodCodes) {
        LessonOccurrence occurrence = new LessonOccurrence(row.id() * 100 + index, row.subjectCode(), row.subjectName(), row.teacherCode(), row.teacherName(), row.groupCode(), row.groupName());
        occurrence.setTeachingRequirementId(row.id());
        occurrence.setRequirementCode(row.requirementCode());
        occurrence.setDuration(row.durationPeriods());
        occurrence.setStudentCount(row.studentCount());
        occurrence.setOccurrenceKey(row.id() + "-" + index);
        occurrence.setActivityIndex(index);
        occurrence.setActivityGroupCode(row.activityGroupCode());
        occurrence.setActivityType(row.activityType());
        occurrence.setActivityMemberIndex(row.activityMemberIndex());
        occurrence.setPinnedPeriodCode(row.pinnedPeriodCode());
        occurrence.setRequiredFeatures(new LinkedHashSet<>(jdbc.query("SELECT feature_code FROM teaching_requirement_feature WHERE teaching_requirement_id=? ORDER BY feature_code", (rs, n) -> rs.getString("feature_code"), row.id())));
        occurrence.setUnavailablePeriodCodes(new LinkedHashSet<>(jdbc.query("SELECT period_code FROM teacher_availability a JOIN teacher t ON t.id=a.teacher_id WHERE t.code=? AND a.term_id=? AND a.available=FALSE UNION SELECT period_code FROM student_group_availability a JOIN student_group g ON g.id=a.student_group_id WHERE g.code=? AND a.term_id=? AND a.available=FALSE", (rs, n) -> rs.getString("period_code"), row.teacherCode(), termId, row.groupCode(), termId)));
        occurrence.setAvailablePeriodCodes(new LinkedHashSet<>(timeslots.stream().map(Timeslot::getId).toList()));
        occurrence.setBreakAfterPeriodCodes(new LinkedHashSet<>(timeslots.stream().filter(Timeslot::isBreakAfter).map(Timeslot::getId).toList()));
        occurrence.setNextPeriodCodes(nextPeriodCodes);
        if (row.pinnedPeriodCode() != null && !row.pinnedPeriodCode().isBlank()) {
            Timeslot pinned = timeslots.stream().filter(slot -> row.pinnedPeriodCode().equals(slot.getId())).findFirst().orElseThrow(() -> new IllegalStateException("固定节次不存在: " + row.pinnedPeriodCode()));
            occurrence.setTimeslot(pinned);
            occurrence.setPinned(true);
        }
        return occurrence;
    }

    private record RequirementRow(long id, String requirementCode, String subjectCode, String subjectName, String teacherCode, String teacherName, String groupCode, String groupName, int studentCount, int weeklyPeriods, int durationPeriods, String pinnedPeriodCode, String activityGroupCode, String activityType, int activityMemberIndex) {}
}
