package com.classschedule.schedule.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(username = "export-planner", roles = "PLANNER")
class ScheduleExportIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("class_schedule_export").withUsername("class_schedule").withPassword("class_schedule");
    private static final String TEST_CJK_FONT = resolveTestFont();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        if (TEST_CJK_FONT != null) registry.add("app.pdf.font-path", () -> TEST_CJK_FONT);
    }
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedOwner() {
        jdbc.update("INSERT INTO app_user(username,password_hash,display_name) VALUES('export-planner','{noop}test','测试排课员') ON CONFLICT (username) DO NOTHING");
    }

    @Test
    void exportsXlsxPdfValidationAndPrintData() throws Exception {
        long version = seedCandidateVersion();
        byte[] xlsx = mockMvc.perform(get("/api/schedule-versions/" + version + "/exports/xlsx?view=CLASS"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx).isNotEmpty();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            assertThat(workbook.getSheet("课表")).isNotNull();
            assertThat(workbook.getSheet("课表").getRow(2).getCell(0).getStringCellValue()).isEqualTo("occurrenceKey");
        }
        byte[] pdf = mockMvc.perform(get("/api/schedule-versions/" + version + "/exports/pdf?view=CLASS"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(pdf).startsWith(new byte[]{37, 80, 68, 70});
        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            String extracted = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertThat(extracted).contains("v" + version, "revision 0", "T001", "G7-1", "MON-1", "A101");
            if (TEST_CJK_FONT == null) assertThat(extracted).contains("?");
            else assertThat(extracted).contains("课表版本", "数学");
        }
        mockMvc.perform(get("/api/schedule-versions/" + version + "/validation"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        mockMvc.perform(get("/api/schedule-versions/" + version + "/print?view=CLASS"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML)).andExpect(content().string(org.hamcrest.Matchers.containsString("课表版本")));
    }

    private static String resolveTestFont() {
        String configured = System.getenv("APP_PDF_FONT_PATH");
        String[] candidates = {
                configured,
                "/System/Library/Fonts/STHeiti Medium.ttc",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.otf",
                "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
                "C:/Windows/Fonts/msyh.ttc",
                "C:/Windows/Fonts/simhei.ttf"
        };
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) continue;
            try {
                if (Files.isRegularFile(Path.of(candidate))) return candidate;
            } catch (InvalidPathException ignored) {
                // Ignore an invalid optional path and continue with other platform candidates.
            }
        }
        return null;
    }

    private long seedCandidateVersion() {
        Long term = jdbc.queryForObject("SELECT id FROM academic_term WHERE code='2026-FALL'", Long.class);
        Long scenario = jdbc.queryForObject("INSERT INTO schedule_scenario(term_id,owner_user_id,name) VALUES(?,(SELECT id FROM app_user WHERE username='export-planner'), 'export-test') RETURNING id", Long.class, term);
        Long version = jdbc.queryForObject("INSERT INTO schedule_version(scenario_id,owner_user_id,status,score,legacy_identity_unverified) VALUES(?,(SELECT id FROM app_user WHERE username='export-planner'), 'CANDIDATE','0hard/0soft',FALSE) RETURNING id", Long.class, scenario);
        jdbc.update("INSERT INTO schedule_assignment(schedule_version_id,occurrence_id,occurrence_key,subject_code,subject_name,teacher_code,teacher_name,student_group_code,student_group_name,timeslot_code,timeslot_label,weekday,period_no,room_code,room_name,source,locked,duration,student_count,required_features,room_features,room_capacity) VALUES(?,1,'export-1','MATH','数学','T001','张老师','G7-1','七年级1班','MON-1','周一 第1节',1,1,'A101','教学楼 A101','SOLVER',false,1,0,'{}','{}',50)", version);
        return version;
    }
}
