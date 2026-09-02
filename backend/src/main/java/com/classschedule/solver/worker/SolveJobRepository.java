package com.classschedule.solver.worker;

import com.classschedule.schedule.ScheduleSnapshotHashService;
import com.classschedule.masterdata.AcademicTermResolver;
import com.classschedule.solver.SolveReadiness;
import com.classschedule.solver.SolveReadinessException;
import com.classschedule.solver.SolveReadinessService;
import com.classschedule.solver.Timetable;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SolveJobRepository {
    private final JdbcTemplate jdbc;
    private final ScheduleSnapshotHashService snapshots;
    private final SolveReadinessService readiness;
    private final AcademicTermResolver terms;

    public SolveJobRepository(JdbcTemplate jdbc, ScheduleSnapshotHashService snapshots, SolveReadinessService readiness, AcademicTermResolver terms) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.readiness = readiness;
        this.terms = terms;
    }

    @Transactional
    public SolveJobHandle enqueue(String idempotencyKey) {
        return enqueue(idempotencyKey, null, null);
    }

    @Transactional
    public SolveJobHandle enqueue(String idempotencyKey, String submittedByUsername) {
        return enqueue(idempotencyKey, submittedByUsername, null);
    }

    @Transactional
    public SolveJobHandle enqueue(String idempotencyKey, String submittedByUsername, String termCode) {
        String owner = normalizedOwner(submittedByUsername);
        requireKnownUser(owner);
        String normalizedTermCode = terms.resolve(termCode);
        SolveReadiness checked = readiness.check(normalizedTermCode);
        if (!checked.ready()) throw new SolveReadinessException(checked);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            lockIdempotencyKey(idempotencyKey, owner);
            String ownerClause = " AND (u.username = CAST(? AS varchar) OR CAST(? AS varchar) = 'system')";
            Object[] ownerArgs = new Object[]{owner, owner};
            List<Object> params = new java.util.ArrayList<>();
            params.add(idempotencyKey);
            params.addAll(java.util.Arrays.asList(ownerArgs));
            List<SolveJobHandle> existing = jdbc.query(
                "SELECT j.id, j.schedule_version_id, j.status FROM solve_job j LEFT JOIN app_user u ON u.id = j.submitted_by_user_id WHERE j.idempotency_key = ? AND j.status IN ('QUEUED','RUNNING')" + ownerClause,
                    (rs, rowNum) -> new SolveJobHandle(rs.getLong("id"), rs.getLong("schedule_version_id"), rs.getString("status")), params.toArray());
            if (!existing.isEmpty()) return existing.get(0);
        }
        Long scenarioId = jdbc.queryForObject(
                "INSERT INTO schedule_scenario (term_id, name, status, owner_user_id) SELECT id, '持久化求解场景', 'DRAFT', (SELECT id FROM app_user WHERE username = ?) FROM academic_term WHERE code = ? RETURNING id",
                Long.class, owner, normalizedTermCode);
        ScheduleSnapshotHashService.Snapshot snapshot = snapshots.snapshot(normalizedTermCode);
        Long versionId = jdbc.queryForObject(
                "INSERT INTO schedule_version (scenario_id, owner_user_id, status, solver_version, random_seed, snapshot_term_code, input_snapshot_hash, rule_snapshot_hash, input_snapshot_at) VALUES (?, (SELECT owner_user_id FROM schedule_scenario WHERE id = ?), 'SOLVING', 'timefold-1.17.0', 0, ?, ?, ?, CURRENT_TIMESTAMP) RETURNING id",
                Long.class, scenarioId, scenarioId, snapshot.termCode(), snapshot.inputHash(), snapshot.ruleHash());
        Long jobId = jdbc.queryForObject(
                "INSERT INTO solve_job (schedule_version_id, status, idempotency_key, next_attempt_at, deadline_at, submitted_by_user_id) VALUES (?, 'QUEUED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '15 minutes', (SELECT id FROM app_user WHERE username = ?)) RETURNING id",
                Long.class, versionId,
                idempotencyKey == null || idempotencyKey.isBlank() ? UUID.randomUUID().toString() : idempotencyKey,
                owner);
        return new SolveJobHandle(jobId, versionId, "QUEUED");
    }

    private void lockIdempotencyKey(String idempotencyKey, String submittedByUsername) {
        String owner = submittedByUsername == null ? "__legacy__" : submittedByUsername;
        jdbc.queryForRowSet(
                "SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))",
                owner.length() + ":" + owner + ":" + idempotencyKey).next();
    }


    @Transactional
    public int recoverExpired() {
        int reclaimed = jdbc.update("UPDATE solve_job SET status = 'QUEUED', worker_id = NULL, lease_until = NULL, heartbeat_at = NULL, next_attempt_at = CURRENT_TIMESTAMP WHERE status = 'RUNNING' AND lease_until IS NOT NULL AND lease_until < CURRENT_TIMESTAMP AND cancel_requested = FALSE AND deadline_at > CURRENT_TIMESTAMP");
        List<long[]> expired = jdbc.query("SELECT id, schedule_version_id FROM solve_job WHERE status = 'RUNNING' AND lease_until IS NOT NULL AND lease_until < CURRENT_TIMESTAMP AND cancel_requested = FALSE AND deadline_at <= CURRENT_TIMESTAMP",
                (rs, rowNum) -> new long[]{rs.getLong("id"), rs.getLong("schedule_version_id")});
        int failed = 0;
        for (long[] job : expired) {
            jdbc.update("UPDATE schedule_version SET status = 'FAILED' WHERE id = ? AND status = 'SOLVING'", job[1]);
            int updated = jdbc.update("UPDATE solve_job SET status = 'FAILED', error_code = 'DEADLINE_EXCEEDED', error_message = '求解任务超过截止时间', finished_at = CURRENT_TIMESTAMP, lease_until = NULL WHERE id = ? AND status = 'RUNNING' AND cancel_requested = FALSE", job[0]);
            if (updated == 1) {
                failed++;
                jdbc.update("INSERT INTO audit_event (action, aggregate_type, aggregate_id, detail) VALUES ('SOLVE_DEADLINE', 'SOLVE_JOB', ?, jsonb_build_object('errorCode', 'DEADLINE_EXCEEDED'))", String.valueOf(job[0]));
            }
        }
        return reclaimed + failed;
    }

    @Transactional
    public boolean finishCancelled(long jobId, long versionId) {
        jdbc.update("UPDATE schedule_version SET status = 'CANCELLED' WHERE id = ? AND status = 'SOLVING'", versionId);
        return jdbc.update("UPDATE solve_job SET status = 'CANCELLED', finished_at = CURRENT_TIMESTAMP, lease_until = NULL WHERE id = ? AND status IN ('RUNNING','COMPLETING') AND cancel_requested = TRUE", jobId) == 1;
    }
    @Transactional
    public Long claim(String workerId, Duration lease) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM solve_job WHERE status = 'QUEUED' AND cancel_requested = FALSE AND next_attempt_at <= CURRENT_TIMESTAMP AND deadline_at > CURRENT_TIMESTAMP ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1",
                (rs, rowNum) -> rs.getLong("id"));
        if (ids.isEmpty()) return null;
        Long jobId = ids.get(0);
        int updated = jdbc.update(
                "UPDATE solve_job SET status = 'RUNNING', worker_id = ?, attempt = attempt + 1, started_at = COALESCE(started_at, CURRENT_TIMESTAMP), heartbeat_at = CURRENT_TIMESTAMP, lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 second') WHERE id = ? AND status = 'QUEUED'",
                workerId, lease.toSeconds(), jobId);
        return updated == 1 ? jobId : null;
    }


    @Transactional
    public void heartbeat(long jobId, String workerId, int progress) {
        jdbc.update("UPDATE solve_job SET heartbeat_at = CURRENT_TIMESTAMP, lease_until = CURRENT_TIMESTAMP + INTERVAL '30 seconds', progress = ? WHERE id = ? AND worker_id = ? AND status = 'RUNNING'", progress, jobId, workerId);
    }

    @Transactional
    public boolean requestCancel(long jobId) {
        return requestCancel(jobId, null);
    }

    @Transactional
    public boolean requestCancel(long jobId, String requesterUsername) {
        if (requesterUsername != null && jdbc.queryForObject("SELECT COUNT(*) FROM solve_job j LEFT JOIN app_user u ON u.id=j.submitted_by_user_id WHERE j.id=? AND (u.username=? AND u.enabled=TRUE OR ?='system' OR EXISTS (SELECT 1 FROM app_user_role ur JOIN app_role r ON r.id=ur.role_id JOIN app_user admin ON admin.id=ur.user_id WHERE admin.username=? AND admin.enabled=TRUE AND r.code='USER_ADMIN' AND r.active=TRUE))", Integer.class, jobId, requesterUsername, requesterUsername, requesterUsername) == 0) {
            throw new IllegalArgumentException("无权操作该求解任务");
        }
        int queued = jdbc.update(
                "UPDATE solve_job SET status = 'CANCELLED', cancel_requested = TRUE, finished_at = CURRENT_TIMESTAMP, lease_until = NULL WHERE id = ? AND cancel_requested = FALSE AND status = 'QUEUED'",
                jobId);
        if (queued == 1) {
            jdbc.update(
                    "UPDATE schedule_version SET status = 'CANCELLED' WHERE id = (SELECT schedule_version_id FROM solve_job WHERE id = ?) AND status = 'SOLVING'",
                    jobId);
            return true;
        }
        return jdbc.update(
                "UPDATE solve_job SET cancel_requested = TRUE WHERE id = ? AND cancel_requested = FALSE AND status IN ('RUNNING','COMPLETING')",
                jobId) == 1;
    }

    @Transactional
    public boolean complete(long jobId, long versionId, String score, Timetable solution) {
        if (jdbc.update("UPDATE solve_job SET status = 'COMPLETING' WHERE id = ? AND status = 'RUNNING' AND cancel_requested = FALSE", jobId) != 1) return false;
        jdbc.update("DELETE FROM schedule_assignment WHERE schedule_version_id = ?", versionId);
        solution.getOccurrences().forEach(occurrence -> {
            var timeslot = occurrence.getTimeslot(); var room = occurrence.getRoom();
            jdbc.update("INSERT INTO schedule_assignment (schedule_version_id, occurrence_id, teaching_requirement_id, requirement_code, occurrence_key, activity_index, activity_member_index, pinned_period_code, activity_type_snapshot, subject_code, subject_name, teacher_code, teacher_name, student_group_code, student_group_name, timeslot_code, timeslot_label, weekday, period_no, room_code, room_name, source, locked, duration, activity_group_code, student_count, required_features, room_features, room_capacity) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", versionId, occurrence.getId(), occurrence.getTeachingRequirementId(), occurrence.getRequirementCode(), occurrence.getOccurrenceKey(), occurrence.getActivityIndex(), occurrence.getActivityMemberIndex(), occurrence.getPinnedPeriodCode(), occurrence.getActivityType(), occurrence.getSubjectCode(), occurrence.getSubjectName(), occurrence.getTeacherCode(), occurrence.getTeacherName(), occurrence.getStudentGroupCode(), occurrence.getStudentGroupName(), timeslot == null ? null : timeslot.getId(), timeslot == null ? null : timeslot.getLabel(), timeslot == null ? null : timeslot.getWeekday(), timeslot == null ? null : timeslot.getPeriod(), room == null ? null : room.getId(), room == null ? null : room.getName(), "SOLVER", occurrence.isPinned(), occurrence.getDuration(), occurrence.getActivityGroupCode(), occurrence.getStudentCount(), occurrence.getRequiredFeatures().toArray(String[]::new), room == null ? new String[0] : room.getFeatures().toArray(String[]::new), room == null ? 0 : room.getCapacity());
        });
        jdbc.update("UPDATE schedule_version SET status = 'CANDIDATE', score = ?, legacy_identity_unverified = ? WHERE id = ? AND status = 'SOLVING'", score, !identityComplete(solution), versionId);
        return jdbc.update("UPDATE solve_job SET status = 'COMPLETED', progress = 100, error_code = NULL, error_message = NULL, finished_at = CURRENT_TIMESTAMP, lease_until = NULL WHERE id = ? AND status = 'COMPLETING' AND cancel_requested = FALSE", jobId) == 1;
    }

    private boolean identityComplete(Timetable solution) {
        return solution.getOccurrences().stream().allMatch(item -> item.getTeachingRequirementId() != null
                && item.getRequirementCode() != null && !item.getRequirementCode().isBlank()
                && item.hasExplicitOccurrenceKey());
    }
    @Transactional
    public void fail(long jobId, long versionId, String code, String message) {
        Boolean cancelled = jdbc.queryForObject("SELECT cancel_requested FROM solve_job WHERE id = ? AND status = 'RUNNING'", Boolean.class, jobId);
        if (Boolean.TRUE.equals(cancelled)) {
            finishCancelled(jobId, versionId);
            return;
        }
        Integer attempt = jdbc.queryForObject("SELECT attempt FROM solve_job WHERE id = ? AND status = 'RUNNING'", Integer.class, jobId);
        Integer maxAttempts = jdbc.queryForObject("SELECT max_attempts FROM solve_job WHERE id = ?", Integer.class, jobId);
        Boolean withinDeadline = jdbc.queryForObject("SELECT deadline_at > CURRENT_TIMESTAMP FROM solve_job WHERE id = ?", Boolean.class, jobId);
        if (attempt != null && maxAttempts != null && attempt < maxAttempts && !Boolean.FALSE.equals(withinDeadline)) {
            jdbc.update("UPDATE solve_job SET status = 'QUEUED', error_code = ?, error_message = ?, worker_id = NULL, lease_until = NULL, heartbeat_at = NULL, next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '2 seconds') WHERE id = ? AND status = 'RUNNING' AND cancel_requested = FALSE", code, message, attempt, jobId);
            return;
        }
        jdbc.update("UPDATE schedule_version SET status = 'FAILED' WHERE id = ? AND status = 'SOLVING'", versionId);
        jdbc.update("UPDATE solve_job SET status = 'FAILED', error_code = ?, error_message = ?, finished_at = CURRENT_TIMESTAMP, lease_until = NULL WHERE id = ? AND status = 'RUNNING' AND cancel_requested = FALSE", code, message, jobId);
        jdbc.update("INSERT INTO audit_event (action, aggregate_type, aggregate_id, actor, detail) VALUES ('SOLVE_FAILED', 'SOLVE_JOB', ?, 'worker', jsonb_build_object('errorCode', ?, 'errorMessage', ?))", String.valueOf(jobId), code, message);
    }

    public SolveJobDetails details(long jobId) {
        return details(jobId, null);
    }

    public SolveJobDetails details(long jobId, String requesterUsername) {
        String ownerClause = requesterUsername == null ? "" : " AND (u.username = ? AND u.enabled = TRUE OR ? = 'system' OR EXISTS (SELECT 1 FROM app_user_role ur JOIN app_role r ON r.id=ur.role_id JOIN app_user admin ON admin.id=ur.user_id WHERE admin.username=? AND admin.enabled=TRUE AND r.code='USER_ADMIN' AND r.active=TRUE))";
        Object[] args = requesterUsername == null ? new Object[]{jobId} : new Object[]{jobId, requesterUsername, requesterUsername, requesterUsername};
        try {
            return jdbc.queryForObject("SELECT j.id, j.schedule_version_id, j.status AS job_status, v.status AS version_status, j.progress, v.score, j.error_code, j.error_message, j.attempt, j.started_at, j.heartbeat_at, j.finished_at, j.cancel_requested, j.deadline_at FROM solve_job j JOIN schedule_version v ON v.id = j.schedule_version_id LEFT JOIN app_user u ON u.id = j.submitted_by_user_id WHERE j.id = ?" + ownerClause, (rs, rowNum) -> mapDetails(rs), args);
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            throw exception;
        }
    }

    private SolveJobDetails mapDetails(ResultSet rs) throws SQLException {
        return new SolveJobDetails(rs.getLong("id"), rs.getLong("schedule_version_id"), rs.getString("job_status"), rs.getString("version_status"), rs.getInt("progress"), rs.getString("score"), rs.getString("error_code"), rs.getString("error_message"), rs.getInt("attempt"), stringTime(rs.getObject("started_at", OffsetDateTime.class)), stringTime(rs.getObject("heartbeat_at", OffsetDateTime.class)), stringTime(rs.getObject("finished_at", OffsetDateTime.class)), rs.getBoolean("cancel_requested"), stringTime(rs.getObject("deadline_at", OffsetDateTime.class)));
    }

    private String stringTime(OffsetDateTime time) { return time == null ? null : time.toString(); }

    private String normalizedOwner(String username) {
        return username == null || username.isBlank() ? "system" : username.trim();
    }

    private void requireKnownUser(String username) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username = ? AND enabled = TRUE", Integer.class, username) == 0) {
            throw new IllegalArgumentException("用户不存在或已停用: " + username);
        }
    }
}
