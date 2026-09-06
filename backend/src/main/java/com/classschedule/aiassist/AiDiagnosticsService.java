package com.classschedule.aiassist;

import com.classschedule.schedule.ScheduleAssignmentView;
import com.classschedule.schedule.ScheduleVersionView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * 本地规则引擎的候选方案诊断：不依赖外部服务，输出指标、发现与改进建议。
 * 规则阈值面向行政班排课的常见校园作息（5 天 × 6 节）。
 */
@Service
public class AiDiagnosticsService {
    static final int SLOTS_PER_ROOM = 30;
    static final int DAILY_LOAD_WARN = 5;
    static final int SAME_DAY_GAP_WARN = 2;

    public Map<String, Object> diagnose(ScheduleVersionView version) {
        List<ScheduleAssignmentView> assignments = version.assignments();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("versionId", version.id());
        result.put("status", version.status());
        result.put("score", version.score());

        int unplaced = 0;
        int roomless = 0;
        Map<String, Integer> teacherLoad = new TreeMap<>();
        Map<String, Integer> classLoad = new TreeMap<>();
        Map<String, Integer> roomUsage = new TreeMap<>();
        // key: teacher|weekday，值：该教师当天的节次序号（空档按天内计算，不能跨天）
        Map<String, List<Integer>> teacherDayPeriods = new LinkedHashMap<>();
        for (ScheduleAssignmentView item : assignments) {
            if (item.timeslotCode() == null) unplaced++;
            if (item.roomCode() == null || item.roomCode().isBlank()) roomless++;
            teacherLoad.merge(item.teacherCode(), 1, Integer::sum);
            classLoad.merge(item.studentGroupCode(), 1, Integer::sum);
            if (item.roomCode() != null && !item.roomCode().isBlank()) {
                roomUsage.merge(item.roomCode(), 1, Integer::sum);
            }
            if (item.timeslotCode() != null) {
                teacherDayPeriods
                        .computeIfAbsent(item.teacherCode() + "|" + item.weekday(), ignored -> new ArrayList<>())
                        .add(item.period());
            }
        }
        int periods = assignments.size();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("occurrences", periods);
        metrics.put("unplaced", unplaced);
        metrics.put("roomless", roomless);
        metrics.put("teachers", teacherLoad.size());
        metrics.put("studentGroups", classLoad.size());
        metrics.put("roomUtilization", roomUsage.entrySet().stream()
                .collect(LinkedHashMap::new,
                        (m, e) -> m.put(e.getKey(), Math.round(e.getValue() * 1000.0 / SLOTS_PER_ROOM) / 10.0),
                        Map::putAll));
        result.put("metrics", metrics);

        int hard = version.hardScore() == null ? 0 : version.hardScore();
        int medium = version.mediumScore() == null ? 0 : version.mediumScore();
        List<Map<String, Object>> findings = new ArrayList<>();
        if (hard > 0) {
            findings.add(finding(
                    "HIGH",
                    "存在 " + hard + " 个硬约束冲突",
                    "通常为教师/班级/教室时间冲突、教室容量或特征不满足，或存在未分配节次/教室的课次；硬冲突未清零前不能发布。"));
        }
        if (unplaced > 0) {
            findings.add(finding("HIGH", unplaced + " 个课次未获得时间", "这些课次完全没有排入节次，需要释放冲突资源或放宽固定节次后重新求解。"));
        }
        if (roomless > 0) {
            findings.add(finding("HIGH", roomless + " 个课次未分配教室", "课次已有时间但没有教室：教室容量或特征可能不足，或该节次教室已被占满。"));
        }
        if (medium > 0) {
            findings.add(finding("MEDIUM", "存在 " + medium + " 个中等级冲突", "中等冲突不阻塞发布，但会体现在校验报告中，可按需调整。"));
        }
        teacherLoad.entrySet().stream()
                .filter(entry -> entry.getValue() >= DAILY_LOAD_WARN * 2)
                .max(Map.Entry.comparingByValue())
                .ifPresent(entry ->
                        findings.add(finding("MEDIUM", "教师 " + entry.getKey() + " 周课时 " + entry.getValue() + " 节", "总课时偏高，注意单日连堂与工作量上限规则。")));
        teacherDayPeriods.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey().split("\\|", 2)[0], sameDayGaps(entry.getValue())))
                .max(Map.Entry.comparingByValue())
                .filter(entry -> entry.getValue() >= SAME_DAY_GAP_WARN)
                .ifPresent(entry ->
                        findings.add(finding("LOW", "教师 " + entry.getKey() + " 单日空档 " + entry.getValue() + " 节", "同一天课程之间存在空档，可尝试把相邻节次的课次前移以减少等待。")));
        if (roomUsage.size() > 1) {
            int max = roomUsage.values().stream().max(Integer::compare).orElse(0);
            int min = roomUsage.values().stream().min(Integer::compare).orElse(0);
            if (max >= min * 3) {
                findings.add(finding("LOW", "教室使用不均衡", "教室使用次数差异较大（最高 " + max + "，最低 " + min + "），可将部分课次换到空闲教室分摊损耗。"));
            }
        }
        if (findings.isEmpty()) {
            findings.add(finding("INFO", "未发现明显问题", "当前方案无硬冲突、负载与占用分布均衡；可发布或导出。"));
        }
        result.put("findings", findings);

        List<String> suggestions = new ArrayList<>();
        if (hard > 0 || unplaced > 0 || roomless > 0) {
            suggestions.add("先消除硬冲突：在工作台点击冲突课次，按预览提示更换节次或教室；必要时放宽固定节次。");
        }
        if (roomless > 0) {
            suggestions.add("可将未分配教室的课次调整到使用较少的教室，或检查教室容量与特征配置。");
        }
        if (medium > 0) {
            suggestions.add("按校验报告中的中等级冲突逐条评估是否需要调整。");
        }
        suggestions.add("调整后重新求解或运行校验报告；发布前确认方案完整度为 100%。");
        result.put("suggestions", suggestions);
        return result;
    }

    private static int sameDayGaps(List<Integer> dayPeriods) {
        List<Integer> sorted = dayPeriods.stream().distinct().sorted().toList();
        if (sorted.size() < 2) return 0;
        int max = 0;
        for (int i = 1; i < sorted.size(); i++) {
            max = Math.max(max, sorted.get(i) - sorted.get(i - 1) - 1);
        }
        return max;
    }

    private static Map<String, Object> finding(String severity, String title, String detail) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("severity", severity);
        finding.put("title", title);
        finding.put("detail", detail);
        return finding;
    }
}
