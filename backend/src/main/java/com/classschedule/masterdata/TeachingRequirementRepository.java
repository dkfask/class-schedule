package com.classschedule.masterdata;

import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TeachingRequirementRepository {
    private final JdbcTemplate jdbc;
    private final AcademicTermResolver terms;

    public TeachingRequirementRepository(JdbcTemplate jdbc, AcademicTermResolver terms) {
        this.jdbc = jdbc;
        this.terms = terms;
    }

    public List<TeachingRequirementItem> list(String termCode, boolean active) {
        return jdbc.query(
                "SELECT r.id,r.code,term.code AS term_code,g.code AS group_code,s.code AS subject_code,t.code AS teacher_code,r.weekly_periods,r.duration_periods,r.student_count,COALESCE((SELECT string_agg(feature_code, ',' ORDER BY feature_code) FROM teaching_requirement_feature rf WHERE rf.teaching_requirement_id=r.id),'') AS required_features,r.pinned_period_code,r.active FROM teaching_requirement r JOIN academic_term term ON term.id=r.term_id JOIN student_group g ON g.id=r.student_group_id JOIN subject s ON s.id=r.subject_id JOIN teacher t ON t.id=r.teacher_id WHERE term.code=? AND r.active=? ORDER BY r.code",
                (rs, row) -> item(rs),
                terms.resolve(termCode),
                active);
    }

    @Transactional
    public TeachingRequirementItem create(TeachingRequirementRequest request) {
        validateReferences(request);
        ensureFeatureCodes(request.requiredFeatures());
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM teaching_requirement WHERE code=?",
                        Integer.class,
                        request.code())
                > 0) throw new IllegalArgumentException("教学需求编码已存在: " + request.code());
        Long id =
                jdbc.queryForObject(
                        "INSERT INTO teaching_requirement(code,term_id,student_group_id,subject_id,teacher_id,weekly_periods,duration_periods,student_count,pinned_period_code) VALUES(?,(SELECT id FROM academic_term WHERE code=?),(SELECT id FROM student_group WHERE code=?),(SELECT id FROM subject WHERE code=?),(SELECT id FROM teacher WHERE code=?),?,?,?,?) RETURNING id",
                        Long.class,
                        request.code(),
                        request.termCode(),
                        request.studentGroupCode(),
                        request.subjectCode(),
                        request.teacherCode(),
                        request.weeklyPeriods(),
                        request.durationPeriods(),
                        request.studentCount(),
                        request.pinnedPeriodCode());
        replaceFeatures(id, request.requiredFeatures());
        jdbc.update(
                "INSERT INTO audit_event(action,aggregate_type,aggregate_id,detail) VALUES('CREATE','TEACHING_REQUIREMENT',?,jsonb_build_object('code',?))",
                String.valueOf(id),
                request.code());
        return get(id);
    }

    @Transactional
    public TeachingRequirementItem update(long id, TeachingRequirementRequest request) {
        get(id);
        validateReferences(request);
        ensureFeatureCodes(request.requiredFeatures());
        Integer duplicate =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM teaching_requirement WHERE code = ? AND id <> ?",
                        Integer.class,
                        request.code(),
                        id);
        if (duplicate != null && duplicate > 0)
            throw new IllegalArgumentException("教学需求编码已存在: " + request.code());
        jdbc.update(
                "UPDATE teaching_requirement SET code=?,term_id=(SELECT id FROM academic_term WHERE code=?),student_group_id=(SELECT id FROM student_group WHERE code=?),subject_id=(SELECT id FROM subject WHERE code=?),teacher_id=(SELECT id FROM teacher WHERE code=?),weekly_periods=?,duration_periods=?,student_count=?,pinned_period_code=? WHERE id=?",
                request.code(),
                request.termCode(),
                request.studentGroupCode(),
                request.subjectCode(),
                request.teacherCode(),
                request.weeklyPeriods(),
                request.durationPeriods(),
                request.studentCount(),
                request.pinnedPeriodCode(),
                id);
        replaceFeatures(id, request.requiredFeatures());
        jdbc.update(
                "INSERT INTO audit_event(action,aggregate_type,aggregate_id,detail) VALUES('UPDATE','TEACHING_REQUIREMENT',?,jsonb_build_object('code',?))",
                String.valueOf(id),
                request.code());
        return get(id);
    }

    public void deactivate(long id) {
        get(id);
        jdbc.update("UPDATE teaching_requirement SET active=FALSE WHERE id=?", id);
        jdbc.update(
                "INSERT INTO audit_event(action,aggregate_type,aggregate_id) VALUES('DEACTIVATE','TEACHING_REQUIREMENT',?)",
                String.valueOf(id));
    }

    public TeachingRequirementItem get(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT r.id,r.code,term.code AS term_code,g.code AS group_code,s.code AS subject_code,t.code AS teacher_code,r.weekly_periods,r.duration_periods,r.student_count,COALESCE((SELECT string_agg(feature_code, ',' ORDER BY feature_code) FROM teaching_requirement_feature rf WHERE rf.teaching_requirement_id=r.id),'') AS required_features,r.pinned_period_code,r.active FROM teaching_requirement r JOIN academic_term term ON term.id=r.term_id JOIN student_group g ON g.id=r.student_group_id JOIN subject s ON s.id=r.subject_id JOIN teacher t ON t.id=r.teacher_id WHERE r.id=?",
                    (rs, row) -> item(rs),
                    id);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("教学需求不存在: " + id);
        }
    }

    private TeachingRequirementItem item(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TeachingRequirementItem(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("term_code"),
                rs.getString("group_code"),
                rs.getString("subject_code"),
                rs.getString("teacher_code"),
                rs.getInt("weekly_periods"),
                rs.getInt("duration_periods"),
                rs.getInt("student_count"),
                rs.getString("required_features"),
                rs.getString("pinned_period_code"),
                rs.getBoolean("active"));
    }

    private void replaceFeatures(long id, String values) {
        jdbc.update("DELETE FROM teaching_requirement_feature WHERE teaching_requirement_id=?", id);
        if (values != null)
            for (String feature : values.split(","))
                if (!feature.isBlank())
                    jdbc.update(
                            "INSERT INTO teaching_requirement_feature(teaching_requirement_id,feature_code) VALUES(?,?) ON CONFLICT DO NOTHING",
                            id,
                            feature.trim());
    }

    private void ensureFeatureCodes(String values) {
        if (values == null || values.isBlank()) return;
        for (String feature : values.split(","))
            if (jdbc.queryForObject(
                            "SELECT COUNT(*) FROM room_feature_catalog WHERE code=? AND active=TRUE",
                            Integer.class,
                            feature.trim())
                    == 0) throw new IllegalArgumentException("教室特征不存在或已停用: " + feature.trim());
    }

    private void validateReferences(TeachingRequirementRequest request) {
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM academic_term WHERE code=? AND status <> 'ARCHIVED'",
                        Integer.class,
                        request.termCode())
                == 0) throw new IllegalArgumentException("学期不存在或已归档: " + request.termCode());
        require("student_group", request.studentGroupCode(), "班级");
        require("subject", request.subjectCode(), "课程");
        require("teacher", request.teacherCode(), "教师");
        if (request.pinnedPeriodCode() != null
                && !request.pinnedPeriodCode().isBlank()
                && jdbc.queryForObject(
                                "SELECT COUNT(*) FROM period_template p JOIN academic_term t ON t.id=p.term_id WHERE t.code=? AND p.code=?",
                                Integer.class,
                                request.termCode(),
                                request.pinnedPeriodCode())
                        == 0) throw new IllegalArgumentException("固定节次不属于目标学期");
    }

    private void require(String table, String code, String label) {
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE code=? AND active=TRUE",
                        Integer.class,
                        code)
                == 0) throw new IllegalArgumentException(label + "不存在或已停用: " + code);
    }
}
