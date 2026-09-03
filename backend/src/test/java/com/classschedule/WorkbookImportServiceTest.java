package com.classschedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.classschedule.importexport.MasterDataSchemaRegistry;
import com.classschedule.importexport.WorkbookImportService;
import java.io.ByteArrayOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;

class WorkbookImportServiceTest {
    @Test
    void missingRequiredSheetsAreReported() throws Exception {
        JdbcTemplate jdbc = mockJdbc();
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            workbook.createSheet("教师");
            workbook.write(output);
            var preview = new WorkbookImportService(jdbc).preview(multipart(output, "input.xlsx"));
            assertThat(preview.status()).isEqualTo("INVALID");
            assertThat(preview.batchId()).isEqualTo(1L);
            assertThat(preview.issues())
                    .anyMatch(
                            issue ->
                                    issue.code().equals("MISSING_SHEET")
                                            && issue.sheet().equals("班级"));
        }
    }

    @Test
    void masterDataAllowsMissingOptionalSheets() throws Exception {
        JdbcTemplate jdbc = mockJdbc();
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            sheet(workbook, "说明", new String[] {"说明"}, new String[] {"MASTER_DATA v1"});
            sheet(
                    workbook,
                    "教师",
                    MasterDataSchemaRegistry.headers("教师").toArray(String[]::new),
                    new String[] {"T1", "教师", "TRUE"});
            sheet(
                    workbook,
                    "班级",
                    MasterDataSchemaRegistry.headers("班级").toArray(String[]::new),
                    new String[] {"G1", "班级", "HOMEROOM", "30", "TRUE"});
            sheet(
                    workbook,
                    "课程",
                    MasterDataSchemaRegistry.headers("课程").toArray(String[]::new),
                    new String[] {"S1", "课程", "TRUE"});
            sheet(
                    workbook,
                    "教学需求",
                    MasterDataSchemaRegistry.headers("教学需求").toArray(String[]::new),
                    new String[] {"R1", "2026-FALL", "G1", "S1", "T1", "1", "1", "30", "", "TRUE"});
            workbook.write(output);
            var preview =
                    new WorkbookImportService(jdbc).preview(multipart(output, "minimal.xlsx"));
            assertThat(preview.status()).isEqualTo("VALIDATED");
            assertThat(preview.issues()).isEmpty();
        }
    }

    @Test
    void masterDataStillRequiresCoreSheets() throws Exception {
        JdbcTemplate jdbc = mockJdbc();
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            sheet(workbook, "说明", new String[] {"说明"}, new String[] {"MASTER_DATA v1"});
            sheet(
                    workbook,
                    "教师",
                    MasterDataSchemaRegistry.headers("教师").toArray(String[]::new),
                    new String[] {"T1", "教师", "TRUE"});
            sheet(
                    workbook,
                    "课程",
                    MasterDataSchemaRegistry.headers("课程").toArray(String[]::new),
                    new String[] {"S1", "课程", "TRUE"});
            sheet(
                    workbook,
                    "教学需求",
                    MasterDataSchemaRegistry.headers("教学需求").toArray(String[]::new),
                    new String[] {"R1", "2026-FALL", "G1", "S1", "T1", "1", "1", "30", "", "TRUE"});
            workbook.write(output);
            var preview =
                    new WorkbookImportService(jdbc).preview(multipart(output, "missing-core.xlsx"));
            assertThat(preview.status()).isEqualTo("INVALID");
            assertThat(preview.issues())
                    .anyMatch(
                            issue ->
                                    issue.code().equals("MISSING_SHEET")
                                            && issue.sheet().equals("班级"));
        }
    }

    @Test
    void duplicateCodesAreReportedWithRow() throws Exception {
        JdbcTemplate jdbc = mockJdbc();
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("教师");
            sheet.createRow(0).createCell(0).setCellValue("code");
            sheet.createRow(1).createCell(0).setCellValue("T001");
            sheet.createRow(2).createCell(0).setCellValue("T001");
            workbook.write(output);
            var preview = new WorkbookImportService(jdbc).preview(multipart(output, "input.xlsx"));
            assertThat(preview.issues())
                    .anyMatch(issue -> issue.code().equals("DUPLICATE_CODE") && issue.row() == 3);
        }
    }

    @Test
    void rejectsFormulaCells() throws Exception {
        JdbcTemplate jdbc = mockJdbc();
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("说明");
            sheet.createRow(0).createCell(0).setCellValue("说明");
            sheet.createRow(1).createCell(0).setCellFormula("1+1");
            workbook.write(output);
            var preview = new WorkbookImportService(jdbc).preview(multipart(output, "input.xlsx"));
            assertThat(preview.issues())
                    .anyMatch(issue -> issue.code().equals("FORMULA_NOT_ALLOWED"));
        }
    }

    @Test
    void rejectsOverlongCellText() throws Exception {
        JdbcTemplate jdbc = mockJdbc();
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("说明");
            sheet.createRow(0).createCell(0).setCellValue("说明");
            sheet.createRow(1).createCell(0).setCellValue("x".repeat(257));
            workbook.write(output);
            var preview = new WorkbookImportService(jdbc).preview(multipart(output, "input.xlsx"));
            assertThat(preview.issues())
                    .anyMatch(issue -> issue.code().equals("CELL_TEXT_TOO_LONG"));
        }
    }

    @Test
    void rejectsSheetsAboveRowLimit() throws Exception {
        JdbcTemplate jdbc = mockJdbc();
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("说明");
            sheet.createRow(0).createCell(0).setCellValue("说明");
            sheet.createRow(10_001).createCell(0).setCellValue("x");
            workbook.write(output);
            var preview = new WorkbookImportService(jdbc).preview(multipart(output, "input.xlsx"));
            assertThat(preview.issues()).anyMatch(issue -> issue.code().equals("TOO_MANY_ROWS"));
        }
    }

    private JdbcTemplate mockJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(java.util.List.of("ACTIVE"));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);
        return jdbc;
    }

    private MockMultipartFile multipart(ByteArrayOutputStream output, String name) {
        return new MockMultipartFile(
                "file",
                name,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                output.toByteArray());
    }

    private void sheet(XSSFWorkbook workbook, String name, String[] headers, String[] values) {
        var sheet = workbook.createSheet(name);
        var header = sheet.createRow(0);
        for (int index = 0; index < headers.length; index++)
            header.createCell(index).setCellValue(headers[index]);
        var row = sheet.createRow(1);
        for (int index = 0; index < values.length; index++)
            row.createCell(index).setCellValue(values[index]);
    }
}
