package com.classschedule.importexport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ExternalTimetableConverterTest {
    @Test
    void convertsItc2007InstanceToImportableMasterDataWorkbook() throws Exception {
        Path directory = Files.createTempDirectory("itc-converter-test-");
        Path input = directory.resolve("comp01.ctt");
        Path output = directory.resolve("comp01.xlsx");
        Files.writeString(
                input,
                """
                Name: Tiny
                Courses: 2
                Rooms: 1
                Days: 5
                Periods_per_day: 2
                Curricula: 1
                Constraints: 1

                COURSES:
                c0001 t000 2 1 30
                c0002 t001 1 1 20

                ROOMS:
                r001 40

                CURRICULA:
                q000 2 c0001 c0002

                UNAVAILABILITY_CONSTRAINTS:
                c0001 0 0

                END.
                """,
                StandardCharsets.UTF_8);

        ExternalTimetableConverter.ConversionResult result =
                ExternalTimetableConverter.convert(
                        ExternalTimetableConverter.SourceFormat.ITC2007,
                        input,
                        output,
                        "2026-FALL",
                        "2026 秋季学期");

        assertThat(result.teacherCount()).isEqualTo(2);
        assertThat(result.studentGroupCount()).isEqualTo(1);
        assertThat(result.subjectCount()).isEqualTo(2);
        assertThat(result.roomCount()).isEqualTo(1);
        assertThat(result.requirementCount()).isEqualTo(2);
        assertThat(result.warnings()).anyMatch(item -> item.contains("不可用约束"));
        assertMasterDataWorkbook(output, 2, 1, 2, 1, 2);
    }

    @Test
    void convertsUnitimeZipAndKeepsCandidateSchedulingAsWarnings() throws Exception {
        Path directory = Files.createTempDirectory("unitime-converter-test-");
        Path input = directory.resolve("unitime.zip");
        Path output = directory.resolve("unitime.xlsx");
        String xml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <timetable term="2026-FALL">
                  <rooms><room id="1" capacity="30"/></rooms>
                  <classes>
                    <class id="10" offering="100" classLimit="25">
                      <instructor id="7"/><time days="1000000" start="1" length="1"/>
                    </class>
                    <class id="11" offering="100" classLimit="20"><time days="0100000" start="2" length="1"/></class>
                    <class id="12" parent="10" classLimit="25"/>
                  </classes>
                  <students><student id="1"/><student id="2"/></students>
                </timetable>
                """;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("tiny.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            Files.write(input, bytes.toByteArray());
        }

        ExternalTimetableConverter.ConversionResult result =
                ExternalTimetableConverter.convert(
                        ExternalTimetableConverter.SourceFormat.UNITIME,
                        input,
                        output,
                        "2026-FALL",
                        "2026 秋季学期");

        assertThat(result.teacherCount()).isEqualTo(2);
        assertThat(result.studentGroupCount()).isEqualTo(2);
        assertThat(result.subjectCount()).isEqualTo(1);
        assertThat(result.roomCount()).isEqualTo(1);
        assertThat(result.requirementCount()).isEqualTo(2);
        assertThat(result.warnings()).anyMatch(item -> item.contains("候选集合"));
        assertThat(result.warnings()).anyMatch(item -> item.contains("辅助 class"));
        assertMasterDataWorkbook(output, 2, 2, 1, 1, 2);
    }

    @Test
    void addsLogicalCapacityRoomWhenNoCandidateRoomCanHoldClassLimit() throws Exception {
        Path directory = Files.createTempDirectory("unitime-logical-room-test-");
        Path input = directory.resolve("unitime.zip");
        Path output = directory.resolve("unitime.xlsx");
        String xml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <timetable term="2026-FALL">
                  <rooms><room id="1" capacity="30"/></rooms>
                  <classes>
                    <class id="10" offering="100" classLimit="35" nrRooms="1">
                      <room id="1"/>
                    </class>
                  </classes>
                </timetable>
                """;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("tiny.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            Files.write(input, bytes.toByteArray());
        }

        ExternalTimetableConverter.ConversionResult result =
                ExternalTimetableConverter.convert(
                        ExternalTimetableConverter.SourceFormat.UNITIME,
                        input,
                        output,
                        "2026-FALL",
                        "2026 秋季学期");

        assertThat(result.roomCount()).isEqualTo(2);
        assertThat(result.warnings()).anyMatch(item -> item.contains("逻辑容量教室"));
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            org.apache.poi.ss.usermodel.Sheet rooms = workbook.getSheet("教室");
            assertThat(dataRows(rooms)).isEqualTo(2);
            assertThat(rooms.getRow(2).getCell(0).getStringCellValue())
                    .isEqualTo("UT-LOGICAL-ROOM-10");
            assertThat(rooms.getRow(2).getCell(2).getNumericCellValue()).isEqualTo(35);
        }
    }

    private void assertMasterDataWorkbook(
            Path path,
            int teacherRows,
            int groupRows,
            int subjectRows,
            int roomRows,
            int requirementRows)
            throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(path))) {
            assertThat(workbook.getNumberOfSheets())
                    .isEqualTo(MasterDataSchemaRegistry.SHEETS.size());
            for (int index = 0; index < MasterDataSchemaRegistry.SHEETS.size(); index++) {
                MasterDataSchemaRegistry.Sheet definition =
                        MasterDataSchemaRegistry.SHEETS.get(index);
                assertThat(workbook.getSheetAt(index).getSheetName()).isEqualTo(definition.name());
                RowHeader.assertMatches(workbook.getSheetAt(index), definition);
            }
            assertThat(dataRows(workbook.getSheet("教师"))).isEqualTo(teacherRows);
            assertThat(dataRows(workbook.getSheet("班级"))).isEqualTo(groupRows);
            assertThat(dataRows(workbook.getSheet("课程"))).isEqualTo(subjectRows);
            assertThat(dataRows(workbook.getSheet("教室"))).isEqualTo(roomRows);
            assertThat(dataRows(workbook.getSheet("教学需求"))).isEqualTo(requirementRows);
        }
    }

    private int dataRows(org.apache.poi.ss.usermodel.Sheet sheet) {
        int rows = 0;
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            if (sheet.getRow(index) != null
                    && sheet.getRow(index).getCell(0) != null
                    && !sheet.getRow(index).getCell(0).toString().isBlank()) rows++;
        }
        return rows;
    }

    private static final class RowHeader {
        private static void assertMatches(
                org.apache.poi.ss.usermodel.Sheet sheet,
                MasterDataSchemaRegistry.Sheet definition) {
            for (int column = 0; column < definition.headers().size(); column++) {
                assertThat(sheet.getRow(0).getCell(column).getStringCellValue())
                        .isEqualTo(definition.headers().get(column));
            }
        }
    }
}
