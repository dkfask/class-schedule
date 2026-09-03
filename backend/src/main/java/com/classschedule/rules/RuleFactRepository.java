package com.classschedule.rules;

import com.classschedule.api.ActivityGroupRequest;
import com.classschedule.api.AvailabilityRequest;
import com.classschedule.api.RequirementFeatureRequest;
import com.classschedule.api.RoomFeatureRequest;
import com.classschedule.masterdata.AcademicTermResolver;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RuleFactRepository {
    private final JdbcTemplate jdbc;
    private final AcademicTermResolver terms;

    public RuleFactRepository(JdbcTemplate jdbc, AcademicTermResolver terms) {
        this.jdbc = jdbc;
        this.terms = terms;
    }

    @Transactional
    public void upsertAvailability(String resourceType, AvailabilityRequest request) {
        String table;
        String resourceColumn;
        String resourceTable;
        switch (resourceType.toUpperCase()) {
            case "TEACHER" -> {
                table = "teacher_availability";
                resourceColumn = "teacher_id";
                resourceTable = "teacher";
            }
            case "ROOM" -> {
                table = "room_availability";
                resourceColumn = "room_id";
                resourceTable = "room";
            }
            case "STUDENT_GROUP" -> {
                table = "student_group_availability";
                resourceColumn = "student_group_id";
                resourceTable = "student_group";
            }
            default -> throw new IllegalArgumentException("不支持的资源可用性类型: " + resourceType);
        }
        requireActive(resourceTable, request.resourceCode());
        requireTermPeriod(request.termCode(), request.periodCode());
        jdbc.update(
                "INSERT INTO "
                        + table
                        + " ("
                        + resourceColumn
                        + ", term_id, period_code, available) VALUES ((SELECT id FROM "
                        + resourceTable
                        + " WHERE code=?), (SELECT id FROM academic_term WHERE code=?), ?, ?) ON CONFLICT ("
                        + resourceColumn
                        + ", term_id, period_code) DO UPDATE SET available=EXCLUDED.available",
                request.resourceCode(),
                request.termCode(),
                request.periodCode(),
                request.available());
        audit(
                "AVAILABILITY_UPSERT",
                resourceType + ":" + request.resourceCode() + ":" + request.periodCode());
    }

    @Transactional
    public void upsertRoomFeature(RoomFeatureRequest request) {
        requireActive("room", request.roomCode());
        jdbc.update(
                "INSERT INTO room_feature_catalog(code,name) VALUES(?,?) ON CONFLICT(code) DO UPDATE SET name=EXCLUDED.name, active=TRUE",
                request.featureCode(),
                request.featureName() == null || request.featureName().isBlank()
                        ? request.featureCode()
                        : request.featureName());
        jdbc.update(
                "INSERT INTO room_feature(room_id,feature_code) VALUES((SELECT id FROM room WHERE code=?),?) ON CONFLICT DO NOTHING",
                request.roomCode(),
                request.featureCode());
        audit("ROOM_FEATURE_UPSERT", request.roomCode() + ":" + request.featureCode());
    }

    @Transactional
    public void addRequirementFeature(RequirementFeatureRequest request) {
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM teaching_requirement WHERE code=? AND active=TRUE",
                        Integer.class,
                        request.requirementCode())
                == 0) throw new IllegalArgumentException("教学需求不存在: " + request.requirementCode());
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM room_feature_catalog WHERE code=? AND active=TRUE",
                        Integer.class,
                        request.featureCode())
                == 0) throw new IllegalArgumentException("教室特征不存在或已停用: " + request.featureCode());
        jdbc.update(
                "INSERT INTO teaching_requirement_feature(teaching_requirement_id,feature_code) VALUES((SELECT id FROM teaching_requirement WHERE code=?),?) ON CONFLICT DO NOTHING",
                request.requirementCode(),
                request.featureCode());
        audit(
                "REQUIREMENT_FEATURE_UPSERT",
                request.requirementCode() + ":" + request.featureCode());
    }

    @Transactional
    public void upsertActivityGroup(ActivityGroupRequest request) {
        if (!List.of("JOINED", "SYNCHRONIZED", "CONSECUTIVE").contains(request.activityType()))
            throw new IllegalArgumentException("不支持的活动组类型: " + request.activityType());
        String termCode = terms.resolve(request.normalizedTermCode());
        Long termId =
                jdbc
                        .query(
                                "SELECT id FROM academic_term WHERE code=? AND status <> 'ARCHIVED'",
                                (rs, row) -> rs.getLong("id"),
                                termCode)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("学期不存在或已归档: " + termCode));
        if (new java.util.HashSet<>(request.requirementCodes()).size()
                != request.requirementCodes().size())
            throw new IllegalArgumentException("活动组成员不能重复");
        for (String requirementCode : request.requirementCodes()) {
            if (jdbc.queryForObject(
                            "SELECT COUNT(*) FROM teaching_requirement r JOIN academic_term t ON t.id=r.term_id WHERE r.code=? AND r.active=TRUE AND t.id=?",
                            Integer.class,
                            requirementCode,
                            termId)
                    == 0) throw new IllegalArgumentException("教学需求不存在或不属于目标学期: " + requirementCode);
            if (jdbc.queryForObject(
                            "SELECT COUNT(*) FROM activity_group_member m JOIN activity_group g ON g.id=m.activity_group_id WHERE m.teaching_requirement_id=(SELECT id FROM teaching_requirement WHERE code=?) AND NOT (g.term_id=? AND g.code=?)",
                            Integer.class,
                            requirementCode,
                            termId,
                            request.code())
                    > 0) throw new IllegalArgumentException("教学需求已属于其他活动组: " + requirementCode);
        }
        jdbc.update(
                "INSERT INTO activity_group(code,name,activity_type,term_id,active) VALUES(?,?,?,?,TRUE) ON CONFLICT(term_id,code) DO UPDATE SET name=EXCLUDED.name, activity_type=EXCLUDED.activity_type, active=TRUE",
                request.code(),
                request.name(),
                request.activityType(),
                termId);
        Long groupId =
                jdbc.queryForObject(
                        "SELECT id FROM activity_group WHERE term_id=? AND code=?",
                        Long.class,
                        termId,
                        request.code());
        jdbc.update("DELETE FROM activity_group_member WHERE activity_group_id=?", groupId);
        int memberIndex = 0;
        for (String requirementCode : request.requirementCodes()) {
            jdbc.update(
                    "INSERT INTO activity_group_member(activity_group_id,teaching_requirement_id,member_index) VALUES(?,(SELECT id FROM teaching_requirement WHERE code=?),?)",
                    groupId,
                    requirementCode,
                    memberIndex++);
        }
        audit("ACTIVITY_GROUP_UPSERT", request.code());
    }

    public record AvailabilityItem(
            String resourceType, String resourceCode, String periodCode, boolean available) {}

    public void deleteAvailability(String resourceType, AvailabilityRequest request) {
        String table;
        String resourceColumn;
        String resourceTable;
        switch (resourceType.toUpperCase()) {
            case "TEACHER" -> {
                table = "teacher_availability";
                resourceColumn = "teacher_id";
                resourceTable = "teacher";
            }
            case "ROOM" -> {
                table = "room_availability";
                resourceColumn = "room_id";
                resourceTable = "room";
            }
            case "STUDENT_GROUP" -> {
                table = "student_group_availability";
                resourceColumn = "student_group_id";
                resourceTable = "student_group";
            }
            default -> throw new IllegalArgumentException("不支持的资源可用性类型: " + resourceType);
        }
        jdbc.update(
                "DELETE FROM "
                        + table
                        + " WHERE "
                        + resourceColumn
                        + " = (SELECT id FROM "
                        + resourceTable
                        + " WHERE code = ?) AND term_id = (SELECT id FROM academic_term WHERE code = ?) AND period_code = ?",
                request.resourceCode(),
                request.termCode(),
                request.periodCode());
        audit(
                "AVAILABILITY_DELETE",
                resourceType + ":" + request.resourceCode() + ":" + request.periodCode());
    }

    public record FeatureItem(String code, String name, boolean active) {}

    public record RoomFeatureItem(String roomCode, String featureCode, String featureName) {}

    public record RequirementFeatureItem(String requirementCode, String featureCode) {}

    public record ActivityGroupItem(
            String code, String name, String activityType, List<String> requirementCodes) {}

    public List<FeatureItem> listFeatureCatalog() {
        return jdbc.query(
                "SELECT code, name, active FROM room_feature_catalog ORDER BY code",
                (rs, row) ->
                        new FeatureItem(
                                rs.getString("code"),
                                rs.getString("name"),
                                rs.getBoolean("active")));
    }

    public List<RoomFeatureItem> listRoomFeatures(String roomCode) {
        String sql =
                "SELECT r.code AS room_code, rf.feature_code, c.name FROM room_feature rf JOIN room r ON r.id=rf.room_id JOIN room_feature_catalog c ON c.code=rf.feature_code ORDER BY r.code, rf.feature_code";
        if (roomCode == null || roomCode.isBlank()) {
            return jdbc.query(
                    sql,
                    (rs, row) ->
                            new RoomFeatureItem(
                                    rs.getString("room_code"),
                                    rs.getString("feature_code"),
                                    rs.getString("name")));
        }
        return jdbc.query(
                sql.replace(" ORDER BY", " WHERE r.code=? ORDER BY"),
                (rs, row) ->
                        new RoomFeatureItem(
                                rs.getString("room_code"),
                                rs.getString("feature_code"),
                                rs.getString("name")),
                roomCode);
    }

    public void deleteRoomFeature(String roomCode, String featureCode) {
        jdbc.update(
                "DELETE FROM room_feature WHERE room_id=(SELECT id FROM room WHERE code=?) AND feature_code=?",
                roomCode,
                featureCode);
    }

    public List<RequirementFeatureItem> listRequirementFeatures(String requirementCode) {
        String sql =
                "SELECT r.code, f.feature_code FROM teaching_requirement_feature f JOIN teaching_requirement r ON r.id=f.teaching_requirement_id ORDER BY r.code, f.feature_code";
        if (requirementCode == null || requirementCode.isBlank()) {
            return jdbc.query(
                    sql,
                    (rs, row) ->
                            new RequirementFeatureItem(
                                    rs.getString("code"), rs.getString("feature_code")));
        }
        return jdbc.query(
                sql.replace(" ORDER BY", " WHERE r.code=? ORDER BY"),
                (rs, row) ->
                        new RequirementFeatureItem(
                                rs.getString("code"), rs.getString("feature_code")),
                requirementCode);
    }

    public void deleteRequirementFeature(String requirementCode, String featureCode) {
        jdbc.update(
                "DELETE FROM teaching_requirement_feature WHERE teaching_requirement_id=(SELECT id FROM teaching_requirement WHERE code=?) AND feature_code=?",
                requirementCode,
                featureCode);
    }

    public List<ActivityGroupItem> listActivityGroups(String termCode) {
        return jdbc.query(
                "SELECT a.code, a.name, a.activity_type, COALESCE(array_agg(r.code ORDER BY m.member_index) FILTER (WHERE r.code IS NOT NULL), ARRAY[]::varchar[]) AS requirement_codes FROM activity_group a LEFT JOIN activity_group_member m ON m.activity_group_id=a.id LEFT JOIN teaching_requirement r ON r.id=m.teaching_requirement_id WHERE a.active=TRUE AND a.term_id=(SELECT id FROM academic_term WHERE code=?) GROUP BY a.id, a.code, a.name, a.activity_type ORDER BY a.code",
                (rs, row) ->
                        new ActivityGroupItem(
                                rs.getString("code"),
                                rs.getString("name"),
                                rs.getString("activity_type"),
                                List.of((String[]) rs.getArray("requirement_codes").getArray())),
                terms.resolve(termCode));
    }

    public List<ActivityGroupItem> listActivityGroups() {
        return listActivityGroups(terms.resolve(null));
    }

    @Transactional
    public void deleteActivityGroup(String code, String termCode) {
        int updated =
                jdbc.update(
                        "UPDATE activity_group SET active=FALSE WHERE code=? AND term_id=(SELECT id FROM academic_term WHERE code=?) AND active=TRUE",
                        code,
                        terms.resolve(termCode));
        if (updated == 0) throw new IllegalArgumentException("活动组不存在: " + code);
    }

    public List<AvailabilityItem> listAvailability(String termCode) {
        Long termId =
                jdbc
                        .query(
                                "SELECT id FROM academic_term WHERE code=?",
                                (rs, row) -> rs.getLong("id"),
                                terms.resolve(termCode))
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("学期不存在: " + termCode));
        List<AvailabilityItem> items = new java.util.ArrayList<>();
        items.addAll(
                jdbc.query(
                        "SELECT r.code, a.period_code, a.available FROM teacher_availability a JOIN teacher r ON r.id=a.teacher_id WHERE a.term_id=? ORDER BY r.code, a.period_code",
                        (rs, row) ->
                                new AvailabilityItem(
                                        "TEACHER",
                                        rs.getString("code"),
                                        rs.getString("period_code"),
                                        rs.getBoolean("available")),
                        termId));
        items.addAll(
                jdbc.query(
                        "SELECT r.code, a.period_code, a.available FROM room_availability a JOIN room r ON r.id=a.room_id WHERE a.term_id=? ORDER BY r.code, a.period_code",
                        (rs, row) ->
                                new AvailabilityItem(
                                        "ROOM",
                                        rs.getString("code"),
                                        rs.getString("period_code"),
                                        rs.getBoolean("available")),
                        termId));
        items.addAll(
                jdbc.query(
                        "SELECT r.code, a.period_code, a.available FROM student_group_availability a JOIN student_group r ON r.id=a.student_group_id WHERE a.term_id=? ORDER BY r.code, a.period_code",
                        (rs, row) ->
                                new AvailabilityItem(
                                        "STUDENT_GROUP",
                                        rs.getString("code"),
                                        rs.getString("period_code"),
                                        rs.getBoolean("available")),
                        termId));
        return items;
    }

    private void audit(String action, String detail) {
        jdbc.update(
                "INSERT INTO audit_event(action,aggregate_type,aggregate_id,actor,actor_user_id,actor_kind,outcome,detail) VALUES(?, 'RULE_FACT', ?, 'system', (SELECT id FROM app_user WHERE username='system'), 'SERVICE', 'SUCCESS', jsonb_build_object('detail', ?))",
                action,
                detail,
                detail);
    }

    private void requireActive(String table, String code) {
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE code=? AND active=TRUE",
                        Integer.class,
                        code)
                == 0) throw new IllegalArgumentException("资源不存在或已停用: " + code);
    }

    private void requireTermPeriod(String termCode, String periodCode) {
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM period_template p JOIN academic_term t ON t.id=p.term_id WHERE t.code=? AND p.code=?",
                        Integer.class,
                        termCode,
                        periodCode)
                == 0) throw new IllegalArgumentException("节次不属于目标学期: " + periodCode);
    }
}
