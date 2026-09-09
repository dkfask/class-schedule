package com.classschedule.api;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private static final int PAGE_SIZE = 100;
    private final JdbcTemplate jdbc;

    public AuditController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> events(
            @RequestParam(required = false) String termCode,
            @RequestParam(required = false) Long versionId,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        StringBuilder sql = new StringBuilder(
                """
                SELECT e.id, e.action, e.aggregate_type, e.aggregate_id, e.actor,
                       e.actor_kind, e.outcome, e.correlation_id, e.detail::text AS detail,
                       e.created_at,
                       COALESCE(e.detail->>'termCode', ib.term_code, st.code, jt.code) AS term_code
                FROM audit_event e
                LEFT JOIN import_batch ib
                  ON e.aggregate_type = 'IMPORT_BATCH' AND e.aggregate_id = ib.id::text
                LEFT JOIN schedule_version sv
                  ON e.aggregate_type = 'SCHEDULE_VERSION' AND e.aggregate_id = sv.id::text
                LEFT JOIN schedule_scenario ss ON ss.id = sv.scenario_id
                LEFT JOIN academic_term st ON st.id = ss.term_id
                LEFT JOIN solve_job sj
                  ON e.aggregate_type = 'SOLVE_JOB' AND e.aggregate_id = sj.id::text
                LEFT JOIN schedule_version jv ON jv.id = sj.schedule_version_id
                LEFT JOIN schedule_scenario js ON js.id = jv.scenario_id
                LEFT JOIN academic_term jt ON jt.id = js.term_id
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        String termExpression = "COALESCE(e.detail->>'termCode', ib.term_code, st.code, jt.code)";
        if (termCode != null && !termCode.isBlank()) {
            sql.append(" AND ").append(termExpression).append(" = ?");
            args.add(termCode.trim());
        }
        if (versionId != null) {
            sql.append(" AND ((e.aggregate_type = 'SCHEDULE_VERSION' AND e.aggregate_id = ?)");
            sql.append(" OR (e.aggregate_type = 'SOLVE_JOB' AND sj.schedule_version_id = ?))");
            args.add(String.valueOf(versionId));
            args.add(versionId);
        }
        if (actor != null && !actor.isBlank()) {
            sql.append(" AND e.actor ILIKE ?");
            args.add("%" + actor.trim() + "%");
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND e.action = ?");
            args.add(action.trim());
        }
        if (from != null && !from.isBlank()) {
            sql.append(" AND e.created_at >= ?");
            args.add(LocalDate.parse(from).atStartOfDay().atOffset(ZoneOffset.UTC));
        }
        if (to != null && !to.isBlank()) {
            sql.append(" AND e.created_at < ?");
            args.add(LocalDate.parse(to).plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
        }
        sql.append(" ORDER BY e.created_at DESC, e.id DESC LIMIT ?");
        args.add(PAGE_SIZE);

        List<AuditEvent> events = jdbc.query(sql.toString(), (rs, rowNum) -> new AuditEvent(
                rs.getLong("id"),
                rs.getString("action"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("actor"),
                rs.getString("actor_kind"),
                rs.getString("outcome"),
                rs.getString("correlation_id"),
                rs.getString("detail"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getString("term_code")), args.toArray());
        return Map.of("items", events, "count", events.size(), "hasMore", events.size() == PAGE_SIZE);
    }

    public record AuditEvent(
            long id,
            String action,
            String aggregateType,
            String aggregateId,
            String actor,
            String actorKind,
            String outcome,
            String correlationId,
            String detail,
            OffsetDateTime createdAt,
            String termCode) {}
}
