package com.classschedule.importexport;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(username = "test-planner", roles = "PLANNER")
class WorkbookImportPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("class_schedule_test")
            .withUsername("class_schedule")
            .withPassword("class_schedule");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void previewThenConfirmImportsAllBusinessSheetsInOneFlow() throws Exception {
        MockMultipartFile file = workbook("T900", "G9-1", "SCI", "B201", "REQ-900", false);

        String preview = mockMvc.perform(multipart("/api/imports/preview").file(file).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.batchId").isNumber())
                .andReturn().getResponse().getContentAsString();
        long batchId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(preview).get("batchId").asLong();

        mockMvc.perform(post("/api/imports/confirm")
                        .with(csrf()).contentType("application/json")
                        .content("{\"batchId\":" + batchId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IMPORTED"))
                .andExpect(jsonPath("$.importedRows").value(5));

        org.assertj.core.api.Assertions.assertThat(count("teacher", "T900")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(count("student_group", "G9-1")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(count("subject", "SCI")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(count("room", "B201")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(count("teaching_requirement", "REQ-900")).isEqualTo(1);

        mockMvc.perform(post("/api/imports/confirm")
                        .with(csrf()).contentType("application/json")
                        .content("{\"batchId\":" + batchId + "}"))
                .andExpect(status().isConflict());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id = ?", String.class, batchId)).isEqualTo("IMPORTED");
    }

    @Test
    void confirmStateChangeIsAtomicForAlreadyImportedBatch() throws Exception {
        MockMultipartFile file = workbook("T902", "G9-3", "SCI3", "B203", "REQ-902", false);
        String preview = mockMvc.perform(multipart("/api/imports/preview").file(file).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long batchId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(preview).get("batchId").asLong();
        mockMvc.perform(post("/api/imports/confirm").with(csrf()).contentType("application/json").content("{\"batchId\":" + batchId + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/imports/confirm").with(csrf()).contentType("application/json").content("{\"batchId\":" + batchId + "}"))
                .andExpect(status().isConflict());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM teaching_requirement WHERE code = 'REQ-902'", Integer.class)).isEqualTo(1);
    }

    @Test
    void failedRequirementWriteRollsBackEarlierMasterDataWrites() throws Exception {
        MockMultipartFile file = workbook("T901", "G9-2", "SCI2", "B202", "R".repeat(65), false);
        String preview = mockMvc.perform(multipart("/api/imports/preview").file(file).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andReturn().getResponse().getContentAsString();
        long batchId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(preview).get("batchId").asLong();

        mockMvc.perform(post("/api/imports/confirm")
                        .with(csrf()).contentType("application/json")
                        .content("{\"batchId\":" + batchId + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"));

        org.assertj.core.api.Assertions.assertThat(count("teacher", "T901")).isZero();
        org.assertj.core.api.Assertions.assertThat(count("student_group", "G9-2")).isZero();
        org.assertj.core.api.Assertions.assertThat(count("subject", "SCI2")).isZero();
        org.assertj.core.api.Assertions.assertThat(count("room", "B202")).isZero();
    }

    private int count(String table, String code) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE code = ?", Integer.class, code);
    }

    private MockMultipartFile workbook(String teacher, String group, String subject, String room,
            String requirement, boolean duplicate) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            sheet(workbook, "教师", new String[] {"code", "name"}, new String[] {teacher, "导入教师"});
            sheet(workbook, "班级", new String[] {"code", "name"}, new String[] {group, "导入班级"});
            sheet(workbook, "课程", new String[] {"code", "name"}, new String[] {subject, "导入课程"});
            SheetBuilder roomSheet = new SheetBuilder(workbook, "教室", new String[] {"code", "name", "capacity"});
            roomSheet.row(room, "导入教室", "50");
            SheetBuilder requirementSheet = new SheetBuilder(workbook, "教学需求", new String[] {"code", "termCode", "studentGroupCode", "subjectCode", "teacherCode", "weeklyPeriods", "durationPeriods"});
            requirementSheet.row(requirement, "2026-FALL", group, subject, teacher, "1", "1");
            workbook.write(output);
            return new MockMultipartFile("file", "import.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    private void sheet(XSSFWorkbook workbook, String name, String[] headers, String[] values) {
        SheetBuilder builder = new SheetBuilder(workbook, name, headers);
        builder.row(values);
    }

    private static final class SheetBuilder {
        private final org.apache.poi.ss.usermodel.Sheet sheet;
        private int rowIndex = 0;

        private SheetBuilder(XSSFWorkbook workbook, String name, String[] headers) {
            sheet = workbook.createSheet(name);
            row(headers);
        }

        private void row(String... values) {
            var row = sheet.createRow(rowIndex++);
            for (int column = 0; column < values.length; column++) row.createCell(column).setCellValue(values[column]);
        }
    }
}
