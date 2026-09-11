package com.classschedule.solver;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SolveReadinessService {
    private final JdbcTemplate jdbc;

    public SolveReadinessService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SolveReadiness check(String termCode) {
        String normalized = termCode == null ? "" : termCode.trim();
        List<SolveReadiness.Issue> issues = new ArrayList<>();
        if (normalized.isBlank()) {
            issues.add(new SolveReadiness.Issue("TERM_REQUIRED", "请选择有效学期"));
            return new SolveReadiness(normalized, false, 0, 0, 0, issues);
        }

        String status =
                jdbc
                        .query(
                                "SELECT status FROM academic_term WHERE code=?",
                                (rs, rowNum) -> rs.getString("status"),
                                normalized)
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (status == null) {
            issues.add(new SolveReadiness.Issue("TERM_NOT_FOUND", "学期不存在: " + normalized));
            return new SolveReadiness(normalized, false, 0, 0, 0, issues);
        }
        if ("ARCHIVED".equals(status)) {
            issues.add(new SolveReadiness.Issue("TERM_ARCHIVED", "归档学期不能排课: " + normalized));
            return new SolveReadiness(normalized, false, 0, 0, 0, issues);
        }

        Long termId =
                jdbc.queryForObject(
                        "SELECT id FROM academic_term WHERE code=?", Long.class, normalized);
        int timeslotCount = count("SELECT COUNT(*) FROM period_template WHERE term_id=?", termId);
        int roomCount = count("SELECT COUNT(*) FROM room WHERE active=TRUE");
        int requirementCount =
                count(
                        "SELECT COUNT(*) FROM teaching_requirement WHERE term_id=? AND active=TRUE",
                        termId);
        int missingHomeRoomCount =
                count(
                        "SELECT COUNT(*) FROM teaching_requirement r JOIN student_group g ON g.id=r.student_group_id LEFT JOIN room home ON home.id=g.home_room_id WHERE r.term_id=? AND r.active=TRUE AND r.room_assignment_mode='HOME' AND (home.id IS NULL OR home.active=FALSE)",
                        termId);
        if (timeslotCount == 0) issues.add(new SolveReadiness.Issue("NO_TIMESLOTS", "当前学期尚未配置节次"));
        if (roomCount == 0)
            issues.add(new SolveReadiness.Issue("NO_ACTIVE_ROOMS", "尚未配置启用教室，请先新增或导入教室"));
        if (requirementCount == 0)
            issues.add(
                    new SolveReadiness.Issue(
                            "NO_ACTIVE_REQUIREMENTS", "当前学期没有启用的教学需求，请先确认导入或新增教学需求"));
        if (missingHomeRoomCount > 0)
            issues.add(
                    new SolveReadiness.Issue(
                            "HOME_ROOM_NOT_CONFIGURED",
                            "有 " + missingHomeRoomCount + " 条行政班教学需求缺少有效的绑定教室"));
        return new SolveReadiness(
                normalized, issues.isEmpty(), timeslotCount, roomCount, requirementCount, issues);
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }
}
