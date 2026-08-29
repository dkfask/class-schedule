package com.classschedule.importexport;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
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
@WithMockUser(username = "test-planner", authorities = {"ROLE_PLANNER", "IMPORT_EXECUTE"})
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

    @BeforeEach
    void ensureTestPlannerOwner() {
        jdbc.update("INSERT INTO app_user(username,password_hash,display_name,enabled) VALUES(?,?,?,TRUE) ON CONFLICT (username) DO NOTHING",
                "test-planner", "{noop}test", "测试排课员");
        jdbc.update("INSERT INTO app_user_role(user_id,role_id) SELECT u.id,r.id FROM app_user u CROSS JOIN app_role r WHERE u.username=? AND r.code='PLANNER' ON CONFLICT DO NOTHING", "test-planner");
    }

    @Test
    void templateDownloadExposesStableMasterDataContract() throws Exception {
        mockMvc.perform(get("/api/imports/templates/master-data.xlsx"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Disposition", org.hamcrest.Matchers.containsString("master-data-v1.xlsx")))
                .andExpect(result -> {
                    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
                        org.assertj.core.api.Assertions.assertThat(workbook.getNumberOfSheets()).isEqualTo(MasterDataSchemaRegistry.SHEETS.size());
                        for (int index = 0; index < MasterDataSchemaRegistry.SHEETS.size(); index++) {
                            var definition = MasterDataSchemaRegistry.SHEETS.get(index);
                            org.assertj.core.api.Assertions.assertThat(workbook.getSheetAt(index).getSheetName()).isEqualTo(definition.name());
                            var header = workbook.getSheetAt(index).getRow(0);
                            for (int column = 0; column < definition.headers().size(); column++) {
                                org.assertj.core.api.Assertions.assertThat(header.getCell(column).getStringCellValue()).isEqualTo(definition.headers().get(column));
                            }
                        }
                    }
                });
    }

    @Test
    void importEndpointsRequireImportPermission() throws Exception {
        mockMvc.perform(get("/api/imports/templates/master-data.xlsx").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/imports/templates/master-data.xlsx").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("planner").roles("PLANNER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/imports/templates/master-data.xlsx"))
                .andExpect(status().isOk());
    }

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
    void previewThenConfirmImportsMasterDataV1AcrossAllSheets() throws Exception {
        MockMultipartFile file = masterDataWorkbook();

        String preview = mockMvc.perform(multipart("/api/imports/preview").file(file).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.templateType").value("MASTER_DATA"))
                .andExpect(jsonPath("$.templateVersion").value("v1"))
                .andExpect(jsonPath("$.schemaHash").value(MasterDataSchemaRegistry.schemaHash()))
                .andExpect(jsonPath("$.sheets[0]").value("说明"))
                .andExpect(jsonPath("$.sheets[10]").value("活动组"))
                .andReturn().getResponse().getContentAsString();
        long batchId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(preview).get("batchId").asLong();

        mockMvc.perform(post("/api/imports/confirm")
                        .with(csrf()).contentType("application/json")
                        .content("{\"batchId\":" + batchId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IMPORTED"))
                .andExpect(jsonPath("$.importedRows").value(13))
                .andExpect(jsonPath("$.sheetStats[1].sheet").value("教师"))
                .andExpect(jsonPath("$.sheetStats[1].rows").value(1))
                .andExpect(jsonPath("$.sheetStats[10].rows").value(2));

        org.assertj.core.api.Assertions.assertThat(count("teacher", "T910")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT active FROM teacher WHERE code='T910'", Boolean.class)).isTrue();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT student_count FROM student_group WHERE code='G9-10'", Integer.class)).isEqualTo(36);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT room_type FROM room WHERE code='B210'", String.class)).isEqualTo("实验室");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT student_count FROM teaching_requirement WHERE code='REQ-910'", Integer.class)).isEqualTo(30);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT pinned_period_code FROM teaching_requirement WHERE code='REQ-910'", String.class)).isEqualTo("MON-1");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT available FROM teacher_availability a JOIN teacher t ON t.id=a.teacher_id JOIN academic_term term ON term.id=a.term_id WHERE t.code='T910' AND term.code='2026-FALL' AND a.period_code='MON-1'", Boolean.class)).isFalse();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT available FROM room_availability a JOIN room r ON r.id=a.room_id JOIN academic_term term ON term.id=a.term_id WHERE r.code='B210' AND term.code='2026-FALL' AND a.period_code='MON-2'", Boolean.class)).isTrue();
        org.assertj.core.api.Assertions.assertThat(count("room_feature_catalog", "LAB")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_feature rf JOIN room r ON r.id=rf.room_id WHERE r.code='B210' AND rf.feature_code='LAB'", Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM teaching_requirement_feature f JOIN teaching_requirement r ON r.id=f.teaching_requirement_id WHERE r.code='REQ-910' AND f.feature_code='LAB'", Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity_group_member m JOIN activity_group g ON g.id=m.activity_group_id WHERE g.code='ACT-910'", Integer.class)).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT member_index FROM activity_group_member m JOIN activity_group g ON g.id=m.activity_group_id JOIN teaching_requirement r ON r.id=m.teaching_requirement_id WHERE g.code='ACT-910' AND r.code='REQ-911'", Integer.class)).isEqualTo(1);
    }

    @Test
    void masterDataWithoutOptionalSheetsImportsCoreDataAndPreservesExistingRoom() throws Exception {
        jdbc.update("INSERT INTO room(code,name,capacity,room_type,active) VALUES(?,?,?,?,TRUE) ON CONFLICT (code) DO NOTHING",
                "KEEP-ROOM", "已有教室", 40, "普通教室");
        MockMultipartFile file = minimalMasterDataWorkbook("MIN-1");

        String preview = mockMvc.perform(multipart("/api/imports/preview").file(file).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.sheets").value(org.hamcrest.Matchers.contains("说明", "教师", "班级", "课程", "教学需求")))
                .andReturn().getResponse().getContentAsString();
        long batchId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(preview).get("batchId").asLong();

        mockMvc.perform(post("/api/imports/confirm")
                        .with(csrf()).contentType("application/json")
                        .content("{\"batchId\":" + batchId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IMPORTED"))
                .andExpect(jsonPath("$.importedRows").value(4))
                .andExpect(jsonPath("$.sheetStats[4].rows").value(0))
                .andExpect(jsonPath("$.sheetStats[6].rows").value(0))
                .andExpect(jsonPath("$.sheetStats[10].rows").value(0));

        org.assertj.core.api.Assertions.assertThat(count("teacher", "T-MIN-1")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(count("student_group", "G-MIN-1")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(count("subject", "SUB-MIN-1")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(count("teaching_requirement", "REQ-MIN-1")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(count("room", "KEEP-ROOM")).isEqualTo(1);
    }

    @Test
    void masterDataMissingRequiredSheetIsRejected() throws Exception {
        MockMultipartFile file = masterDataWithoutGroupSheet("MISSING-1");

        mockMvc.perform(multipart("/api/imports/preview").file(file).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVALID"))
                .andExpect(jsonPath("$.issues[?(@.code == 'MISSING_SHEET')]").isNotEmpty());
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

    @Test
    void masterDataRequiresChineseHeaders() throws Exception {
        MockMultipartFile chinese = masterDataWorkbook();
        mockMvc.perform(multipart("/api/imports/preview").file(chinese).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(chinese.getBytes()));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.getSheet("教师").getRow(0).getCell(0).setCellValue("code");
            workbook.write(output);
            MockMultipartFile english = new MockMultipartFile("file", "master-data-v1.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
            mockMvc.perform(multipart("/api/imports/preview").file(english).with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("INVALID"))
                    .andExpect(jsonPath("$.issues[?(@.code == 'INVALID_HEADER')]").isNotEmpty());
        }
    }

    private int count(String table, String code) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE code = ?", Integer.class, code);
    }

    private MockMultipartFile minimalMasterDataWorkbook(String suffix) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            sheet(workbook, "说明", new String[] {"说明"}, new String[] {"MASTER_DATA v1 minimal fixture"});
            sheet(workbook, "教师", MasterDataSchemaRegistry.headers("教师").toArray(String[]::new), new String[] {"T-" + suffix, "导入教师", "TRUE"});
            sheet(workbook, "班级", MasterDataSchemaRegistry.headers("班级").toArray(String[]::new), new String[] {"G-" + suffix, "导入班级", "HOMEROOM", "30", "TRUE"});
            sheet(workbook, "课程", MasterDataSchemaRegistry.headers("课程").toArray(String[]::new), new String[] {"SUB-" + suffix, "导入课程", "TRUE"});
            sheet(workbook, "教学需求", MasterDataSchemaRegistry.headers("教学需求").toArray(String[]::new), new String[] {"REQ-" + suffix, "2026-FALL", "G-" + suffix, "SUB-" + suffix, "T-" + suffix, "1", "1", "30", "", "TRUE"});
            workbook.write(output);
            return new MockMultipartFile("file", "master-data-minimal.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    private MockMultipartFile masterDataWithoutGroupSheet(String suffix) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            sheet(workbook, "说明", new String[] {"说明"}, new String[] {"MASTER_DATA v1 invalid fixture"});
            sheet(workbook, "教师", MasterDataSchemaRegistry.headers("教师").toArray(String[]::new), new String[] {"T-" + suffix, "导入教师", "TRUE"});
            sheet(workbook, "课程", MasterDataSchemaRegistry.headers("课程").toArray(String[]::new), new String[] {"SUB-" + suffix, "导入课程", "TRUE"});
            sheet(workbook, "教学需求", MasterDataSchemaRegistry.headers("教学需求").toArray(String[]::new), new String[] {"REQ-" + suffix, "2026-FALL", "G-" + suffix, "SUB-" + suffix, "T-" + suffix, "1", "1", "30", "", "TRUE"});
            workbook.write(output);
            return new MockMultipartFile("file", "master-data-invalid.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    private MockMultipartFile masterDataWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            sheet(workbook, "说明", new String[] {"说明"}, new String[] {"MASTER_DATA v1 test fixture"});
            sheet(workbook, "教师", MasterDataSchemaRegistry.headers("教师").toArray(String[]::new), new String[] {"T910", "实验教师", "TRUE"});
            sheet(workbook, "班级", MasterDataSchemaRegistry.headers("班级").toArray(String[]::new), new String[] {"G9-10", "九年级10班", "HOMEROOM", "36", "TRUE"});
            sheet(workbook, "课程", MasterDataSchemaRegistry.headers("课程").toArray(String[]::new), new String[] {"SCI10", "科学", "TRUE"});
            sheet(workbook, "教室", MasterDataSchemaRegistry.headers("教室").toArray(String[]::new), new String[] {"B210", "实验室 B210", "40", "实验室", "TRUE"});
            SheetBuilder requirements = new SheetBuilder(workbook, "教学需求", MasterDataSchemaRegistry.headers("教学需求").toArray(String[]::new));
            requirements.row("REQ-910", "2026-FALL", "G9-10", "SCI10", "T910", "2", "1", "30", "MON-1", "TRUE");
            requirements.row("REQ-911", "2026-FALL", "G9-10", "SCI10", "T910", "1", "1", "30", "", "TRUE");
            SheetBuilder availability = new SheetBuilder(workbook, "资源可用性", MasterDataSchemaRegistry.headers("资源可用性").toArray(String[]::new));
            availability.row("TEACHER", "T910", "2026-FALL", "MON-1", "FALSE");
            availability.row("ROOM", "B210", "2026-FALL", "MON-2", "TRUE");
            sheet(workbook, "特征目录", MasterDataSchemaRegistry.headers("特征目录").toArray(String[]::new), new String[] {"LAB", "实验室", "TRUE"});
            sheet(workbook, "教室特征", MasterDataSchemaRegistry.headers("教室特征").toArray(String[]::new), new String[] {"B210", "LAB", "TRUE"});
            sheet(workbook, "教学需求特征", MasterDataSchemaRegistry.headers("教学需求特征").toArray(String[]::new), new String[] {"REQ-910", "LAB", "TRUE"});
            SheetBuilder activities = new SheetBuilder(workbook, "活动组", MasterDataSchemaRegistry.headers("活动组").toArray(String[]::new));
            activities.row("ACT-910", "科学同步", "SYNCHRONIZED", "2026-FALL", "0", "REQ-910", "TRUE");
            activities.row("ACT-910", "科学同步", "SYNCHRONIZED", "2026-FALL", "1", "REQ-911", "TRUE");
            workbook.write(output);
            return new MockMultipartFile("file", "master-data-v1.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
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
