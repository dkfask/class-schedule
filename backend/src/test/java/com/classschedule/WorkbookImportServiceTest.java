package com.classschedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.classschedule.importexport.WorkbookImportService;
import java.io.ByteArrayOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

class WorkbookImportServiceTest {
    @Test
    void missingRequiredSheetsAreReported() throws Exception {
        JdbcTemplate jdbc = mockJdbc();
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            workbook.createSheet("教师");
            workbook.write(output);
            var file = multipart(output, "input.xlsx");
            var preview = new WorkbookImportService(jdbc).preview(file);
            assertThat(preview.status()).isEqualTo("INVALID");
            assertThat(preview.batchId()).isEqualTo(1L);
            assertThat(preview.issues()).anyMatch(issue -> issue.code().equals("MISSING_SHEET") && issue.sheet().equals("班级"));
        }
    }

    @Test
    void duplicateCodesAreReportedWithRow() throws Exception {
        JdbcTemplate jdbc = mockJdbc();
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("教师");
            sheet.createRow(0).createCell(0).setCellValue("code");
            sheet.createRow(1).createCell(0).setCellValue("T001");
            sheet.createRow(2).createCell(0).setCellValue("T001");
            workbook.write(output);
            var preview = new WorkbookImportService(jdbc).preview(multipart(output, "input.xlsx"));
            assertThat(preview.issues()).anyMatch(issue -> issue.code().equals("DUPLICATE_CODE") && issue.row() == 3);
        }
    }

    private JdbcTemplate mockJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        return jdbc;
    }

    private MockMultipartFile multipart(ByteArrayOutputStream output, String name) {
        return new MockMultipartFile("file", name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
    }
}
