package com.classschedule.schedule.export;

import com.classschedule.schedule.ScheduleRepository;
import com.classschedule.schedule.report.ValidationReportService;
import java.util.Set;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schedule-versions")
public class ScheduleExportController {
    private final ScheduleRepository schedules;
    private final ScheduleExportService exports;
    private final SchedulePdfExportService pdfs;
    private final ValidationReportService reports;

    public ScheduleExportController(ScheduleRepository schedules, ScheduleExportService exports, SchedulePdfExportService pdfs, ValidationReportService reports) {
        this.schedules = schedules;
        this.exports = exports;
        this.pdfs = pdfs;
        this.reports = reports;
    }

    @GetMapping("/{versionId}/exports/xlsx")
    public ResponseEntity<byte[]> xlsx(@PathVariable long versionId,
            @RequestParam(defaultValue = "CLASS") String view,
            @RequestParam(required = false) String resourceCode, Authentication authentication) {
        if (!viewerCanRead(versionId, authentication)) return ResponseEntity.status(403).build();
        byte[] content = exports.xlsx(versionId, view, resourceCode);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("schedule-v" + versionId + ".xlsx").build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(content);
    }

    @GetMapping("/{versionId}/exports/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable long versionId,
            @RequestParam(defaultValue = "CLASS") String view,
            @RequestParam(required = false) String resourceCode, Authentication authentication) {
        if (!viewerCanRead(versionId, authentication)) return ResponseEntity.status(403).build();
        byte[] content = pdfs.pdf(versionId, view, resourceCode);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("schedule-v" + versionId + ".pdf").build().toString())
                .contentType(MediaType.APPLICATION_PDF).body(content);
    }

    @GetMapping("/{versionId}/validation")
    public ResponseEntity<?> validation(@PathVariable long versionId, Authentication authentication) {
        if (!viewerCanRead(versionId, authentication)) return ResponseEntity.status(403).body(java.util.Map.of("code", "VIEWER_PUBLISHED_ONLY"));
        return ResponseEntity.ok(reports.validate(versionId));
    }

    @GetMapping("/{versionId}/validation/export.xlsx")
    public ResponseEntity<byte[]> validationXlsx(@PathVariable long versionId, Authentication authentication) {
        if (!viewerCanRead(versionId, authentication)) return ResponseEntity.status(403).build();
        byte[] content = reports.xlsx(versionId);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("validation-v" + versionId + ".xlsx").build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(content);
    }

    @GetMapping("/{versionId}/validation/export.pdf")
    public ResponseEntity<byte[]> validationPdf(@PathVariable long versionId, Authentication authentication) {
        if (!viewerCanRead(versionId, authentication)) return ResponseEntity.status(403).build();
        byte[] content = pdfs.validationPdf(reports.validate(versionId));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("validation-v" + versionId + ".pdf").build().toString())
                .contentType(MediaType.APPLICATION_PDF).body(content);
    }

    @GetMapping("/{versionId}/print")
    public ResponseEntity<String> print(@PathVariable long versionId,
            @RequestParam(defaultValue = "CLASS") String view,
            @RequestParam(required = false) String resourceCode, Authentication authentication) {
        if (!viewerCanRead(versionId, authentication)) return ResponseEntity.status(403).build();
        var version = reports.validate(versionId);
        StringBuilder rows = new StringBuilder();
        for (var item : exports.assignments(versionId, view, resourceCode)) {
            rows.append("<tr><td>").append(escape(item.occurrenceKey())).append("</td><td>").append(escape(item.subjectName())).append("</td><td>").append(escape(item.teacherCode())).append("</td><td>").append(escape(item.studentGroupCode())).append("</td><td>").append(escape(item.timeslotCode())).append("</td><td>").append(escape(item.roomCode())).append("</td></tr>");
        }
        String html = "<!doctype html><html><head><meta charset=\"UTF-8\"><title>课表版本 v" + versionId + "</title><style>@media print{.no-print{display:none}}body{font-family:sans-serif}table{border-collapse:collapse;width:100%}th,td{border:1px solid #ccc;padding:6px}</style></head><body><h1>课表版本 v" + versionId + "</h1><p>状态：" + version.status() + " / revision " + version.revision() + "</p><p class=\"no-print\">请使用浏览器打印功能生成纸质课表。</p><table><thead><tr><th>任务</th><th>课程</th><th>教师</th><th>班级</th><th>节次</th><th>教室</th></tr></thead><tbody>" + rows + "</tbody></table></body></html>";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8")).body(html);
    }

    private boolean viewerCanRead(long versionId, Authentication authentication) {
        if (!isPublishedOnlyViewer(authentication)) return true;
        return Set.of("PUBLISHED").contains(schedules.findVersion(versionId).status());
    }

    private boolean isPublishedOnlyViewer(Authentication authentication) {
        if (authentication == null) return false;
        boolean viewer = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_VIEWER".equals(a.getAuthority()));
        boolean planner = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_PLANNER".equals(a.getAuthority()));
        return viewer && !planner;
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
