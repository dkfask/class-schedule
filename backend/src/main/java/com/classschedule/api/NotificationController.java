package com.classschedule.api;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private static final List<String> STATUSES = List.of("PENDING", "READ", "DONE");
    private final JdbcTemplate jdbc;

    public NotificationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) String status, Authentication authentication) {
        materialize(authentication.getName());
        String normalizedStatus = normalizeStatus(status);
        String filter = normalizedStatus == null ? "" : " AND n.status = ?";
        Object[] args = normalizedStatus == null
                ? new Object[] {authentication.getName()}
                : new Object[] {authentication.getName(), normalizedStatus};
        List<NotificationItem> items =
                jdbc.query(
                        "SELECT n.id,n.kind,n.title,n.message,n.status,n.aggregate_type,n.aggregate_id,n.term_code,n.mandatory,n.created_at,n.read_at,n.completed_at FROM app_notification n JOIN app_user u ON u.id=n.user_id WHERE u.username=?"
                                + filter
                                + " ORDER BY CASE n.status WHEN 'PENDING' THEN 1 WHEN 'READ' THEN 2 ELSE 3 END, n.created_at DESC LIMIT 100",
                        (rs, row) -> item(rs),
                        args);
        Integer pending =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM app_notification n JOIN app_user u ON u.id=n.user_id WHERE u.username=? AND n.status='PENDING'",
                        Integer.class,
                        authentication.getName());
        return Map.of("items", items, "count", items.size(), "pendingCount", pending == null ? 0 : pending);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable long id,
            @RequestBody(required = false) NotificationUpdateRequest request,
            Authentication authentication) {
        String status;
        try {
            status = normalizeStatus(request == null ? null : request.status());
            if (status == null) throw new IllegalArgumentException("通知状态不能为空");
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "INVALID_NOTIFICATION", "message", exception.getMessage()));
        }
        int updated =
                jdbc.update(
                        "UPDATE app_notification SET status=?, read_at=CASE WHEN ? IN ('READ','DONE') THEN COALESCE(read_at,CURRENT_TIMESTAMP) ELSE read_at END, completed_at=CASE WHEN ?='DONE' THEN COALESCE(completed_at,CURRENT_TIMESTAMP) ELSE NULL END WHERE id=? AND user_id=(SELECT id FROM app_user WHERE username=?)",
                        status,
                        status,
                        status,
                        id,
                        authentication.getName());
        if (updated != 1)
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("id", id, "status", status));
    }

    private void materialize(String username) {
        jdbc.update(
                """
                INSERT INTO app_notification(user_id,audit_event_id,kind,title,message,status,aggregate_type,aggregate_id,term_code,mandatory,created_at)
                SELECT u.id,e.id,
                       CASE WHEN e.action IN ('IMPORT_FAILED','SOLVE_FAILED','SOLVE_DEADLINE') THEN 'ACTION_REQUIRED' WHEN e.action='PUBLISH' THEN 'RELEASE' ELSE 'ACTIVITY' END,
                       CASE e.action
                           WHEN 'IMPORT_COMPLETED' THEN '数据导入完成'
                           WHEN 'IMPORT_FAILED' THEN '数据导入失败，需要处理'
                           WHEN 'SOLVE_COMPLETED' THEN '求解完成，可以检查候选版本'
                           WHEN 'SOLVE_FAILED' THEN '求解失败，需要处理'
                           WHEN 'SOLVE_DEADLINE' THEN '求解超时，需要重新提交'
                           WHEN 'SOLVE_CANCELLED' THEN '求解任务已取消'
                           WHEN 'PUBLISH' THEN '课表版本已发布'
                           WHEN 'PROBLEM_CREATED' THEN '新增问题反馈'
                           WHEN 'PROBLEM_UPDATED' THEN '问题记录已更新'
                           ELSE '课表工作流有新的进展'
                       END,
                       CASE e.action
                           WHEN 'IMPORT_COMPLETED' THEN '导入批次 #' || e.aggregate_id || ' 已完成，数据健康状态可以重新确认。'
                           WHEN 'IMPORT_FAILED' THEN '导入批次 #' || e.aggregate_id || ' 未通过检查，请打开问题中心查看证据。'
                           WHEN 'SOLVE_COMPLETED' THEN '求解任务 #' || e.aggregate_id || ' 已完成，候选版本等待发布检查。'
                           WHEN 'SOLVE_FAILED' THEN '求解任务 #' || e.aggregate_id || ' 执行失败，请根据错误码处理。'
                           WHEN 'SOLVE_DEADLINE' THEN '求解任务 #' || e.aggregate_id || ' 超过截止时间，请重新提交。'
                           WHEN 'SOLVE_CANCELLED' THEN '求解任务 #' || e.aggregate_id || ' 已取消，历史记录仍保留。'
                           WHEN 'PUBLISH' THEN '版本 v' || e.aggregate_id || ' 已发布，可以进入已发布课表查看。'
                           WHEN 'PROBLEM_CREATED' THEN '问题 #' || e.aggregate_id || ' 已进入待处理清单。'
                           WHEN 'PROBLEM_UPDATED' THEN '问题 #' || e.aggregate_id || ' 的处理状态已更新。'
                           ELSE e.action || ' 已记录。'
                       END,
                       'PENDING',e.aggregate_type,e.aggregate_id,
                       COALESCE(e.detail->>'termCode',ib.term_code,st.code,jt.code,pt.code),
                       e.action IN ('IMPORT_FAILED','SOLVE_FAILED','SOLVE_DEADLINE'),e.created_at
                FROM audit_event e
                JOIN app_user u ON u.username=? AND u.enabled=TRUE
                LEFT JOIN import_batch ib ON e.aggregate_type='IMPORT_BATCH' AND e.aggregate_id=ib.id::text
                LEFT JOIN schedule_version sv ON e.aggregate_type='SCHEDULE_VERSION' AND e.aggregate_id=sv.id::text
                LEFT JOIN schedule_scenario ss ON ss.id=sv.scenario_id
                LEFT JOIN academic_term st ON st.id=ss.term_id
                LEFT JOIN solve_job sj ON e.aggregate_type='SOLVE_JOB' AND e.aggregate_id=sj.id::text
                LEFT JOIN schedule_version jv ON jv.id=sj.schedule_version_id
                LEFT JOIN schedule_scenario js ON js.id=jv.scenario_id
                LEFT JOIN academic_term jt ON jt.id=js.term_id
                LEFT JOIN problem_record pr ON e.aggregate_type='PROBLEM_RECORD' AND e.aggregate_id=pr.id::text
                LEFT JOIN academic_term pt ON pt.id=pr.term_id
                WHERE e.action IN ('IMPORT_COMPLETED','IMPORT_FAILED','SOLVE_COMPLETED','SOLVE_FAILED','SOLVE_DEADLINE','SOLVE_CANCELLED','PUBLISH','PROBLEM_CREATED','PROBLEM_UPDATED')
                  AND (e.actor_user_id=u.id OR e.actor=u.username OR ib.created_by_user_id=u.id OR sj.submitted_by_user_id=u.id OR sv.owner_user_id=u.id OR pr.reported_by_user_id=u.id)
                  AND NOT EXISTS (SELECT 1 FROM app_notification existing WHERE existing.user_id=u.id AND existing.audit_event_id=e.id)
                """,
                username);
    }

    private String normalizeStatus(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase();
        if (!STATUSES.contains(normalized)) throw new IllegalArgumentException("不支持的通知状态: " + value);
        return normalized;
    }

    private NotificationItem item(ResultSet rs) throws SQLException {
        return new NotificationItem(
                rs.getLong("id"),
                rs.getString("kind"),
                rs.getString("title"),
                rs.getString("message"),
                rs.getString("status"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("term_code"),
                rs.getBoolean("mandatory"),
                rs.getObject("created_at", OffsetDateTime.class),
                nullableTime(rs, "read_at"),
                nullableTime(rs, "completed_at"));
    }

    private OffsetDateTime nullableTime(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }

    public record NotificationUpdateRequest(String status) {}

    public record NotificationItem(
            long id,
            String kind,
            String title,
            String message,
            String status,
            String aggregateType,
            String aggregateId,
            String termCode,
            boolean mandatory,
            OffsetDateTime createdAt,
            OffsetDateTime readAt,
            OffsetDateTime completedAt) {}
}
