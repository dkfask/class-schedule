package com.classschedule.api;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetrospectiveService {
    private final JdbcTemplate jdbc;

    public RetrospectiveService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> get(String termCode) {
        String term = required(termCode);
        requireTerm(term);
        Map<String, Object> metrics = metrics(term);
        Map<String, Object> notes = jdbc.query(
                        "SELECT r.rule_adaptation,r.legacy_issues,r.school_feedback,r.support_intervention_count,r.updated_at FROM term_retrospective r JOIN academic_term t ON t.id=r.term_id WHERE t.code=?",
                        (rs, row) -> {
                            Map<String, Object> value = new LinkedHashMap<>();
                            value.put("ruleAdaptation", rs.getString("rule_adaptation"));
                            value.put("legacyIssues", rs.getString("legacy_issues"));
                            value.put("schoolFeedback", rs.getString("school_feedback"));
                            value.put("supportInterventionCount", rs.getInt("support_intervention_count"));
                            value.put("updatedAt", rs.getObject("updated_at", OffsetDateTime.class));
                            return value;
                        },
                        term)
                .stream()
                .findFirst()
                .orElseGet(() -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("ruleAdaptation", "");
                    value.put("legacyIssues", "");
                    value.put("schoolFeedback", "");
                    value.put("supportInterventionCount", 0);
                    value.put("updatedAt", null);
                    return value;
                });
        return Map.of("termCode", term, "metrics", metrics, "notes", notes);
    }

    @Transactional
    public Map<String, Object> save(RetrospectiveRequest request, String actor) {
        String term = required(request.termCode());
        requireTerm(term);
        int support = request.supportInterventionCount() == null ? 0 : request.supportInterventionCount();
        if (support < 0) throw new IllegalArgumentException("支持介入次数不能为负数");
        jdbc.update(
                "INSERT INTO term_retrospective(term_id,rule_adaptation,legacy_issues,school_feedback,support_intervention_count,updated_by_user_id) VALUES((SELECT id FROM academic_term WHERE code=?),?,?,?,?,(SELECT id FROM app_user WHERE username=?)) ON CONFLICT(term_id) DO UPDATE SET rule_adaptation=EXCLUDED.rule_adaptation,legacy_issues=EXCLUDED.legacy_issues,school_feedback=EXCLUDED.school_feedback,support_intervention_count=EXCLUDED.support_intervention_count,updated_by_user_id=EXCLUDED.updated_by_user_id,updated_at=CURRENT_TIMESTAMP",
                term,
                blankToNull(request.ruleAdaptation()),
                blankToNull(request.legacyIssues()),
                blankToNull(request.schoolFeedback()),
                support,
                actor);
        jdbc.update(
                "INSERT INTO audit_event(action,aggregate_type,aggregate_id,actor,actor_user_id,actor_kind,detail) VALUES('RETROSPECTIVE_UPDATED','ACADEMIC_TERM',(SELECT id::text FROM academic_term WHERE code=?),?,(SELECT id FROM app_user WHERE username=?), 'USER',jsonb_build_object('termCode',?::text,'supportInterventionCount',?::int))",
                term,
                actor,
                actor,
                term,
                support);
        return get(term);
    }

    private Map<String, Object> metrics(String term) {
        String auditTerm = "COALESCE(e.detail->>'termCode',ib.term_code,st.code,jt.code,pt.code)";
        String joins =
                " FROM audit_event e LEFT JOIN import_batch ib ON e.aggregate_type='IMPORT_BATCH' AND e.aggregate_id=ib.id::text LEFT JOIN schedule_version sv ON e.aggregate_type='SCHEDULE_VERSION' AND e.aggregate_id=sv.id::text LEFT JOIN schedule_scenario ss ON ss.id=sv.scenario_id LEFT JOIN academic_term st ON st.id=ss.term_id LEFT JOIN solve_job sj ON e.aggregate_type='SOLVE_JOB' AND e.aggregate_id=sj.id::text LEFT JOIN schedule_version jv ON jv.id=sj.schedule_version_id LEFT JOIN schedule_scenario js ON js.id=jv.scenario_id LEFT JOIN academic_term jt ON jt.id=js.term_id LEFT JOIN problem_record pr ON e.aggregate_type='PROBLEM_RECORD' AND e.aggregate_id=pr.id::text LEFT JOIN academic_term pt ON pt.id=pr.term_id WHERE " + auditTerm + "=?";
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("importAttempts", scalar("SELECT COUNT(*)" + joins + " AND e.action IN ('IMPORT_VALIDATED','IMPORT_FAILED','IMPORT_COMPLETED')", term));
        metrics.put("importFailures", scalar("SELECT COUNT(*)" + joins + " AND e.action='IMPORT_FAILED'", term));
        metrics.put("manualAdjustments", scalar("SELECT COUNT(*)" + joins + " AND e.action IN ('ADJUSTMENT','ADJUSTMENT_EXCHANGE','ASSIGNMENT_LOCK','ASSIGNMENT_UNLOCK')", term));
        metrics.put("publishedVersions", scalar("SELECT COUNT(*)" + joins + " AND e.action='PUBLISH'", term));
        metrics.put("problemReports", scalar("SELECT COUNT(*)" + joins + " AND e.action='PROBLEM_CREATED'", term));
        metrics.put("supportInterventions", scalar("SELECT COUNT(*)" + joins + " AND e.action IN ('PROBLEM_CREATED','RETROSPECTIVE_UPDATED')", term));
        metrics.put("solveAttempts", scalar("SELECT COUNT(*) FROM solve_job j JOIN schedule_version v ON v.id=j.schedule_version_id JOIN schedule_scenario s ON s.id=v.scenario_id JOIN academic_term t ON t.id=s.term_id WHERE t.code=?", term));
        metrics.put("solveFailures", scalar("SELECT COUNT(*) FROM solve_job j JOIN schedule_version v ON v.id=j.schedule_version_id JOIN schedule_scenario s ON s.id=v.scenario_id JOIN academic_term t ON t.id=s.term_id WHERE t.code=? AND j.status='FAILED'", term));
        metrics.put("elapsedMinutes", elapsed(term));
        return metrics;
    }

    private Object elapsed(String term) {
        String expression = "COALESCE(e.detail->>'termCode',ib.term_code,st.code,jt.code,pt.code)";
        String joins =
                " FROM audit_event e LEFT JOIN import_batch ib ON e.aggregate_type='IMPORT_BATCH' AND e.aggregate_id=ib.id::text LEFT JOIN schedule_version sv ON e.aggregate_type='SCHEDULE_VERSION' AND e.aggregate_id=sv.id::text LEFT JOIN schedule_scenario ss ON ss.id=sv.scenario_id LEFT JOIN academic_term st ON st.id=ss.term_id LEFT JOIN solve_job sj ON e.aggregate_type='SOLVE_JOB' AND e.aggregate_id=sj.id::text LEFT JOIN schedule_version jv ON jv.id=sj.schedule_version_id LEFT JOIN schedule_scenario js ON js.id=jv.scenario_id LEFT JOIN academic_term jt ON jt.id=js.term_id LEFT JOIN problem_record pr ON e.aggregate_type='PROBLEM_RECORD' AND e.aggregate_id=pr.id::text LEFT JOIN academic_term pt ON pt.id=pr.term_id WHERE " + expression + "=?";
        return jdbc.queryForObject(
                "SELECT COALESCE(EXTRACT(EPOCH FROM (MAX(e.created_at) FILTER (WHERE e.action='PUBLISH') - MIN(e.created_at)) / 60),0)" + joins,
                Double.class,
                term);
    }

    private long scalar(String query, String term) {
        Number value = jdbc.queryForObject(query, Number.class, term);
        return value == null ? 0 : value.longValue();
    }

    private void requireTerm(String term) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM academic_term WHERE code=? AND status <> 'ARCHIVED'", Integer.class, term) == 0)
            throw new IllegalArgumentException("学期不存在或已归档: " + term);
    }

    private String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("学期不能为空");
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
