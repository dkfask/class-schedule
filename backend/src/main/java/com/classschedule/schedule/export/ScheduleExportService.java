package com.classschedule.schedule.export;

import com.classschedule.schedule.ScheduleAssignmentView;
import com.classschedule.schedule.ScheduleRepository;
import com.classschedule.schedule.ScheduleVersionView;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class ScheduleExportService {
    private final ScheduleRepository schedules;

    public ScheduleExportService(ScheduleRepository schedules) {
        this.schedules = schedules;
    }

    public List<ScheduleAssignmentView> assignments(long versionId, String view, String resourceCode) {
        return schedules.findVersionFiltered(versionId, view, resourceCode).assignments();
    }

    public byte[] xlsx(long versionId, String view, String resourceCode) {
        ScheduleVersionView version = schedules.findVersionFiltered(versionId, view, resourceCode);
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("课表");
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("课表版本 v" + version.id() + " / revision " + version.revision());
            title.createCell(1).setCellValue("生成时间");
            title.createCell(2).setCellValue(OffsetDateTime.now().toString());
            Row header = sheet.createRow(2);
            String[] columns = {"occurrenceKey", "课程编码", "课程名称", "教师", "班级", "节次", "教室", "来源", "锁定", "连续节数"};
            for (int i = 0; i < columns.length; i++) header.createCell(i).setCellValue(columns[i]);
            int rowNumber = 3;
            for (ScheduleAssignmentView item : version.assignments()) {
                Row row = sheet.createRow(rowNumber++);
                String[] values = {item.occurrenceKey(), item.subjectCode(), item.subjectName(), item.teacherCode(), item.studentGroupCode(), item.timeslotCode(), item.roomCode(), item.source(), String.valueOf(item.locked()), String.valueOf(item.duration())};
                for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i] == null ? "" : values[i]);
            }
            workbook.write(output);
            workbook.dispose();
            return output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("课表导出失败", exception);
        }
    }
}
