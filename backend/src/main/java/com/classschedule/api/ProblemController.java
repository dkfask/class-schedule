package com.classschedule.api;

import jakarta.validation.Valid;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/problems")
public class ProblemController {
    private static final List<String> PRIORITIES = List.of("LOW", "MEDIUM", "HIGH", "URGENT");
    private static final List<String> CATEGORIES = List.of("DATA", "RULE", "PRODUCT", "OPERATION", "TO_CONFIRM");
    private static final List<String> STATUSES = List.of("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED");
    private final JdbcTemplate jdbc;

    public ProblemController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) String termCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String category) {
        StringBuilder sql = new StringBuilder(
                "SELECT p.id, t.code AS term_code, p.schedule_version_id, p.solve_job_id, p.import_batch_id, p.title, p.description, p.priority, p.category, p.status, p.evidence, p.resolution, reporter.username AS reporter, p.assignee, p.created_at, p.updated_at, p.resolved_at FROM problem_record p LEFT JOIN academic_term t ON t.id = p.term_id LEFT JOIN app_user reporter ON reporter.id = p.reported_by_user_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (termCode != null && !termCode.isBlank()) {
            sql.append(" AND t.code = ?");
            args.add(termCode.trim());
        }
        appendEnumFilter(sql, args, "p.status", status, STATUSES);
        appendEnumFilter(sql, args, "p.priority", priority, PRIORITIES);
        appendEnumFilter(sql, args, "p.category", category, CATEGORIES);
        sql.append(" ORDER BY CASE p.priority WHEN 'URGENT' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 4 END, p.created_at DESC LIMIT 200");
        List<ProblemRecord> items =
                jdbc.query(
                        sql.toString(),
                        (rs, rowNum) ->
                                new ProblemRecord(
                                        rs.getLong("id"),
                                        rs.getString("term_code"),
                                        nullableLong(rs, "schedule_version_id"),
                                        nullableLong(rs, "solve_job_id"),
                                        nullableLong(rs, "import_batch_id"),
                                        rs.getString("title"),
                                        rs.getString("description"),
                                        rs.getString("priority"),
                                        rs.getString("category"),
                                        rs.getString("status"),
                                        rs.getString("evidence"),
                                        rs.getString("resolution"),
                                        rs.getString("reporter"),
                                        rs.getString("assignee"),
                                        rs.getObject("created_at", OffsetDateTime.class),
                                        rs.getObject("updated_at", OffsetDateTime.class),
                                        rs.getObject("resolved_at", OffsetDateTime.class)),
                        args.toArray());
        return Map.of("items", items, "count", items.size());
    }

    @PostMapping
    public ResponseEntity<?> create(
            @Valid @RequestBody ProblemCreateRequest request, Authentication authentication) {
        try {
            String termCode = normalize(request.termCode());
            if (termCode != null)
                requireExists(
                        "SELECT COUNT(*) FROM academic_term WHERE code = ?",
                        termCode,
                        "学期不存在: " + termCode);
            if (request.versionId() != null)
                requireExists(
                        "SELECT COUNT(*) FROM schedule_version WHERE id = ?",
                        request.versionId(),
                        "版本不存在: " + request.versionId());
            if (request.solveJobId() != null)
                requireExists(
                        "SELECT COUNT(*) FROM solve_job WHERE id = ?",
                        request.solveJobId(),
                        "求解任务不存在: " + request.solveJobId());
            if (request.importBatchId() != null)
                requireExists(
                        "SELECT COUNT(*) FROM import_batch WHERE id = ?",
                        request.importBatchId(),
                        "导入批次不存在: " + request.importBatchId());
            String priority = enumValue(request.priority(), "MEDIUM", PRIORITIES, "优先级");
            String category = enumValue(request.category(), "TO_CONFIRM", CATEGORIES, "问题分类");
            Long id =
                    jdbc.queryForObject(
                            "INSERT INTO problem_record(term_id, schedule_version_id, solve_job_id, import_batch_id, title, description, priority, category, evidence, reported_by_user_id) VALUES ((SELECT id FROM academic_term WHERE code = ?), ?, ?, ?, ?, ?, ?, ?, ?, (SELECT id FROM app_user WHERE username = ?)) RETURNING id",
                            Long.class,
                            termCode,
                            request.versionId(),
                            request.solveJobId(),
                            request.importBatchId(),
                            request.title().trim(),
                            request.description().trim(),
                            priority,
                            category,
                            normalize(request.evidence()),
                            authentication.getName());
            jdbc.update(
                    "INSERT INTO audit_event(action, aggregate_type, aggregate_id, actor, detail) VALUES ('PROBLEM_CREATED', 'PROBLEM_RECORD', ?, ?, jsonb_build_object('title', ?, 'priority', ?, 'category', ?))",
                    String.valueOf(id),
                    authentication.getName(),
                    request.title().trim(),
                    priority,
                    category);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "status", "OPEN"));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "INVALID_PROBLEM", "message", exception.getMessage()));
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable long id,
            @Valid @RequestBody ProblemUpdateRequest request,
            Authentication authentication) {
        try {
            requireExists("SELECT COUNT(*) FROM problem_record WHERE id = ?", id, "问题不存在: " + id);
            String status = enumValue(request.status(), null, STATUSES, "问题状态");
            String category = enumValue(request.category(), null, CATEGORIES, "问题分类");
            String priority = enumValue(request.priority(), null, PRIORITIES, "优先级");
            int updated =
                    jdbc.update(
                            "UPDATE problem_record SET status = COALESCE(?, status), category = COALESCE(?, category), priority = COALESCE(?, priority), assignee = COALESCE(?, assignee), resolution = COALESCE(?, resolution), evidence = COALESCE(?, evidence), resolved_at = CASE WHEN ? IN ('RESOLVED', 'CLOSED') THEN COALESCE(resolved_at, CURRENT_TIMESTAMP) WHEN ? IS NOT NULL THEN NULL ELSE resolved_at END, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                            status,
                            category,
                            priority,
                            normalize(request.assignee()),
                            normalize(request.resolution()),
                            normalize(request.evidence()),
                            status,
                            status,
                            id);
            if (updated != 1) throw new IllegalArgumentException("问题不存在: " + id);
            jdbc.update(
                    "INSERT INTO audit_event(action, aggregate_type, aggregate_id, actor, detail) VALUES ('PROBLEM_UPDATED', 'PROBLEM_RECORD', ?, ?, jsonb_build_object('status', ?::text, 'category', ?::text, 'priority', ?::text))",
                    String.valueOf(id),
                    authentication.getName(),
                    status,
                    category,
                    priority);
            return ResponseEntity.ok(Map.of("id", id, "status", status == null ? "UNCHANGED" : status));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "INVALID_PROBLEM_UPDATE", "message", exception.getMessage()));
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidFilter(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "INVALID_PROBLEM_FILTER", "message", exception.getMessage()));
    }

    private void appendEnumFilter(
            StringBuilder sql, List<Object> args, String column, String value, List<String> allowed) {
        if (value == null || value.isBlank()) return;
        String normalized = value.trim().toUpperCase();
        if (!allowed.contains(normalized)) throw new IllegalArgumentException("不支持的筛选值: " + value);
        sql.append(" AND ").append(column).append(" = ?");
        args.add(normalized);
    }

    private String enumValue(String value, String fallback, List<String> allowed, String label) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim().toUpperCase();
        if (!allowed.contains(normalized)) throw new IllegalArgumentException(label + "不支持: " + value);
        return normalized;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void requireExists(String query, Object value, String message) {
        Integer count = jdbc.queryForObject(query, Integer.class, value);
        if (count == null || count == 0) throw new IllegalArgumentException(message);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record ProblemRecord(
            long id,
            String termCode,
            Long versionId,
            Long solveJobId,
            Long importBatchId,
            String title,
            String description,
            String priority,
            String category,
            String status,
            String evidence,
            String resolution,
            String reporter,
            String assignee,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime resolvedAt) {}
}
