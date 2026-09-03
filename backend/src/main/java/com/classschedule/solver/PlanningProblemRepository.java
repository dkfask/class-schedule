package com.classschedule.solver;

import com.classschedule.masterdata.AcademicTermResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PlanningProblemRepository {
    private final JdbcTemplate jdbc;
    private final AcademicTermResolver terms;

    public PlanningProblemRepository(JdbcTemplate jdbc, AcademicTermResolver terms) {
        this.jdbc = jdbc;
        this.terms = terms;
    }

    public PlanningProblem loadDefault() {
        return load(terms.resolve(null));
    }

    public PlanningProblem loadForVersion(long versionId) {
        String termCode =
                jdbc.queryForObject(
                        "SELECT t.code FROM schedule_version v JOIN schedule_scenario s ON s.id=v.scenario_id JOIN academic_term t ON t.id=s.term_id WHERE v.id=?",
                        String.class,
                        versionId);
        if (termCode == null || termCode.isBlank())
            throw new IllegalArgumentException("版本学期不存在: " + versionId);
        return load(termCode);
    }

    public PlanningProblem load(String termCode) {
        Long termId =
                jdbc.queryForObject(
                        "SELECT id FROM academic_term WHERE code=? AND status <> 'ARCHIVED'",
                        Long.class,
                        termCode);
        if (termId == null) throw new IllegalArgumentException("学期不存在或已归档: " + termCode);
        var timeslots =
                jdbc.query(
                        "SELECT code, weekday, period_no, label, continuity_group, break_after FROM period_template WHERE term_id=? ORDER BY weekday, period_no",
                        (rs, rowNum) -> {
                            Timeslot slot =
                                    new Timeslot(
                                            rs.getString("code"),
                                            rs.getInt("weekday"),
                                            rs.getInt("period_no"),
                                            rs.getString("label"));
                            slot.setContinuityGroup(rs.getString("continuity_group"));
                            slot.setBreakAfter(rs.getBoolean("break_after"));
                            return slot;
                        },
                        termId);
        List<RoomRow> roomRows =
                jdbc.query(
                        "SELECT id, code, name, capacity FROM room WHERE active=TRUE ORDER BY code",
                        (rs, rowNum) ->
                                new RoomRow(
                                        rs.getLong("id"),
                                        rs.getString("code"),
                                        rs.getString("name"),
                                        rs.getInt("capacity")));
        Map<Long, Set<String>> roomFeatures = new LinkedHashMap<>();
        jdbc.query(
                "SELECT rf.room_id, rf.feature_code FROM room_feature rf JOIN room r ON r.id=rf.room_id WHERE r.active=TRUE ORDER BY rf.room_id, rf.feature_code",
                (rs, rowNum) -> {
                    roomFeatures
                            .computeIfAbsent(
                                    rs.getLong("room_id"), ignored -> new LinkedHashSet<>())
                            .add(rs.getString("feature_code"));
                    return null;
                });
        Map<Long, Set<String>> roomUnavailablePeriods = new LinkedHashMap<>();
        jdbc.query(
                "SELECT room_id, period_code FROM room_availability WHERE term_id=? AND available=FALSE ORDER BY room_id, period_code",
                (rs, rowNum) -> {
                    roomUnavailablePeriods
                            .computeIfAbsent(
                                    rs.getLong("room_id"), ignored -> new LinkedHashSet<>())
                            .add(rs.getString("period_code"));
                    return null;
                },
                termId);
        var rooms =
                roomRows.stream()
                        .map(
                                row -> {
                                    Room room = new Room(row.code(), row.name(), row.capacity());
                                    room.setFeatures(roomFeatures.getOrDefault(row.id(), Set.of()));
                                    room.setUnavailablePeriodCodes(
                                            roomUnavailablePeriods.getOrDefault(
                                                    row.id(), Set.of()));
                                    return room;
                                })
                        .toList();
        List<ResourceAvailability> availabilities = new ArrayList<>();
        availabilities.addAll(
                jdbc.query(
                        "SELECT 'TEACHER' resource_type,t.code resource_code,a.period_code,a.available FROM teacher_availability a JOIN teacher t ON t.id=a.teacher_id WHERE a.term_id=?",
                        this::availability,
                        termId));
        availabilities.addAll(
                jdbc.query(
                        "SELECT 'ROOM' resource_type,r.code resource_code,a.period_code,a.available FROM room_availability a JOIN room r ON r.id=a.room_id WHERE a.term_id=?",
                        this::availability,
                        termId));
        availabilities.addAll(
                jdbc.query(
                        "SELECT 'STUDENT_GROUP' resource_type,g.code resource_code,a.period_code,a.available FROM student_group_availability a JOIN student_group g ON g.id=a.student_group_id WHERE a.term_id=?",
                        this::availability,
                        termId));
        Map<Long, Set<String>> requiredFeatures = new LinkedHashMap<>();
        jdbc.query(
                "SELECT f.teaching_requirement_id, f.feature_code FROM teaching_requirement_feature f JOIN teaching_requirement r ON r.id=f.teaching_requirement_id WHERE r.term_id=? AND r.active=TRUE ORDER BY f.teaching_requirement_id, f.feature_code",
                (rs, rowNum) -> {
                    requiredFeatures
                            .computeIfAbsent(
                                    rs.getLong("teaching_requirement_id"),
                                    ignored -> new LinkedHashSet<>())
                            .add(rs.getString("feature_code"));
                    return null;
                },
                termId);
        Map<String, Set<String>> unavailablePeriods = new LinkedHashMap<>();
        jdbc.query(
                "SELECT 'TEACHER' resource_type,t.code resource_code,a.period_code FROM teacher_availability a JOIN teacher t ON t.id=a.teacher_id WHERE a.term_id=? AND a.available=FALSE "
                        + "UNION ALL SELECT 'STUDENT_GROUP',g.code,a.period_code FROM student_group_availability a JOIN student_group g ON g.id=a.student_group_id WHERE a.term_id=? AND a.available=FALSE",
                (rs, rowNum) -> {
                    unavailablePeriods
                            .computeIfAbsent(
                                    rs.getString("resource_type")
                                            + "\u0000"
                                            + rs.getString("resource_code"),
                                    ignored -> new LinkedHashSet<>())
                            .add(rs.getString("period_code"));
                    return null;
                },
                termId,
                termId);
        var requirements =
                jdbc.query(
                        "SELECT r.id,r.code requirement_code,s.code subject_code,s.name subject_name,t.code teacher_code,t.name teacher_name,g.code group_code,g.name group_name,COALESCE(NULLIF(r.student_count,0),g.student_count,0) student_count,r.weekly_periods,r.duration_periods,r.pinned_period_code,ag.code activity_group_code,ag.activity_type,COALESCE(ag.member_index,-1) activity_member_index FROM teaching_requirement r JOIN subject s ON s.id=r.subject_id JOIN teacher t ON t.id=r.teacher_id JOIN student_group g ON g.id=r.student_group_id JOIN academic_term term ON term.id=r.term_id LEFT JOIN LATERAL (SELECT ag.code,ag.activity_type,agm.member_index FROM activity_group_member agm JOIN activity_group ag ON ag.id=agm.activity_group_id WHERE agm.teaching_requirement_id=r.id AND ag.term_id=term.id AND ag.active=TRUE LIMIT 1) ag ON TRUE WHERE term.code=? AND r.active=TRUE ORDER BY r.id",
                        (rs, rowNum) ->
                                new RequirementRow(
                                        rs.getLong("id"),
                                        rs.getString("requirement_code"),
                                        rs.getString("subject_code"),
                                        rs.getString("subject_name"),
                                        rs.getString("teacher_code"),
                                        rs.getString("teacher_name"),
                                        rs.getString("group_code"),
                                        rs.getString("group_name"),
                                        rs.getInt("student_count"),
                                        rs.getInt("weekly_periods"),
                                        rs.getInt("duration_periods"),
                                        rs.getString("pinned_period_code"),
                                        rs.getString("activity_group_code"),
                                        rs.getString("activity_type"),
                                        rs.getInt("activity_member_index")),
                        termCode);
        var nextPeriodCodes = PeriodContinuity.nextCodesFromTimeslots(timeslots);
        var occurrences =
                requirements.stream()
                        .flatMap(
                                row ->
                                        java.util.stream.IntStream.range(0, row.weeklyPeriods())
                                                .mapToObj(
                                                        index ->
                                                                createOccurrence(
                                                                        row,
                                                                        index,
                                                                        timeslots,
                                                                        nextPeriodCodes,
                                                                        requiredFeatures,
                                                                        unavailablePeriods)))
                        .toList();
        var rules =
                jdbc.query(
                        "SELECT i.rule_code, i.scope_type, i.scope_code, i.int_value, i.text_value, i.severity, i.weight FROM schedule_rule_instance i JOIN schedule_rule_profile p ON p.id=i.profile_id WHERE p.term_id=? AND i.active=TRUE AND p.active=TRUE",
                        (rs, rowNum) ->
                                new TypedScheduleRule(
                                        rs.getString("rule_code"),
                                        rs.getString("scope_type"),
                                        rs.getString("scope_code"),
                                        (Integer) rs.getObject("int_value"),
                                        rs.getString("text_value"),
                                        rs.getString("severity"),
                                        rs.getInt("weight")),
                        termId);
        if (timeslots.isEmpty() || rooms.isEmpty() || occurrences.isEmpty())
            throw new SolverDataNotReadyException("排课基础数据未就绪：需要当前学期节次、至少一间启用教室和至少一条启用教学需求");
        return new PlanningProblem(timeslots, rooms, occurrences, availabilities, rules);
    }

    private ResourceAvailability availability(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new ResourceAvailability(
                rs.getString("resource_type"),
                rs.getString("resource_code"),
                rs.getString("period_code"),
                rs.getBoolean("available"));
    }

    private LessonOccurrence createOccurrence(
            RequirementRow row,
            int index,
            List<Timeslot> timeslots,
            Map<String, String> nextPeriodCodes,
            Map<Long, Set<String>> requiredFeatures,
            Map<String, Set<String>> unavailablePeriods) {
        LessonOccurrence occurrence =
                new LessonOccurrence(
                        row.id() * 100 + index,
                        row.subjectCode(),
                        row.subjectName(),
                        row.teacherCode(),
                        row.teacherName(),
                        row.groupCode(),
                        row.groupName());
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
        occurrence.setRequiredFeatures(
                new LinkedHashSet<>(requiredFeatures.getOrDefault(row.id(), Set.of())));
        Set<String> unavailable =
                new LinkedHashSet<>(
                        unavailablePeriods.getOrDefault(
                                "TEACHER\u0000" + row.teacherCode(), Set.of()));
        unavailable.addAll(
                unavailablePeriods.getOrDefault("STUDENT_GROUP\u0000" + row.groupCode(), Set.of()));
        occurrence.setUnavailablePeriodCodes(unavailable);
        occurrence.setAvailablePeriodCodes(
                new LinkedHashSet<>(timeslots.stream().map(Timeslot::getId).toList()));
        occurrence.setBreakAfterPeriodCodes(
                new LinkedHashSet<>(
                        timeslots.stream()
                                .filter(Timeslot::isBreakAfter)
                                .map(Timeslot::getId)
                                .toList()));
        occurrence.setNextPeriodCodes(nextPeriodCodes);
        if (row.pinnedPeriodCode() != null && !row.pinnedPeriodCode().isBlank()) {
            Timeslot pinned =
                    timeslots.stream()
                            .filter(slot -> row.pinnedPeriodCode().equals(slot.getId()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "固定节次不存在: " + row.pinnedPeriodCode()));
            occurrence.setTimeslot(pinned);
            occurrence.setPinned(true);
        }
        return occurrence;
    }

    private record RoomRow(long id, String code, String name, int capacity) {}

    private record RequirementRow(
            long id,
            String requirementCode,
            String subjectCode,
            String subjectName,
            String teacherCode,
            String teacherName,
            String groupCode,
            String groupName,
            int studentCount,
            int weeklyPeriods,
            int durationPeriods,
            String pinnedPeriodCode,
            String activityGroupCode,
            String activityType,
            int activityMemberIndex) {}
}
