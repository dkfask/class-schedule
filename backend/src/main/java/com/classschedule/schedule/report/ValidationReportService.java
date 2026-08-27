package com.classschedule.schedule.report;

import com.classschedule.schedule.ScheduleRepository;
import com.classschedule.schedule.ScheduleRuleValidator;
import com.classschedule.schedule.ScheduleVersionView;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class ValidationReportService {
    private final ScheduleRepository schedules;
    private final ScheduleRuleValidator validator;

    public ValidationReportService(ScheduleRepository schedules, ScheduleRuleValidator validator) {
        this.schedules = schedules;
        this.validator = validator;
    }

    public Report validate(long versionId) {
        ScheduleVersionView version = schedules.findVersion(versionId);
        List<Violation> violations = validator.validate(versionId, version).stream()
                .map(item -> new Violation(item.code(), item.severity(), item.weight(), item.blocking(), item.message(), item.resourceCode(), item.occurrenceKey()))
                .toList();
        return new Report(version.id(), version.revision(), version.status(), violations);
    }

    public byte[] xlsx(long versionId) {
        Report report = validate(versionId);
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("冲突报告");
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("课表版本 v" + report.versionId() + " / revision " + report.revision());
            title.createCell(1).setCellValue("生成时间");
            title.createCell(2).setCellValue(OffsetDateTime.now().toString());
            Row header = sheet.createRow(2);
            String[] columns = {"code", "severity", "weight", "blocking", "message", "resourceCode", "occurrenceKey"};
            for (int i = 0; i < columns.length; i++) header.createCell(i).setCellValue(columns[i]);
            int rowNumber = 3;
            for (Violation violation : report.violations()) {
                Row row = sheet.createRow(rowNumber++);
                String[] values = {violation.code(), violation.severity(), String.valueOf(violation.weight()), String.valueOf(violation.blocking()), violation.message(), violation.resourceCode(), violation.occurrenceKey()};
                for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i] == null ? "" : values[i]);
            }
            workbook.write(output);
            workbook.dispose();
            return output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("冲突报告导出失败", exception);
        }
    }

    public record Report(long versionId, long revision, String status, List<Violation> violations) {
        public boolean valid() { return violations.stream().noneMatch(Violation::blocking); }
    }

    public record Violation(String code, String severity, int weight, boolean blocking, String message, String resourceCode, String occurrenceKey) {}
}
