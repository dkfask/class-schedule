package com.classschedule.schedule;

import com.classschedule.api.AdjustmentPreviewRequest;
import com.classschedule.api.AdjustmentPreviewResponse;
import com.classschedule.solver.LessonOccurrence;
import com.classschedule.solver.Timetable;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ScheduleRepository {
    private final JdbcTemplate jdbc;
    private final ScheduleRuleValidator ruleValidator;
    private final ScheduleSnapshotHashService snapshots;

    public ScheduleRepository(JdbcTemplate jdbc, ScheduleRuleValidator ruleValidator, ScheduleSnapshotHashService snapshots) {
        this.jdbc = jdbc;
        this.ruleValidator = ruleValidator;
        this.snapshots = snapshots;
    }

    public long createScenarioAndVersion() {
        Long scenarioId = jdbc.queryForObject(
                "INSERT INTO schedule_scenario (term_id, name) SELECT id, '默认排课场景' FROM academic_term WHERE code = '2026-FALL' RETURNING id",
                Long.class);
        ScheduleSnapshotHashService.Snapshot snapshot = snapshots.snapshot("2026-FALL");
        return jdbc.queryForObject(
                "INSERT INTO schedule_version (scenario_id, status, solver_version, snapshot_term_code, input_snapshot_hash, rule_snapshot_hash, input_snapshot_at, legacy_identity_unverified) VALUES (?, 'SOLVING', 'timefold-1.17.0', ?, ?, ?, CURRENT_TIMESTAMP, TRUE) RETURNING id",
                Long.class, scenarioId, snapshot.termCode(), snapshot.inputHash(), snapshot.ruleHash());
    }

    public long createJob(long versionId) {
        return jdbc.queryForObject(
                "INSERT INTO solve_job (schedule_version_id, status) VALUES (?, 'RUNNING') RETURNING id",
                Long.class, versionId);
    }

    @Transactional
    public void markCompleted(long jobId, long versionId, String score, Timetable timetable) {
        jdbc.update("DELETE FROM schedule_assignment WHERE schedule_version_id = ?", versionId);
        for (LessonOccurrence occurrence : timetable.getOccurrences()) {
            var timeslot = occurrence.getTimeslot();
            var room = occurrence.getRoom();
            jdbc.update(
                    "INSERT INTO schedule_assignment (schedule_version_id, occurrence_id, teaching_requirement_id, requirement_code, occurrence_key, activity_index, activity_member_index, pinned_period_code, activity_type_snapshot, subject_code, subject_name, teacher_code, teacher_name, student_group_code, student_group_name, timeslot_code, timeslot_label, weekday, period_no, room_code, room_name, source, locked, duration, activity_group_code, student_count, required_features, room_features, room_capacity) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    versionId, occurrence.getId(), occurrence.getTeachingRequirementId(), occurrence.getRequirementCode(), occurrence.getOccurrenceKey(), occurrence.getActivityIndex(), occurrence.getActivityMemberIndex(), occurrence.getPinnedPeriodCode(), occurrence.getActivityType(), occurrence.getSubjectCode(), occurrence.getSubjectName(),
                    occurrence.getTeacherCode(), occurrence.getTeacherName(), occurrence.getStudentGroupCode(), occurrence.getStudentGroupName(),
                    timeslot == null ? null : timeslot.getId(), timeslot == null ? null : timeslot.getLabel(),
                    timeslot == null ? null : timeslot.getWeekday(), timeslot == null ? null : timeslot.getPeriod(),
                    room == null ? null : room.getId(), room == null ? null : room.getName(), "SOLVER", occurrence.isPinned(), occurrence.getDuration(),
                    occurrence.getActivityGroupCode(), occurrence.getStudentCount(), occurrence.getRequiredFeatures().toArray(String[]::new),
                    room == null ? new String[0] : room.getFeatures().toArray(String[]::new), room == null ? 0 : room.getCapacity());
        }
        jdbc.update("UPDATE schedule_version SET status = 'CANDIDATE', score = ?, legacy_identity_unverified = ? WHERE id = ? AND status = 'SOLVING'", score, !identityComplete(timetable), versionId);
        jdbc.update(
                "UPDATE solve_job SET status = 'COMPLETED', progress = 100, finished_at = CURRENT_TIMESTAMP WHERE id = ?",
                jobId);
    }

    private boolean identityComplete(Timetable timetable) {
        return timetable.getOccurrences().stream().allMatch(item -> item.getTeachingRequirementId() != null
                && item.getRequirementCode() != null && !item.getRequirementCode().isBlank()
                && item.hasExplicitOccurrenceKey());
    }
    public void markFailed(long jobId, long versionId, String message) {
        jdbc.update("UPDATE schedule_version SET status = 'FAILED' WHERE id = ?", versionId);
        jdbc.update(
                "UPDATE solve_job SET status = 'FAILED', error_code = 'SOLVER_ERROR', error_message = ?, finished_at = CURRENT_TIMESTAMP WHERE id = ?",
                message, jobId);
    }

    public ScheduleVersionView findVersionByJob(long jobId) {
        Long versionId = jdbc.queryForObject(
                "SELECT schedule_version_id FROM solve_job WHERE id = ?", Long.class, jobId);
        if (versionId == null) {
            throw new IllegalArgumentException("求解任务不存在: " + jobId);
        }
        return findVersion(versionId);
    }

    public void markCancelled(long jobId) {
        int updated = jdbc.update(
                "UPDATE solve_job SET status = 'CANCELLED', finished_at = CURRENT_TIMESTAMP WHERE id = ? AND status IN ('QUEUED', 'RUNNING')",
                jobId);
        if (updated == 1) {
            jdbc.update(
                    "UPDATE schedule_version SET status = 'CANCELLED' WHERE id = (SELECT schedule_version_id FROM solve_job WHERE id = ?)",
                    jobId);
        }
    }

    public ScheduleVersionView findVersion(long versionId) {
        try {
            List<ScheduleAssignmentView> assignments = jdbc.query(
                    "SELECT occurrence_id, teaching_requirement_id, requirement_code, COALESCE(occurrence_key, occurrence_id::varchar) AS occurrence_key, activity_index, activity_member_index, pinned_period_code, activity_type_snapshot, subject_code, subject_name, teacher_code, teacher_name, student_group_code, student_group_name, timeslot_code, timeslot_label, weekday, period_no, room_code, room_name, source, locked, duration, activity_group_code, COALESCE(activity_type_snapshot, (SELECT activity_type FROM activity_group WHERE code = schedule_assignment.activity_group_code)) AS activity_type, student_count, required_features, room_features, room_capacity FROM schedule_assignment WHERE schedule_version_id = ? ORDER BY occurrence_id",
                    (rs, rowNum) -> mapAssignment(rs), versionId);
            return jdbc.queryForObject(
                    "SELECT v.id, v.status, v.score, v.legacy_identity_unverified, v.revision, v.updated_at, v.archived_at, v.edit_locked, v.edit_lock_owner, v.edit_lock_reason, v.snapshot_term_code, v.input_snapshot_hash, v.rule_snapshot_hash, v.input_snapshot_at, COUNT(a.id) AS assignment_count, COUNT(a.timeslot_code) AS assigned_timeslots, COUNT(a.room_code) AS assigned_rooms FROM schedule_version v LEFT JOIN schedule_assignment a ON v.id = a.schedule_version_id WHERE v.id = ? GROUP BY v.id, v.status, v.score, v.legacy_identity_unverified, v.revision, v.updated_at, v.archived_at, v.edit_locked, v.edit_lock_owner, v.edit_lock_reason, v.snapshot_term_code, v.input_snapshot_hash, v.rule_snapshot_hash, v.input_snapshot_at",
                    (rs, rowNum) -> {
                        long assignmentCount = rs.getLong("assignment_count");
                        long assignedTimeslots = rs.getLong("assigned_timeslots");
                        long assignedRooms = rs.getLong("assigned_rooms");
                        String status = rs.getString("status");
                        String score = rs.getString("score");
                        ScheduleScoreView parsedScore = ScheduleScoreView.parse(score);
                        long revision = rs.getLong("revision");
                        var updatedAt = rs.getObject("updated_at", java.time.OffsetDateTime.class);
                        var archivedAt = rs.getObject("archived_at", java.time.OffsetDateTime.class);
                        boolean legacyIdentityUnverified = rs.getBoolean("legacy_identity_unverified");
                        boolean editLocked = rs.getBoolean("edit_locked");
                        String editLockOwner = rs.getString("edit_lock_owner");
                        String editLockReason = rs.getString("edit_lock_reason");
                        boolean publishable = "CANDIDATE".equals(status)
                                && !legacyIdentityUnverified
                                && parsedScore.hardFeasible()
                                && assignmentCount > 0
                                && assignmentCount == assignedTimeslots
                                && assignmentCount == assignedRooms
                                && ScheduleValidation.validate(new ScheduleVersionView(
                                                rs.getLong("id"), status, score, false, assignments, revision,
                                                updatedAt, archivedAt, editLocked, editLockOwner, editLockReason, rs.getString("snapshot_term_code"),
                                                rs.getString("input_snapshot_hash"), rs.getString("rule_snapshot_hash"), rs.getObject("input_snapshot_at", java.time.OffsetDateTime.class), legacyIdentityUnverified)).isEmpty()
                                && ruleValidator.validate(rs.getLong("id"), new ScheduleVersionView(
                                                rs.getLong("id"), status, score, false, assignments, revision,
                                                updatedAt, archivedAt, editLocked, editLockOwner, editLockReason, rs.getString("snapshot_term_code"),
                                                rs.getString("input_snapshot_hash"), rs.getString("rule_snapshot_hash"), rs.getObject("input_snapshot_at", java.time.OffsetDateTime.class), legacyIdentityUnverified)).stream().noneMatch(ScheduleRuleValidator.Violation::blocking);
                        return new ScheduleVersionView(rs.getLong("id"), status, score, publishable, assignments,
                                revision, updatedAt, archivedAt, editLocked, editLockOwner, editLockReason, rs.getString("snapshot_term_code"),
                                rs.getString("input_snapshot_hash"), rs.getString("rule_snapshot_hash"), rs.getObject("input_snapshot_at", java.time.OffsetDateTime.class), legacyIdentityUnverified);
                    },
                    versionId);
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalArgumentException("版本不存在: " + versionId, exception);
        }
    }

    public ScheduleVersionView findVersionFiltered(long versionId, String view, String resourceCode) {
        ScheduleVersionView full = findVersion(versionId);
        if (resourceCode == null || resourceCode.isBlank()) {
            return full;
        }
        String normalizedView = view == null ? "CLASS" : view.trim().toUpperCase();
        if (!Set.of("CLASS", "TEACHER", "ROOM").contains(normalizedView)) {
            throw new IllegalArgumentException("不支持的课表视图: " + view);
        }
        List<ScheduleAssignmentView> filtered = full.assignments().stream()
                .filter(item -> switch (normalizedView) {
                    case "TEACHER" -> resourceCode.equals(item.teacherCode());
                    case "ROOM" -> resourceCode.equals(item.roomCode());
                    default -> resourceCode.equals(item.studentGroupCode());
                })
                .toList();
        return new ScheduleVersionView(
                full.id(), full.status(), full.score(), full.publishable(), filtered,
                full.revision(), full.updatedAt(), full.archivedAt(), full.editLocked(),
                full.editLockOwner(), full.editLockReason(), full.termCode(), full.inputSnapshotHash(), full.ruleSnapshotHash(), full.inputSnapshotAt(), full.legacyIdentityUnverified());
    }

    public AdjustmentPreviewResponse previewAdjustment(
            long versionId, AdjustmentPreviewRequest request) {
        ScheduleVersionView version = findVersion(versionId);
        ScheduleAssignmentView current = version.assignments().stream()
                .filter(item -> item.occurrenceId() == request.occurrenceId())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("教学任务不存在: " + request.occurrenceId()));

        List<AdjustmentPreviewResponse.Violation> violations = new ArrayList<>();
        Set<Long> affected = new LinkedHashSet<>();
        boolean lockedConflict = false;

        if (!("DRAFT".equals(version.status()) || "CANDIDATE".equals(version.status()))) {
            violations.add(new AdjustmentPreviewResponse.Violation(
                    "VERSION_NOT_EDITABLE", "当前版本不可调整，请先创建草稿或分支版本", version.status()));
        }
        if (current.locked()) {
            violations.add(new AdjustmentPreviewResponse.Violation(
                    "LOCKED_ASSIGNMENT", "课程已锁定，不能调整", String.valueOf(current.occurrenceId())));
            lockedConflict = true;
        }
        if (!timeslotExists(versionId, request.timeslotCode())) {
            violations.add(new AdjustmentPreviewResponse.Violation(
                    "TIMESLOT_NOT_FOUND", "目标节次不存在", request.timeslotCode()));
        }
        if (!roomExists(request.roomCode())) {
            violations.add(new AdjustmentPreviewResponse.Violation(
                    "ROOM_NOT_FOUND", "目标教室不存在或已停用", request.roomCode()));
        }

        for (ScheduleAssignmentView item : version.assignments()) {
            if (item.occurrenceId() == current.occurrenceId()
                    || !request.timeslotCode().equals(item.timeslotCode())) {
                continue;
            }
            boolean conflict = false;
            if (current.teacherCode().equals(item.teacherCode())) {
                violations.add(new AdjustmentPreviewResponse.Violation(
                        "TEACHER_CONFLICT", "教师在目标节次已有课程", current.teacherCode()));
                conflict = true;
            }
            if (current.studentGroupCode().equals(item.studentGroupCode())) {
                violations.add(new AdjustmentPreviewResponse.Violation(
                        "STUDENT_GROUP_CONFLICT", "班级在目标节次已有课程", current.studentGroupCode()));
                conflict = true;
            }
            if (request.roomCode().equals(item.roomCode())) {
                violations.add(new AdjustmentPreviewResponse.Violation(
                        "ROOM_CONFLICT", "教室在目标节次已有课程", request.roomCode()));
                conflict = true;
            }
            if (conflict) {
                affected.add(item.occurrenceId());
            }
            if (conflict && item.locked()) {
                lockedConflict = true;
                violations.add(new AdjustmentPreviewResponse.Violation(
                        "LOCKED_CONFLICT", "目标资源涉及已锁定课程", String.valueOf(item.occurrenceId())));
            }
        }

        if (timeslotExists(versionId, request.timeslotCode()) && roomExists(request.roomCode())) {
            ScheduleAssignmentView target = targetLocation(versionId, current, request);
            List<ScheduleAssignmentView> targetAssignments = version.assignments().stream()
                    .map(item -> item.occurrenceId() == current.occurrenceId() ? target : item)
                    .toList();
            for (ScheduleRuleValidator.Violation violation : ruleValidator.validateAssignments(versionId, targetAssignments)) {
                if (violation.blocking()) {
                    violations.add(new AdjustmentPreviewResponse.Violation(
                            violation.code(), violation.message(), violation.resourceCode()));
                }
                if (violation.occurrenceKey().equals(current.occurrenceKey())) {
                    affected.add(current.occurrenceId());
                }
            }
        }

        return new AdjustmentPreviewResponse(
                violations.isEmpty(),
                violations,
                List.copyOf(affected),
                lockedConflict,
                versionId,
                new AdjustmentPreviewResponse.AdjustmentLocation(
                        current.timeslotCode(), current.roomCode()),
                new AdjustmentPreviewResponse.AdjustmentLocation(
                        request.timeslotCode(), request.roomCode()));
    }

    private ScheduleAssignmentView targetLocation(long versionId, ScheduleAssignmentView current, AdjustmentPreviewRequest request) {
        Map<String, Object> slot = jdbc.queryForMap(
                "SELECT p.weekday, p.period_no, p.label FROM period_template p JOIN schedule_scenario s ON s.term_id=p.term_id JOIN schedule_version v ON v.scenario_id=s.id WHERE v.id=? AND p.code=?",
                versionId, request.timeslotCode());
        Map<String, Object> room = jdbc.queryForMap("SELECT capacity FROM room WHERE code=? AND active=TRUE", request.roomCode());
        List<String> features = jdbc.query("SELECT rf.feature_code FROM room_feature rf JOIN room r ON r.id=rf.room_id WHERE r.code=?", (rs, row) -> rs.getString("feature_code"), request.roomCode());
        return new ScheduleAssignmentView(
                current.occurrenceId(), current.subjectCode(), current.subjectName(), current.teacherCode(), current.teacherName(),
                current.studentGroupCode(), current.studentGroupName(), request.timeslotCode(), (String) slot.get("label"),
                ((Number) slot.get("weekday")).intValue(), ((Number) slot.get("period_no")).intValue(), request.roomCode(),
                (String) jdbc.queryForObject("SELECT name FROM room WHERE code=?", String.class, request.roomCode()), current.source(),
                current.locked(), current.duration(), current.occurrenceKey(), current.activityGroupCode(), current.activityType(),
                current.studentCount(), current.requiredFeatures(), new java.util.LinkedHashSet<>(features), ((Number) room.get("capacity")).intValue(),
                current.teachingRequirementId(), current.requirementCode(), current.activityIndex(), current.activityMemberIndex(), current.pinnedPeriodCode(), current.activityTypeSnapshot());
    }

    private boolean timeslotExists(long versionId, String timeslotCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM period_template p JOIN schedule_scenario s ON s.term_id = p.term_id JOIN schedule_version v ON v.scenario_id = s.id WHERE v.id = ? AND p.code = ?",
                Integer.class, versionId, timeslotCode);
        return count != null && count > 0;
    }

    private boolean roomExists(String roomCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM room WHERE code = ? AND active = TRUE", Integer.class, roomCode);
        return count != null && count > 0;
    }

    public com.classschedule.api.ExchangeCandidatesResponse exchangeCandidates(
            long versionId, com.classschedule.api.ExchangeCandidatesRequest request) {
        ScheduleVersionView version = findVersion(versionId);
        ScheduleAssignmentView current = version.assignments().stream()
                .filter(item -> item.occurrenceId() == request.occurrenceId())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("教学任务不存在: " + request.occurrenceId()));
        var preview = previewAdjustment(versionId, new AdjustmentPreviewRequest(
                request.occurrenceId(), request.timeslotCode(), request.roomCode()));
        if (preview.allowed()) return new com.classschedule.api.ExchangeCandidatesResponse(true, List.of(), List.of());
        List<com.classschedule.api.ExchangeCandidatesResponse.Candidate> candidates = version.assignments().stream()
                .filter(item -> item.occurrenceId() != current.occurrenceId())
                .filter(item -> !item.locked())
                .filter(item -> request.timeslotCode().equals(item.timeslotCode()) && request.roomCode().equals(item.roomCode()))
                .map(item -> new com.classschedule.api.ExchangeCandidatesResponse.Candidate(
                        item.occurrenceId(), item.occurrenceKey(), item.subjectName(), item.studentGroupCode(),
                        item.teacherCode(), item.roomCode(), item.timeslotCode()))
                .toList();
        return new com.classschedule.api.ExchangeCandidatesResponse(false, candidates, preview.hardViolations());
    }

    @Transactional
    public void exchange(long versionId, com.classschedule.api.ExchangeRequest request) {
        exchange(versionId, request, "planner", null);
    }

    @Transactional
    public ScheduleVersionRepositoryModels.MutationResult exchange(
            long versionId, com.classschedule.api.ExchangeRequest request, String actor, String idempotencyKey) {
        if (request.occurrenceId() == request.swapOccurrenceId()) {
            throw new IllegalArgumentException("不能交换同一教学任务");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new IllegalArgumentException("调整原因不能为空");
        }
        String key = normalizeKey(idempotencyKey);
        String requestHash = hash("EXCHANGE|" + request.occurrenceId() + "|" + request.swapOccurrenceId() + "|" + request.reason());
        LockedVersion locked = lockEditableVersion(versionId, null, actor);
        ScheduleVersionRepositoryModels.MutationResult existing = findIdempotentResult(versionId, key, requestHash);
        if (existing != null) return existing;
        requireExpectedRevision(versionId, locked.revision(), request.expectedRevision());
        supersedeRedo(versionId);

        ScheduleVersionView version = findVersion(versionId);
        ScheduleAssignmentView left = assignment(version, request.occurrenceId());
        ScheduleAssignmentView right = assignment(version, request.swapOccurrenceId());
        if (left.locked() || right.locked()) throw new IllegalArgumentException("锁定课程不能交换");

        ScheduleAssignmentView leftAfter = relocate(left, right.timeslotCode(), right.timeslotLabel(), right.weekday(), right.period(), right.roomCode(), right.roomName(), right.roomFeatures(), right.roomCapacity());
        ScheduleAssignmentView rightAfter = relocate(right, left.timeslotCode(), left.timeslotLabel(), left.weekday(), left.period(), left.roomCode(), left.roomName(), left.roomFeatures(), left.roomCapacity());
        List<ScheduleAssignmentView> afterAssignments = version.assignments().stream()
                .map(item -> item.occurrenceId() == left.occurrenceId() ? leftAfter
                        : item.occurrenceId() == right.occurrenceId() ? rightAfter : item)
                .toList();
        List<ScheduleRuleValidator.Violation> violations = ruleValidator.validateAssignments(versionId, afterAssignments);
        if (!violations.isEmpty()) throw new IllegalArgumentException("交换后违反排课规则: " + violations.get(0).message());

        updateAssignmentLocation(versionId, leftAfter);
        updateAssignmentLocation(versionId, rightAfter);
        UUID groupId = UUID.randomUUID();
        long resultRevision = locked.revision() + 1;
        jdbc.update("INSERT INTO adjustment_command_group (id, schedule_version_id, command_type, base_revision, result_revision, state, idempotency_key, request_hash, actor, reason) VALUES (?, ?, 'EXCHANGE', ?, ?, 'APPLIED', ?, ?, ?, ?)", groupId, versionId, locked.revision(), resultRevision, key, requestHash, actor, request.reason());
        Long leftCommand = insertCommand(groupId, versionId, 1, left, leftAfter, request.reason(), actor);
        Long rightCommand = insertCommand(groupId, versionId, 2, right, rightAfter, request.reason(), actor);
        updateRevision(versionId, locked.revision(), resultRevision, "DRAFT");
        insertCommandEvent(groupId, versionId, "APPLY", locked.revision(), resultRevision, key, actor);
        insertAudit(versionId, "ADJUSTMENT_EXCHANGE", resultRevision, groupId.toString(), actor);
        return new ScheduleVersionRepositoryModels.MutationResult(groupId, List.of(leftCommand, rightCommand), resultRevision);
    }

    private ScheduleAssignmentView assignment(ScheduleVersionView version, long occurrenceId) {
        return version.assignments().stream().filter(item -> item.occurrenceId() == occurrenceId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("教学任务不存在: " + occurrenceId));
    }

    private ScheduleAssignmentView relocate(ScheduleAssignmentView current, String timeslotCode, String timeslotLabel,
            int weekday, int period, String roomCode, String roomName, Set<String> roomFeatures, int roomCapacity) {
        return new ScheduleAssignmentView(current.occurrenceId(), current.subjectCode(), current.subjectName(), current.teacherCode(), current.teacherName(),
                current.studentGroupCode(), current.studentGroupName(), timeslotCode, timeslotLabel, weekday, period, roomCode, roomName,
                "MANUAL", current.locked(), current.duration(), current.occurrenceKey(), current.activityGroupCode(), current.activityType(),
                current.studentCount(), current.requiredFeatures(), roomFeatures, roomCapacity,
                current.teachingRequirementId(), current.requirementCode(), current.activityIndex(), current.activityMemberIndex(), current.pinnedPeriodCode(), current.activityTypeSnapshot());
    }

    private Long insertCommand(UUID groupId, long versionId, int sequence, ScheduleAssignmentView before,
            ScheduleAssignmentView after, String reason, String actor) {
        return jdbc.queryForObject("INSERT INTO adjustment_command (schedule_version_id, command_group_id, sequence, occurrence_id, from_timeslot_code, to_timeslot_code, from_room_code, to_room_code, reason, actor, from_source, to_source, from_locked, to_locked) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, versionId, groupId, sequence, before.occurrenceId(), before.timeslotCode(), after.timeslotCode(), before.roomCode(), after.roomCode(), reason, actor, before.source(), after.source(), before.locked(), after.locked());
    }
    @Transactional
    public long fork(long versionId, String name) {
        return fork(versionId, name, "planner");
    }

    @Transactional
    public long fork(long versionId, String name, String actor) {
        Map<String, Object> source = versionRowForUpdate(versionId);
        String status = String.valueOf(source.get("status"));
        if (!("CANDIDATE".equals(status) || "PUBLISHED".equals(status) || "ARCHIVED".equals(status))) {
            throw new VersionMutationException("VERSION_NOT_FORKABLE", versionId, ((Number) source.get("revision")).longValue(), "当前版本不可创建分支: " + status);
        }
        Long scenarioId = jdbc.queryForObject("SELECT scenario_id FROM schedule_version WHERE id = ?", Long.class, versionId);
        Long newScenarioId = jdbc.queryForObject("INSERT INTO schedule_scenario (term_id, name, parent_version_id) SELECT term_id, ?, ? FROM schedule_scenario WHERE id = ? RETURNING id", Long.class, name, versionId, scenarioId);
        Long newVersionId = jdbc.queryForObject("INSERT INTO schedule_version (scenario_id, parent_version_id, status, score, solver_version, random_seed, snapshot_term_code, input_snapshot_hash, rule_snapshot_hash, input_snapshot_at, legacy_identity_unverified) SELECT ?, id, 'DRAFT', NULL, solver_version, random_seed, snapshot_term_code, input_snapshot_hash, rule_snapshot_hash, input_snapshot_at, legacy_identity_unverified FROM schedule_version WHERE id = ? RETURNING id", Long.class, newScenarioId, versionId);
        jdbc.update("INSERT INTO schedule_assignment (schedule_version_id, occurrence_id, teaching_requirement_id, requirement_code, occurrence_key, activity_index, activity_member_index, pinned_period_code, activity_type_snapshot, subject_code, subject_name, teacher_code, teacher_name, student_group_code, student_group_name, timeslot_code, timeslot_label, weekday, period_no, room_code, room_name, source, locked, duration, activity_group_code, student_count, required_features, room_features, room_capacity) SELECT ?, occurrence_id, teaching_requirement_id, requirement_code, COALESCE(occurrence_key, occurrence_id::varchar), activity_index, activity_member_index, pinned_period_code, activity_type_snapshot, subject_code, subject_name, teacher_code, teacher_name, student_group_code, student_group_name, timeslot_code, timeslot_label, weekday, period_no, room_code, room_name, source, locked, duration, activity_group_code, student_count, required_features, room_features, room_capacity FROM schedule_assignment WHERE schedule_version_id = ?", newVersionId, versionId);
        insertAudit(newVersionId, "FORK", 0, String.valueOf(versionId), actor);
        return newVersionId;
    }

    public ScheduleVersionRepositoryModels.VersionPage listVersions(String termCode, String status, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        String normalizedTerm = termCode == null || termCode.isBlank() ? "2026-FALL" : termCode;
        String normalizedStatus = status == null ? "" : status.trim();
        String statusFilter = normalizedStatus.isBlank() ? "" : " AND v.status = ?";
        String countSql = "SELECT COUNT(*) FROM schedule_version v JOIN schedule_scenario s ON s.id = v.scenario_id JOIN academic_term t ON t.id = s.term_id WHERE t.code = ?" + statusFilter;
        Long total = normalizedStatus.isBlank()
                ? jdbc.queryForObject(countSql, Long.class, normalizedTerm)
                : jdbc.queryForObject(countSql, Long.class, normalizedTerm, normalizedStatus);
        String listSql = "SELECT v.id, v.status, v.score, v.revision, v.updated_at, v.archived_at, v.edit_locked, v.edit_lock_owner, v.parent_version_id, v.created_at, s.term_id FROM schedule_version v JOIN schedule_scenario s ON s.id = v.scenario_id JOIN academic_term t ON t.id = s.term_id WHERE t.code = ?" + statusFilter + " ORDER BY v.created_at DESC, v.id DESC LIMIT ? OFFSET ?";
        List<ScheduleVersionRepositoryModels.VersionSummary> items = normalizedStatus.isBlank()
                ? jdbc.query(listSql, (rs, row) -> versionSummary(rs), normalizedTerm, safeSize, safePage * safeSize)
                : jdbc.query(listSql, (rs, row) -> versionSummary(rs), normalizedTerm, normalizedStatus, safeSize, safePage * safeSize);
        return new ScheduleVersionRepositoryModels.VersionPage(items, safePage, safeSize, total == null ? 0 : total);
    }

    public List<ScheduleVersionRepositoryModels.DiffItem> diff(long versionId, Long againstVersionId) {
        long baseId = againstVersionId == null ? parentVersionId(versionId) : againstVersionId;
        Map<String, ScheduleAssignmentView> before = baseId == 0 ? Map.of() : assignmentsByKey(baseId);
        Map<String, ScheduleAssignmentView> after = assignmentsByKey(versionId);
        Set<String> keys = new java.util.LinkedHashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        return keys.stream()
                .sorted()
                .map(key -> diffItem(key, before.get(key), after.get(key)))
                .collect(java.util.stream.Collectors.toList());
    }

    private long parentVersionId(long versionId) {
        Long parent = jdbc.queryForObject("SELECT COALESCE(parent_version_id, 0) FROM schedule_version WHERE id = ?", Long.class, versionId);
        return parent == null ? 0 : parent;
    }

    private Map<String, ScheduleAssignmentView> assignmentsByKey(long versionId) {
        return findVersion(versionId).assignments().stream()
                .collect(java.util.stream.Collectors.toMap(ScheduleAssignmentView::occurrenceKey, item -> item, (left, right) -> left, java.util.LinkedHashMap::new));
    }

    private ScheduleVersionRepositoryModels.DiffItem diffItem(String key, ScheduleAssignmentView before, ScheduleAssignmentView after) {
        String type;
        if (before == null) type = "ADDED";
        else if (after == null) type = "REMOVED";
        else if (!java.util.Objects.equals(before.timeslotCode(), after.timeslotCode())) type = "MOVED";
        else if (!java.util.Objects.equals(before.roomCode(), after.roomCode())) type = "ROOM_CHANGED";
        else if (before.locked() != after.locked()) type = "LOCK_CHANGED";
        else if (!java.util.Objects.equals(before.source(), after.source())) type = "SOURCE_CHANGED";
        else type = "UNCHANGED";
        return new ScheduleVersionRepositoryModels.DiffItem(type, key, before, after);
    }

    private ScheduleVersionRepositoryModels.VersionSummary versionSummary(ResultSet rs) throws SQLException {
        return new ScheduleVersionRepositoryModels.VersionSummary(
                rs.getLong("id"), rs.getString("status"), rs.getString("score"),
                findVersion(rs.getLong("id")).publishable(),
                rs.getObject("parent_version_id", Long.class),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getLong("revision"),
                rs.getObject("updated_at", java.time.OffsetDateTime.class),
                rs.getObject("archived_at", java.time.OffsetDateTime.class),
                rs.getBoolean("edit_locked"), rs.getString("edit_lock_owner"));
    }

    @Transactional
    public long adjust(long versionId, long occurrenceId, String timeslotCode, String roomCode, String reason, String actor) {
        return adjust(versionId, occurrenceId, timeslotCode, roomCode, reason, actor, null, null).commandIds().get(0);
    }

    @Transactional
    public ScheduleVersionRepositoryModels.MutationResult adjust(
            long versionId, long occurrenceId, String timeslotCode, String roomCode, String reason,
            String actor, Long expectedRevision, String idempotencyKey) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("调整原因不能为空");
        String key = normalizeKey(idempotencyKey);
        String requestHash = hash("ADJUST|" + occurrenceId + "|" + timeslotCode + "|" + roomCode + "|" + reason);
        LockedVersion locked = lockEditableVersion(versionId, null, actor);
        ScheduleVersionRepositoryModels.MutationResult existing = findIdempotentResult(versionId, key, requestHash);
        if (existing != null) return existing;
        requireExpectedRevision(versionId, locked.revision(), expectedRevision);
        supersedeRedo(versionId);

        ScheduleVersionView version = findVersion(versionId);
        ScheduleAssignmentView current = assignment(version, occurrenceId);
        if (current.locked()) throw new IllegalArgumentException("锁定课程不能调整");
        if (timeslotCode.equals(current.timeslotCode()) && roomCode.equals(current.roomCode())) {
            throw new IllegalArgumentException("目标位置与当前安排相同");
        }
        AdjustmentPreviewResponse validation = previewAdjustment(versionId, new AdjustmentPreviewRequest(occurrenceId, timeslotCode, roomCode));
        if (!validation.allowed()) {
            String details = validation.hardViolations().stream().map(AdjustmentPreviewResponse.Violation::message)
                    .distinct().reduce((left, right) -> left + "；" + right).orElse("调整预览未通过");
            throw new IllegalArgumentException(details);
        }
        ScheduleAssignmentView after = targetAssignment(versionId, current, timeslotCode, roomCode);
        updateAssignmentLocation(versionId, after);
        UUID groupId = UUID.randomUUID();
        long resultRevision = locked.revision() + 1;
        jdbc.update("INSERT INTO adjustment_command_group (id, schedule_version_id, command_type, base_revision, result_revision, state, idempotency_key, request_hash, actor, reason) VALUES (?, ?, 'ADJUST', ?, ?, 'APPLIED', ?, ?, ?, ?)", groupId, versionId, locked.revision(), resultRevision, key, requestHash, actor, reason);
        Long commandId = insertCommand(groupId, versionId, 1, current, after, reason, actor);
        updateRevision(versionId, locked.revision(), resultRevision, "DRAFT");
        insertCommandEvent(groupId, versionId, "APPLY", locked.revision(), resultRevision, key, actor);
        insertAudit(versionId, "ADJUSTMENT", resultRevision, groupId.toString(), actor);
        return new ScheduleVersionRepositoryModels.MutationResult(groupId, List.of(commandId), resultRevision);
    }

    private record LockedVersion(long revision, String status, boolean editLocked, String lockOwner) {}

    private LockedVersion lockEditableVersion(long versionId, Long expectedRevision, String actor) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("SELECT revision, status, edit_locked, edit_lock_owner FROM schedule_version WHERE id = ? FOR UPDATE", versionId);
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalArgumentException("版本不存在: " + versionId, exception);
        }
        long revision = ((Number) row.get("revision")).longValue();
        String status = String.valueOf(row.get("status"));
        boolean editLocked = Boolean.TRUE.equals(row.get("edit_locked"));
        String lockOwner = (String) row.get("edit_lock_owner");
        if (expectedRevision != null && expectedRevision.longValue() != revision) {
            throw new VersionMutationException("VERSION_REVISION_CONFLICT", versionId, revision, "版本已被其他操作更新，请重新加载");
        }
        if (!("DRAFT".equals(status) || "CANDIDATE".equals(status))) {
            throw new VersionMutationException("VERSION_NOT_EDITABLE", versionId, revision, "当前版本不可调整: " + status);
        }
        if (editLocked && (actor == null || !actor.equals(lockOwner))) {
            throw new VersionMutationException("VERSION_LOCKED", versionId, revision, "版本已被锁定: " + lockOwner);
        }
        return new LockedVersion(revision, status, editLocked, lockOwner);
    }

    private ScheduleVersionRepositoryModels.MutationResult findIdempotentResult(long versionId, String key, String requestHash) {
        List<ScheduleVersionRepositoryModels.MutationResult> matches = jdbc.query(
                "SELECT id, result_revision FROM adjustment_command_group WHERE schedule_version_id = ? AND idempotency_key = ?",
                (rs, row) -> new ScheduleVersionRepositoryModels.MutationResult(
                        (UUID) rs.getObject("id"), List.of(), rs.getLong("result_revision")), versionId, key);
        if (matches.isEmpty()) return null;
        UUID groupId = matches.get(0).groupId();
        String savedHash = jdbc.queryForObject("SELECT request_hash FROM adjustment_command_group WHERE id = ?", String.class, groupId);
        if (!requestHash.equals(savedHash)) {
            throw new VersionMutationException("IDEMPOTENCY_CONFLICT", versionId, currentRevision(versionId), "幂等键已用于其他请求");
        }
        List<Long> commandIds = jdbc.query("SELECT id FROM adjustment_command WHERE command_group_id = ? ORDER BY sequence", (rs, row) -> rs.getLong("id"), groupId);
        return new ScheduleVersionRepositoryModels.MutationResult(groupId, commandIds, matches.get(0).revision());
    }

    private long currentRevision(long versionId) {
        Long value = jdbc.queryForObject("SELECT revision FROM schedule_version WHERE id = ?", Long.class, versionId);
        return value == null ? 0L : value;
    }

    private String normalizeKey(String key) { return key == null || key.isBlank() ? UUID.randomUUID().toString() : key.trim(); }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    private ScheduleAssignmentView targetAssignment(long versionId, ScheduleAssignmentView current, String timeslotCode, String roomCode) {
        Map<String, Object> slot = jdbc.queryForMap("SELECT p.weekday, p.period_no, p.label FROM period_template p JOIN schedule_scenario s ON s.term_id = p.term_id JOIN schedule_version v ON v.scenario_id = s.id WHERE v.id = ? AND p.code = ?", versionId, timeslotCode);
        Map<String, Object> room = jdbc.queryForMap("SELECT id, name, capacity FROM room WHERE code = ? AND active = TRUE", roomCode);
        Set<String> features = new LinkedHashSet<>(jdbc.query("SELECT feature_code FROM room_feature rf JOIN room r ON r.id = rf.room_id WHERE r.code = ?", (rs, row) -> rs.getString("feature_code"), roomCode));
        return relocate(current, timeslotCode, (String) slot.get("label"), ((Number) slot.get("weekday")).intValue(), ((Number) slot.get("period_no")).intValue(), roomCode, (String) room.get("name"), features, ((Number) room.get("capacity")).intValue());
    }

    private void updateAssignmentLocation(long versionId, ScheduleAssignmentView assignment) {
        jdbc.update("UPDATE schedule_assignment SET timeslot_code = ?, timeslot_label = ?, weekday = ?, period_no = ?, room_code = ?, room_name = ?, room_features = ?::text[], room_capacity = ?, source = ?, locked = ? WHERE schedule_version_id = ? AND occurrence_id = ?",
                assignment.timeslotCode(), assignment.timeslotLabel(), assignment.weekday(), assignment.period(), assignment.roomCode(), assignment.roomName(), assignment.roomFeatures().toArray(String[]::new), assignment.roomCapacity(), assignment.source(), assignment.locked(), versionId, assignment.occurrenceId());
    }

    private void updateRevision(long versionId, long expectedRevision, long resultRevision, String status) {
        int updated = jdbc.update("UPDATE schedule_version SET revision = ?, updated_at = CURRENT_TIMESTAMP, status = ?, score = NULL WHERE id = ? AND revision = ?", resultRevision, status, versionId, expectedRevision);
        if (updated != 1) throw new VersionMutationException("VERSION_REVISION_CONFLICT", versionId, currentRevision(versionId), "版本已被其他操作更新，请重新加载");
    }

    private void insertCommandEvent(UUID groupId, long versionId, String type, long fromRevision, long toRevision, String key, String actor) {
        jdbc.update("INSERT INTO adjustment_command_event (command_group_id, schedule_version_id, event_type, from_revision, to_revision, idempotency_key, actor) VALUES (?, ?, ?, ?, ?, ?, ?)", groupId, versionId, type, fromRevision, toRevision, key, actor);
    }

    private void insertAudit(long versionId, String action, long revision, String correlationId, String actor) {
        jdbc.update("INSERT INTO audit_event (action, aggregate_type, aggregate_id, actor, actor_user_id, actor_kind, correlation_id, outcome, detail) VALUES (?, 'SCHEDULE_VERSION', ?, ?, (SELECT id FROM app_user WHERE username = ?), CASE WHEN ? = 'worker' THEN 'SERVICE' ELSE 'USER' END, ?, 'SUCCESS', jsonb_build_object('revision', ?::bigint, 'correlationId', ?::text))", action, String.valueOf(versionId), actor, actor, actor, correlationId, revision, correlationId);
    }

    private record CommandRow(long id, int sequence, long occurrenceId, String fromTimeslotCode,
            String toTimeslotCode, String fromRoomCode, String toRoomCode, String fromSource,
            String toSource, Boolean fromLocked, Boolean toLocked) {}

    private List<CommandRow> commandRows(UUID groupId) {
        return jdbc.query("SELECT id, sequence, occurrence_id, from_timeslot_code, to_timeslot_code, from_room_code, to_room_code, from_source, to_source, from_locked, to_locked FROM adjustment_command WHERE command_group_id = ? ORDER BY sequence",
                (rs, row) -> new CommandRow(rs.getLong("id"), rs.getInt("sequence"), rs.getLong("occurrence_id"),
                        rs.getString("from_timeslot_code"), rs.getString("to_timeslot_code"), rs.getString("from_room_code"),
                        rs.getString("to_room_code"), rs.getString("from_source"), rs.getString("to_source"),
                        (Boolean) rs.getObject("from_locked"), (Boolean) rs.getObject("to_locked")), groupId);
    }

    private ScheduleVersionRepositoryModels.MutationResult eventResult(long versionId, String eventType, String key) {
        List<ScheduleVersionRepositoryModels.MutationResult> results = jdbc.query(
                "SELECT command_group_id, to_revision FROM adjustment_command_event WHERE schedule_version_id = ? AND event_type = ? AND idempotency_key = ?",
                (rs, row) -> new ScheduleVersionRepositoryModels.MutationResult((UUID) rs.getObject("command_group_id"), List.of(), rs.getLong("to_revision")), versionId, eventType, key);
        if (results.isEmpty()) return null;
        UUID groupId = results.get(0).groupId();
        List<Long> commandIds = jdbc.query("SELECT id FROM adjustment_command WHERE command_group_id = ? ORDER BY sequence", (rs, row) -> rs.getLong("id"), groupId);
        return new ScheduleVersionRepositoryModels.MutationResult(groupId, commandIds, currentRevision(versionId));
    }

    private Map<String, Object> commandGroup(UUID groupId, long versionId) {
        try {
            return jdbc.queryForMap("SELECT id, command_type, state, base_revision, result_revision FROM adjustment_command_group WHERE id = ? AND schedule_version_id = ? FOR UPDATE", groupId, versionId);
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalArgumentException("命令组不存在: " + groupId, exception);
        }
    }

    private void requireLatestGroup(long versionId, UUID groupId, String state) {
        UUID latest = jdbc.query("SELECT id FROM adjustment_command_group WHERE schedule_version_id = ? AND state = ? ORDER BY result_revision DESC, created_at DESC LIMIT 1",
                (rs, row) -> (UUID) rs.getObject("id"), versionId, state).stream().findFirst().orElse(null);
        if (!groupId.equals(latest)) throw new VersionMutationException("COMMAND_NOT_LATEST", versionId, currentRevision(versionId), "只能操作最近的一次命令");
    }

    private ScheduleAssignmentView withSourceLocked(ScheduleAssignmentView assignment, String source, Boolean locked) {
        return new ScheduleAssignmentView(assignment.occurrenceId(), assignment.subjectCode(), assignment.subjectName(), assignment.teacherCode(), assignment.teacherName(),
                assignment.studentGroupCode(), assignment.studentGroupName(), assignment.timeslotCode(), assignment.timeslotLabel(), assignment.weekday(), assignment.period(),
                assignment.roomCode(), assignment.roomName(), source == null ? assignment.source() : source,
                locked == null ? assignment.locked() : locked, assignment.duration(), assignment.occurrenceKey(), assignment.activityGroupCode(), assignment.activityType(),
                assignment.studentCount(), assignment.requiredFeatures(), assignment.roomFeatures(), assignment.roomCapacity(),
                assignment.teachingRequirementId(), assignment.requirementCode(), assignment.activityIndex(), assignment.activityMemberIndex(), assignment.pinnedPeriodCode(), assignment.activityTypeSnapshot());
    }

    private boolean matchesAfter(ScheduleAssignmentView current, CommandRow row) {
        return java.util.Objects.equals(current.timeslotCode(), row.toTimeslotCode())
                && java.util.Objects.equals(current.roomCode(), row.toRoomCode())
                && java.util.Objects.equals(current.source(), row.toSource())
                && (row.toLocked() == null || current.locked() == row.toLocked());
    }

    private boolean matchesBefore(ScheduleAssignmentView current, CommandRow row) {
        return java.util.Objects.equals(current.timeslotCode(), row.fromTimeslotCode())
                && java.util.Objects.equals(current.roomCode(), row.fromRoomCode())
                && java.util.Objects.equals(current.source(), row.fromSource())
                && (row.fromLocked() == null || current.locked() == row.fromLocked());
    }

    private void supersedeRedo(long versionId) {
        jdbc.update("UPDATE adjustment_command_group SET state = 'SUPERSEDED', updated_at = CURRENT_TIMESTAMP WHERE schedule_version_id = ? AND state = 'UNDONE'", versionId);
    }

    @Transactional
    public ScheduleVersionRepositoryModels.MutationResult undo(long versionId, UUID groupId, Long expectedRevision, String actor, String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        LockedVersion locked = lockEditableVersion(versionId, null, actor);
        ScheduleVersionRepositoryModels.MutationResult existing = eventResult(versionId, "UNDO", key);
        if (existing != null) return existing;
        requireExpectedRevision(versionId, locked.revision(), expectedRevision);
        Map<String, Object> group = commandGroup(groupId, versionId);
        if (!"APPLIED".equals(group.get("state"))) throw new VersionMutationException("COMMAND_NOT_APPLIED", versionId, locked.revision(), "该命令当前不可撤销");
        requireLatestGroup(versionId, groupId, "APPLIED");
        ScheduleVersionView currentVersion = findVersion(versionId);
        List<CommandRow> rows = commandRows(groupId);
        List<ScheduleAssignmentView> next = new ArrayList<>(currentVersion.assignments());
        List<ScheduleAssignmentView> restoredAssignments = new ArrayList<>();
        for (CommandRow row : rows) {
            ScheduleAssignmentView current = assignment(currentVersion, row.occurrenceId());
            if (!matchesAfter(current, row)) throw new VersionMutationException("COMMAND_STATE_CONFLICT", versionId, locked.revision(), "课表已发生其他变化，不能撤销");
            ScheduleAssignmentView restored = withSourceLocked(targetAssignment(versionId, current, row.fromTimeslotCode(), row.fromRoomCode()), row.fromSource(), row.fromLocked());
            restoredAssignments.add(restored);
            next.replaceAll(item -> item.occurrenceId() == row.occurrenceId() ? restored : item);
        }
        if (!ruleValidator.validateAssignments(versionId, next).isEmpty()) throw new IllegalArgumentException("撤销后违反排课规则");
        restoredAssignments.forEach(item -> updateAssignmentLocation(versionId, item));
        long resultRevision = locked.revision() + 1;
        jdbc.update("UPDATE adjustment_command_group SET state = 'UNDONE', updated_at = CURRENT_TIMESTAMP WHERE id = ?", groupId);
        updateRevision(versionId, locked.revision(), resultRevision, "DRAFT");
        insertCommandEvent(groupId, versionId, "UNDO", locked.revision(), resultRevision, key, actor);
        insertAudit(versionId, "ADJUSTMENT_UNDO", resultRevision, groupId.toString(), actor);
        return new ScheduleVersionRepositoryModels.MutationResult(groupId, rows.stream().map(CommandRow::id).toList(), resultRevision);
    }

    @Transactional
    public ScheduleVersionRepositoryModels.MutationResult redo(long versionId, UUID groupId, Long expectedRevision, String actor, String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        LockedVersion locked = lockEditableVersion(versionId, null, actor);
        ScheduleVersionRepositoryModels.MutationResult existing = eventResult(versionId, "REDO", key);
        if (existing != null) return existing;
        requireExpectedRevision(versionId, locked.revision(), expectedRevision);
        Map<String, Object> group = commandGroup(groupId, versionId);
        if (!"UNDONE".equals(group.get("state"))) throw new VersionMutationException("COMMAND_NOT_UNDONE", versionId, locked.revision(), "该命令当前不可重做");
        requireLatestGroup(versionId, groupId, "UNDONE");
        ScheduleVersionView currentVersion = findVersion(versionId);
        List<CommandRow> rows = commandRows(groupId);
        List<ScheduleAssignmentView> next = new ArrayList<>(currentVersion.assignments());
        List<ScheduleAssignmentView> appliedAssignments = new ArrayList<>();
        for (CommandRow row : rows) {
            ScheduleAssignmentView current = assignment(currentVersion, row.occurrenceId());
            if (!matchesBefore(current, row)) throw new VersionMutationException("COMMAND_STATE_CONFLICT", versionId, locked.revision(), "课表已发生其他变化，不能重做");
            ScheduleAssignmentView applied = withSourceLocked(targetAssignment(versionId, current, row.toTimeslotCode(), row.toRoomCode()), row.toSource(), row.toLocked());
            appliedAssignments.add(applied);
            next.replaceAll(item -> item.occurrenceId() == row.occurrenceId() ? applied : item);
        }
        if (!ruleValidator.validateAssignments(versionId, next).isEmpty()) throw new IllegalArgumentException("重做后违反排课规则");
        appliedAssignments.forEach(item -> updateAssignmentLocation(versionId, item));
        long resultRevision = locked.revision() + 1;
        jdbc.update("UPDATE adjustment_command_group SET state = 'APPLIED', updated_at = CURRENT_TIMESTAMP WHERE id = ?", groupId);
        updateRevision(versionId, locked.revision(), resultRevision, "DRAFT");
        insertCommandEvent(groupId, versionId, "REDO", locked.revision(), resultRevision, key, actor);
        insertAudit(versionId, "ADJUSTMENT_REDO", resultRevision, groupId.toString(), actor);
        return new ScheduleVersionRepositoryModels.MutationResult(groupId, rows.stream().map(CommandRow::id).toList(), resultRevision);
    }

    private void requireExpectedRevision(long versionId, long currentRevision, Long expectedRevision) {
        if (expectedRevision != null && expectedRevision.longValue() != currentRevision) {
            throw new VersionMutationException("VERSION_REVISION_CONFLICT", versionId, currentRevision, "版本已被其他操作更新，请重新加载");
        }
    }

    public List<ScheduleVersionRepositoryModels.CommandHistory> commandHistory(long versionId) {
        return jdbc.query(
                "SELECT id, command_type, base_revision, result_revision, state, idempotency_key, actor, reason, created_at FROM adjustment_command_group WHERE schedule_version_id = ? ORDER BY created_at DESC, id DESC",
                (rs, row) -> {
                    UUID groupId = (UUID) rs.getObject("id");
                    List<ScheduleVersionRepositoryModels.CommandItem> commands = jdbc.query("SELECT id, sequence, occurrence_id, from_timeslot_code, to_timeslot_code, from_room_code, to_room_code, from_source, to_source, from_locked, to_locked FROM adjustment_command WHERE command_group_id = ? ORDER BY sequence",
                            (child, childRow) -> new ScheduleVersionRepositoryModels.CommandItem(child.getLong("id"), child.getInt("sequence"), child.getLong("occurrence_id"), child.getString("from_timeslot_code"), child.getString("to_timeslot_code"), child.getString("from_room_code"), child.getString("to_room_code"), child.getString("from_source"), child.getString("to_source"), (Boolean) child.getObject("from_locked"), (Boolean) child.getObject("to_locked")), groupId);
                    return new ScheduleVersionRepositoryModels.CommandHistory(groupId, rs.getString("command_type"), rs.getLong("base_revision"), rs.getLong("result_revision"), rs.getString("state"), rs.getString("idempotency_key"), rs.getString("actor"), rs.getString("reason"), rs.getObject("created_at", OffsetDateTime.class), commands);
                }, versionId);
    }

    @Transactional
    public boolean lockVersion(long versionId, String owner, String reason, Long expectedRevision) {
        String normalizedOwner = owner == null || owner.isBlank() ? "planner" : owner.trim();
        Map<String, Object> row = versionRowForUpdate(versionId);
        long revision = ((Number) row.get("revision")).longValue();
        requireExpectedRevision(versionId, revision, expectedRevision);
        String status = String.valueOf(row.get("status"));
        if (!("DRAFT".equals(status) || "CANDIDATE".equals(status))) {
            throw new VersionMutationException("VERSION_NOT_EDITABLE", versionId, revision, "当前版本不可锁定: " + status);
        }
        if (Boolean.TRUE.equals(row.get("edit_locked"))) {
            if (normalizedOwner.equals(row.get("edit_lock_owner"))) return true;
            throw new VersionMutationException("VERSION_LOCKED", versionId, revision, "版本已被锁定: " + row.get("edit_lock_owner"));
        }
        int updated = jdbc.update("UPDATE schedule_version SET edit_locked = TRUE, edit_lock_owner = ?, edit_lock_owner_user_id = (SELECT id FROM app_user WHERE username = ?), edit_lock_reason = ?, edit_locked_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP, revision = revision + 1 WHERE id = ? AND revision = ?", normalizedOwner, normalizedOwner, reason == null ? "" : reason.trim(), versionId, revision);
        if (updated != 1) throw new VersionMutationException("VERSION_REVISION_CONFLICT", versionId, currentRevision(versionId), "版本已被其他操作更新，请重新加载");
        insertAudit(versionId, "VERSION_LOCK", revision + 1, null, normalizedOwner);
        return true;
    }

    @Transactional
    public boolean unlockVersion(long versionId, String owner, Long expectedRevision) {
        String normalizedOwner = owner == null || owner.isBlank() ? "planner" : owner.trim();
        Map<String, Object> row = versionRowForUpdate(versionId);
        long revision = ((Number) row.get("revision")).longValue();
        requireExpectedRevision(versionId, revision, expectedRevision);
        if (!Boolean.TRUE.equals(row.get("edit_locked"))) return true;
        if (!normalizedOwner.equals(row.get("edit_lock_owner"))) {
            throw new VersionMutationException("VERSION_LOCKED", versionId, revision, "只有锁定者可以解锁版本");
        }
        int updated = jdbc.update("UPDATE schedule_version SET edit_locked = FALSE, edit_lock_owner = NULL, edit_lock_owner_user_id = NULL, edit_lock_reason = NULL, edit_locked_at = NULL, updated_at = CURRENT_TIMESTAMP, revision = revision + 1 WHERE id = ? AND revision = ?", versionId, revision);
        if (updated != 1) throw new VersionMutationException("VERSION_REVISION_CONFLICT", versionId, currentRevision(versionId), "版本已被其他操作更新，请重新加载");
        insertAudit(versionId, "VERSION_UNLOCK", revision + 1, null, normalizedOwner);
        return true;
    }

    @Transactional
    public boolean archive(long versionId, Long expectedRevision, String actor) {
        Map<String, Object> row = versionRowForUpdate(versionId);
        long revision = ((Number) row.get("revision")).longValue();
        requireExpectedRevision(versionId, revision, expectedRevision);
        if (!"PUBLISHED".equals(row.get("status"))) {
            throw new VersionMutationException("VERSION_NOT_ARCHIVABLE", versionId, revision, "只有已发布版本可以归档");
        }
        int updated = jdbc.update("UPDATE schedule_version SET status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP, revision = revision + 1 WHERE id = ? AND status = 'PUBLISHED' AND revision = ?", versionId, revision);
        if (updated != 1) throw new VersionMutationException("VERSION_REVISION_CONFLICT", versionId, currentRevision(versionId), "版本已被其他操作更新，请重新加载");
        insertAudit(versionId, "ARCHIVE", revision + 1, null, actor == null ? "planner" : actor);
        return true;
    }

    private Map<String, Object> versionRowForUpdate(long versionId) {
        try {
            return jdbc.queryForMap("SELECT revision, status, edit_locked, edit_lock_owner FROM schedule_version WHERE id = ? FOR UPDATE", versionId);
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalArgumentException("版本不存在: " + versionId, exception);
        }
    }

    @Transactional
    public boolean publish(long versionId) {
        return publish(versionId, null, "planner");
    }

    @Transactional
    public boolean publish(long versionId, Long expectedRevision) {
        return publish(versionId, expectedRevision, "planner");
    }

    @Transactional
    public boolean publish(long versionId, Long expectedRevision, String actor) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("SELECT revision, status, edit_locked, legacy_identity_unverified FROM schedule_version WHERE id = ? FOR UPDATE", versionId);
        } catch (EmptyResultDataAccessException exception) {
            return false;
        }
        if (!"CANDIDATE".equals(row.get("status")) || Boolean.TRUE.equals(row.get("edit_locked"))) return false;
        if (Boolean.TRUE.equals(row.get("legacy_identity_unverified"))) {
            throw new VersionMutationException("LEGACY_IDENTITY_UNVERIFIED", versionId, ((Number) row.get("revision")).longValue(), "版本缺少可信的教学需求身份，请重新求解");
        }
        String snapshotTerm = jdbc.queryForObject("SELECT snapshot_term_code FROM schedule_version WHERE id = ?", String.class, versionId);
        String inputHash = jdbc.queryForObject("SELECT input_snapshot_hash FROM schedule_version WHERE id = ?", String.class, versionId);
        String ruleHash = jdbc.queryForObject("SELECT rule_snapshot_hash FROM schedule_version WHERE id = ?", String.class, versionId);
        long revision = ((Number) row.get("revision")).longValue();
        if (snapshotTerm != null && inputHash != null && ruleHash != null) {
            ScheduleSnapshotHashService.Snapshot current = snapshots.snapshot(snapshotTerm);
            if (!inputHash.equals(current.inputHash()) || !ruleHash.equals(current.ruleHash())) {
                throw new VersionMutationException("INPUT_SNAPSHOT_STALE", versionId, revision, "版本输入或规则已变化，请重新求解");
            }
        }
        ScheduleVersionView version = findVersion(versionId);
        if (!version.publishable()) return false;
        requireExpectedRevision(versionId, revision, expectedRevision);
        int updated = jdbc.update("UPDATE schedule_version SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP, revision = revision + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'CANDIDATE' AND revision = ?", versionId, revision);
        if (updated != 1) return false;
        insertAudit(versionId, "PUBLISH", revision + 1, null, actor);
        return true;
    }

    private ScheduleAssignmentView mapAssignment(ResultSet rs) throws SQLException {
        return new ScheduleAssignmentView(
                rs.getLong("occurrence_id"), rs.getString("subject_code"), rs.getString("subject_name"),
                rs.getString("teacher_code"), rs.getString("teacher_name"), rs.getString("student_group_code"),
                rs.getString("student_group_name"), rs.getString("timeslot_code"), rs.getString("timeslot_label"),
                rs.getInt("weekday"), rs.getInt("period_no"), rs.getString("room_code"), rs.getString("room_name"),
                rs.getString("source"), rs.getBoolean("locked"), rs.getInt("duration"), rs.getString("occurrence_key"),
                rs.getString("activity_group_code"), rs.getString("activity_type"), rs.getInt("student_count"),
                new java.util.LinkedHashSet<>(java.util.Arrays.asList((String[]) rs.getArray("required_features").getArray())),
                new java.util.LinkedHashSet<>(java.util.Arrays.asList((String[]) rs.getArray("room_features").getArray())),
                rs.getInt("room_capacity"), rs.getObject("teaching_requirement_id", Long.class), rs.getString("requirement_code"),
                rs.getInt("activity_index"), rs.getInt("activity_member_index"), rs.getString("pinned_period_code"), rs.getString("activity_type_snapshot"));
    }
}

