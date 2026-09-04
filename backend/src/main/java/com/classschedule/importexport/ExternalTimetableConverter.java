package com.classschedule.importexport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Converts public timetable benchmark formats into the project's MASTER_DATA v1 workbook. */
public final class ExternalTimetableConverter {
    private static final String DEFAULT_TERM_CODE = "2026-FALL";
    private static final String DEFAULT_TERM_NAME = "2026 秋季学期";

    private ExternalTimetableConverter() {}

    public enum SourceFormat {
        UNITIME,
        ITC2007
    }

    public record ConversionResult(
            SourceFormat sourceFormat,
            int teacherCount,
            int studentGroupCount,
            int subjectCount,
            int roomCount,
            int requirementCount,
            List<String> warnings) {}

    private record Options(
            SourceFormat sourceFormat, Path input, Path output, String termCode, String termName) {}

    private record Teacher(String code, String name) {}

    private record StudentGroup(String code, String name, int studentCount) {}

    private record Subject(String code, String name) {}

    private record Room(String code, String name, int capacity) {}

    private record Requirement(
            String code,
            String groupCode,
            String subjectCode,
            String teacherCode,
            int weeklyPeriods,
            int studentCount) {}

    private static final class ModelData {
        private final LinkedHashMap<String, Teacher> teachers = new LinkedHashMap<>();
        private final LinkedHashMap<String, StudentGroup> studentGroups = new LinkedHashMap<>();
        private final LinkedHashMap<String, Subject> subjects = new LinkedHashMap<>();
        private final LinkedHashMap<String, Room> rooms = new LinkedHashMap<>();
        private final List<Requirement> requirements = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
    }

    private record ItcCourse(
            String code,
            String teacherCode,
            int lecturesPerWeek,
            int minWorkingDays,
            int studentCount) {}

    private record ItcRoom(String code, int capacity) {}

    private record ItcData(
            String name,
            Map<String, ItcCourse> courses,
            Map<String, ItcRoom> rooms,
            Map<String, List<String>> curricula,
            int unavailabilityCount) {}

    private record UnitimeRoom(String id, int capacity) {}

    private record UnitimeClass(
            String id,
            String offeringId,
            String instructorId,
            int classLimit,
            List<String> roomIds,
            int requestedRoomCount) {}

    private record UnitimeData(
            String term,
            List<UnitimeRoom> rooms,
            List<UnitimeClass> classes,
            int studentCount,
            int skippedClassCount) {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || contains(args, "--help")) {
            printUsage();
            return;
        }
        Options options = parseOptions(args);
        ConversionResult result =
                convert(
                        options.sourceFormat(),
                        options.input(),
                        options.output(),
                        options.termCode(),
                        options.termName());
        System.out.printf(
                Locale.ROOT,
                "converted source=%s teachers=%d groups=%d subjects=%d rooms=%d requirements=%d output=%s%n",
                result.sourceFormat(),
                result.teacherCount(),
                result.studentGroupCount(),
                result.subjectCount(),
                result.roomCount(),
                result.requirementCount(),
                options.output().toAbsolutePath());
        for (String warning : result.warnings()) {
            System.out.println("warning: " + warning);
        }
    }

    public static ConversionResult convert(
            SourceFormat sourceFormat, Path input, Path output, String termCode, String termName)
            throws IOException {
        if (sourceFormat == null) throw new IllegalArgumentException("sourceFormat is required");
        if (input == null || !Files.isRegularFile(input)) {
            throw new IllegalArgumentException("输入文件不存在: " + input);
        }
        if (output == null) throw new IllegalArgumentException("输出文件不能为空");
        String resolvedTermCode = requireText(termCode, DEFAULT_TERM_CODE);
        String resolvedTermName = requireText(termName, DEFAULT_TERM_NAME);

        ModelData model =
                switch (sourceFormat) {
                    case ITC2007 -> buildItcModel(parseItc2007(input));
                    case UNITIME -> buildUnitimeModel(parseUnitime(input));
                };
        writeWorkbook(model, sourceFormat, input, output, resolvedTermCode, resolvedTermName);
        return new ConversionResult(
                sourceFormat,
                model.teachers.size(),
                model.studentGroups.size(),
                model.subjects.size(),
                model.rooms.size(),
                model.requirements.size(),
                List.copyOf(model.warnings));
    }

    private static ModelData buildItcModel(ItcData data) {
        ModelData model = new ModelData();
        Set<String> teacherCodes = new LinkedHashSet<>();
        for (ItcCourse course : data.courses().values()) {
            teacherCodes.add(course.teacherCode());
        }
        for (String teacherCode : teacherCodes) {
            model.teachers.put(teacherCode, new Teacher(teacherCode, "教师 " + teacherCode));
        }
        for (ItcRoom room : data.rooms().values()) {
            model.rooms.put(
                    room.code(),
                    new Room(room.code(), "教室 " + room.code(), Math.max(1, room.capacity())));
        }

        Map<String, String> primaryCurriculumByCourse = new LinkedHashMap<>();
        int droppedMemberships = 0;
        for (Map.Entry<String, List<String>> entry : data.curricula().entrySet()) {
            String curriculumCode = entry.getKey();
            String groupCode = "ITC-GROUP-" + curriculumCode;
            model.studentGroups.putIfAbsent(
                    groupCode, new StudentGroup(groupCode, "课程组 " + curriculumCode, 0));
            for (String courseCode : entry.getValue()) {
                if (primaryCurriculumByCourse.putIfAbsent(courseCode, groupCode) != null)
                    droppedMemberships++;
            }
        }

        int syntheticGroups = 0;
        for (ItcCourse course : data.courses().values()) {
            String subjectCode = "ITC-SUBJECT-" + course.code();
            model.subjects.put(subjectCode, new Subject(subjectCode, "课程 " + course.code()));
            String groupCode = primaryCurriculumByCourse.get(course.code());
            if (groupCode == null) {
                groupCode = "ITC-COURSE-GROUP-" + course.code();
                model.studentGroups.put(
                        groupCode,
                        new StudentGroup(
                                groupCode, "课程班级 " + course.code(), course.studentCount()));
                syntheticGroups++;
            }
            model.requirements.add(
                    new Requirement(
                            "ITC-REQ-" + course.code(),
                            groupCode,
                            subjectCode,
                            course.teacherCode(),
                            Math.max(1, course.lecturesPerWeek()),
                            Math.max(0, course.studentCount())));
        }
        if (droppedMemberships > 0) {
            model.warnings.add(
                    "ITC2007 的 "
                            + droppedMemberships
                            + " 个额外课程组关系未导入；当前 MASTER_DATA v1 每条教学需求只能关联一个班级，已保留每门课程首次出现的课程组。");
        }
        if (syntheticGroups > 0) {
            model.warnings.add("ITC2007 有 " + syntheticGroups + " 门课程未出现在课程组中，已生成独立的合成班级。");
        }
        if (data.unavailabilityCount() > 0) {
            model.warnings.add(
                    "ITC2007 的 "
                            + data.unavailabilityCount()
                            + " 条课程不可用约束未导入；当前模板只支持教师、教室和班级资源可用性。");
        }
        model.warnings.add("ITC2007 输入文件只包含问题实例，不包含已排课结果；课程组关系采用降级映射，不能代表原竞赛语义的完整约束。");
        return model;
    }

    private static ModelData buildUnitimeModel(UnitimeData data) {
        ModelData model = new ModelData();
        Map<String, Integer> roomCapacities = new LinkedHashMap<>();
        for (UnitimeRoom room : data.rooms()) {
            String code = "UT-ROOM-" + room.id();
            roomCapacities.put(room.id(), Math.max(1, room.capacity()));
            model.rooms.put(
                    code, new Room(code, "UniTime 教室 " + room.id(), Math.max(1, room.capacity())));
        }

        Set<String> instructorCodes = new LinkedHashSet<>();
        int classesWithoutInstructor = 0;
        for (UnitimeClass timetableClass : data.classes()) {
            if (timetableClass.instructorId() == null || timetableClass.instructorId().isBlank()) {
                classesWithoutInstructor++;
                instructorCodes.add("UT-CLASS-TEACHER-" + timetableClass.id());
            } else {
                instructorCodes.add("UT-TEACHER-" + timetableClass.instructorId());
            }
        }
        for (String code : instructorCodes) {
            model.teachers.put(
                    code,
                    new Teacher(
                            code,
                            code.startsWith("UT-CLASS-")
                                    ? "未指定教师 " + code.substring(code.lastIndexOf('-') + 1)
                                    : "UniTime 教师 " + code.substring("UT-TEACHER-".length())));
        }

        Set<String> offeringCodes = new LinkedHashSet<>();
        for (UnitimeClass timetableClass : data.classes()) {
            offeringCodes.add(timetableClass.offeringId());
        }
        for (String offeringCode : offeringCodes) {
            String code = "UT-SUBJECT-" + offeringCode;
            model.subjects.put(code, new Subject(code, "UniTime 课程 " + offeringCode));
        }

        int logicalRoomCount = 0;
        int explicitNoRoomCount = 0;
        for (UnitimeClass timetableClass : data.classes()) {
            String groupCode = "UT-GROUP-" + timetableClass.id();
            String teacherCode =
                    timetableClass.instructorId() == null || timetableClass.instructorId().isBlank()
                            ? "UT-CLASS-TEACHER-" + timetableClass.id()
                            : "UT-TEACHER-" + timetableClass.instructorId();
            model.studentGroups.put(
                    groupCode,
                    new StudentGroup(
                            groupCode,
                            "UniTime 班次 " + timetableClass.id(),
                            Math.max(0, timetableClass.classLimit())));
            int largestCandidateCapacity =
                    timetableClass.roomIds().stream()
                            .map(roomCapacities::get)
                            .filter(java.util.Objects::nonNull)
                            .mapToInt(Integer::intValue)
                            .max()
                            .orElse(0);
            if (timetableClass.roomIds().isEmpty()
                    && timetableClass.requestedRoomCount() == 0) {
                explicitNoRoomCount++;
            } else if (timetableClass.classLimit() > largestCandidateCapacity) {
                String logicalRoomCode = "UT-LOGICAL-ROOM-" + timetableClass.id();
                model.rooms.put(
                        logicalRoomCode,
                        new Room(
                                logicalRoomCode,
                                "UniTime 逻辑容量教室 " + timetableClass.id(),
                                Math.max(1, timetableClass.classLimit())));
                logicalRoomCount++;
            }
            model.requirements.add(
                    new Requirement(
                            "UT-REQ-" + timetableClass.id(),
                            groupCode,
                            "UT-SUBJECT-" + timetableClass.offeringId(),
                            teacherCode,
                            1,
                            Math.max(0, timetableClass.classLimit())));
        }
        if (logicalRoomCount > 0) {
            model.warnings.add(
                    "UniTime 有 "
                            + logicalRoomCount
                            + " 个班次的 classLimit 超出单个候选教室容量；当前模型只支持单教室，已生成同班次专用的逻辑容量教室以保留人数硬约束。逻辑容量教室不是原始物理教室。");
        }
        if (explicitNoRoomCount > 0) {
            model.warnings.add(
                    "UniTime 有 "
                            + explicitNoRoomCount
                            + " 个班次明确声明无需教室；当前模板没有独立的无需教室字段，已保留零人数占位并由求解器完成时间分配，不能据此解释为物理教室安排。");
        }
        if (classesWithoutInstructor > 0) {
            model.warnings.add(
                    "UniTime 有 " + classesWithoutInstructor + " 个班次没有授课教师，已为每个班次生成独立的合成教师。");
        }
        if (data.skippedClassCount() > 0) {
            model.warnings.add(
                    "UniTime 有 "
                            + data.skippedClassCount()
                            + " 个 class 缺少 id 或 offering 属性，已按辅助 class 跳过。");
        }
        model.warnings.add("UniTime 的时间和教室是候选集合，未将第一个候选项伪装成固定节次或固定教室；需要通过专用适配器才能保留这些偏好和约束。");
        model.warnings.add("UniTime 班次已映射为独立合成班级，学生选课冲突和多班级同步关系未导入。");
        if (data.studentCount() > 0) {
            model.warnings.add(
                    "UniTime 原始数据包含 "
                            + data.studentCount()
                            + " 名学生，但当前 MASTER_DATA v1 没有学生明细表，因此仅保留班次人数上限。");
        }
        return model;
    }

    private static void writeWorkbook(
            ModelData model,
            SourceFormat sourceFormat,
            Path input,
            Path output,
            String termCode,
            String termName)
            throws IOException {
        Path absoluteOutput = output.toAbsolutePath();
        Path parent = absoluteOutput.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                OutputStream stream = Files.newOutputStream(absoluteOutput)) {
            for (MasterDataSchemaRegistry.Sheet definition : MasterDataSchemaRegistry.SHEETS) {
                Sheet sheet = workbook.createSheet(definition.name());
                Row header = sheet.createRow(0);
                for (int column = 0; column < definition.headers().size(); column++) {
                    header.createCell(column).setCellValue(definition.headers().get(column));
                }
                sheet.createFreezePane(0, 1);
                writeSheetRows(
                        sheet, definition.name(), model, sourceFormat, input, termCode, termName);
            }
            workbook.write(stream);
        }
    }

    private static void writeSheetRows(
            Sheet sheet,
            String sheetName,
            ModelData model,
            SourceFormat sourceFormat,
            Path input,
            String termCode,
            String termName) {
        int rowIndex = 1;
        switch (sheetName) {
            case "说明" -> {
                put(sheet, rowIndex++, 0, "MASTER_DATA v1");
                put(
                        sheet,
                        rowIndex++,
                        0,
                        "来源格式: " + sourceFormat + "; 输入文件: " + input.getFileName());
                put(sheet, rowIndex++, 0, "目标学期: " + termCode + " / " + termName);
                for (String warning : model.warnings) put(sheet, rowIndex++, 0, shorten(warning));
            }
            case "教师" -> {
                for (Teacher teacher : model.teachers.values()) {
                    put(sheet, rowIndex, 0, teacher.code());
                    put(sheet, rowIndex, 1, teacher.name());
                    put(sheet, rowIndex++, 2, true);
                }
            }
            case "班级" -> {
                for (StudentGroup group : model.studentGroups.values()) {
                    put(sheet, rowIndex, 0, group.code());
                    put(sheet, rowIndex, 1, group.name());
                    put(sheet, rowIndex, 2, "HOMEROOM");
                    put(sheet, rowIndex, 3, group.studentCount());
                    put(sheet, rowIndex++, 4, true);
                }
            }
            case "课程" -> {
                for (Subject subject : model.subjects.values()) {
                    put(sheet, rowIndex, 0, subject.code());
                    put(sheet, rowIndex, 1, subject.name());
                    put(sheet, rowIndex++, 2, true);
                }
            }
            case "教室" -> {
                for (Room room : model.rooms.values()) {
                    put(sheet, rowIndex, 0, room.code());
                    put(sheet, rowIndex, 1, room.name());
                    put(sheet, rowIndex, 2, room.capacity());
                    put(sheet, rowIndex, 3, "普通教室");
                    put(sheet, rowIndex++, 4, true);
                }
            }
            case "教学需求" -> {
                for (Requirement requirement : model.requirements) {
                    put(sheet, rowIndex, 0, requirement.code());
                    put(sheet, rowIndex, 1, termCode);
                    put(sheet, rowIndex, 2, requirement.groupCode());
                    put(sheet, rowIndex, 3, requirement.subjectCode());
                    put(sheet, rowIndex, 4, requirement.teacherCode());
                    put(sheet, rowIndex, 5, requirement.weeklyPeriods());
                    put(sheet, rowIndex, 6, 1);
                    put(sheet, rowIndex, 7, requirement.studentCount());
                    put(sheet, rowIndex, 8, "");
                    put(sheet, rowIndex++, 9, true);
                }
            }
            default -> {
                // Optional sheets are emitted with headers only so the workbook has the stable
                // template order.
            }
        }
    }

    private static void put(Sheet sheet, int rowIndex, int columnIndex, Object value) {
        if (value == null) return;
        Row row = sheet.getRow(rowIndex);
        if (row == null) row = sheet.createRow(rowIndex);
        if (value instanceof Boolean booleanValue)
            row.createCell(columnIndex).setCellValue(booleanValue);
        else if (value instanceof Number numberValue)
            row.createCell(columnIndex).setCellValue(numberValue.doubleValue());
        else row.createCell(columnIndex).setCellValue(String.valueOf(value));
    }

    private static ItcData parseItc2007(Path input) throws IOException {
        List<String> lines = Files.readAllLines(input, StandardCharsets.UTF_8);
        String name = input.getFileName().toString();
        Map<String, ItcCourse> courses = new LinkedHashMap<>();
        Map<String, ItcRoom> rooms = new LinkedHashMap<>();
        Map<String, List<String>> curricula = new LinkedHashMap<>();
        String section = "";
        int unavailabilityCount = 0;
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if ("END.".equals(line)) {
                section = "";
                continue;
            }
            if (line.startsWith("Name:")) {
                name = line.substring("Name:".length()).trim();
                continue;
            }
            if (line.endsWith(":")) {
                section = line;
                continue;
            }
            String[] values = line.split("\\s+");
            switch (section) {
                case "COURSES:" -> {
                    if (values.length < 5)
                        throw new IllegalArgumentException("ITC2007 课程行字段不足: " + line);
                    courses.put(
                            values[0],
                            new ItcCourse(
                                    values[0],
                                    values[1],
                                    integer(values[2], line),
                                    integer(values[3], line),
                                    integer(values[4], line)));
                }
                case "ROOMS:" -> {
                    if (values.length < 2)
                        throw new IllegalArgumentException("ITC2007 教室行字段不足: " + line);
                    rooms.put(values[0], new ItcRoom(values[0], integer(values[1], line)));
                }
                case "CURRICULA:" -> {
                    if (values.length < 2)
                        throw new IllegalArgumentException("ITC2007 课程组行字段不足: " + line);
                    int courseCount = integer(values[1], line);
                    if (values.length < courseCount + 2)
                        throw new IllegalArgumentException("ITC2007 课程组课程数量不一致: " + line);
                    List<String> members = new ArrayList<>();
                    for (int index = 2; index < courseCount + 2; index++)
                        members.add(values[index]);
                    curricula.put(values[0], members);
                }
                case "UNAVAILABILITY_CONSTRAINTS:" -> {
                    if (values.length < 3)
                        throw new IllegalArgumentException("ITC2007 不可用约束字段不足: " + line);
                    unavailabilityCount++;
                }
                default -> {
                    // Header metadata and END. do not need conversion.
                }
            }
        }
        if (courses.isEmpty()) throw new IllegalArgumentException("ITC2007 文件没有课程数据: " + input);
        if (rooms.isEmpty()) throw new IllegalArgumentException("ITC2007 文件没有教室数据: " + input);
        return new ItcData(name, courses, rooms, curricula, unavailabilityCount);
    }

    private static UnitimeData parseUnitime(Path input) throws IOException {
        byte[] xml = readUnitimeXml(input);
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setXmlProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        setXmlProperty(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        try (InputStream stream = new ByteArrayInputStream(xml)) {
            XMLStreamReader reader =
                    factory.createXMLStreamReader(stream, StandardCharsets.UTF_8.name());
            String term = input.getFileName().toString();
            List<UnitimeRoom> rooms = new ArrayList<>();
            List<UnitimeClass> classes = new ArrayList<>();
            String section = "";
            UnitimeClassBuilder currentClass = null;
            int studentCount = 0;
            int skippedClassCount = 0;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String element = reader.getLocalName();
                    switch (element) {
                        case "timetable" -> term = attribute(reader, "term", term);
                        case "rooms", "classes", "students" -> section = element;
                        case "room" -> {
                            if ("rooms".equals(section)) {
                                String id = requireAttribute(reader, "id", "room");
                                rooms.add(
                                        new UnitimeRoom(
                                                id,
                                                integer(attribute(reader, "capacity", "1"), id)));
                            } else if (currentClass != null && "classes".equals(section)) {
                                currentClass.roomIds.add(requireAttribute(reader, "id", "room"));
                            }
                        }
                        case "class" -> {
                            if ("classes".equals(section)) {
                                String id = attribute(reader, "id", "");
                                String offeringId = attribute(reader, "offering", "");
                                if (id.isBlank() || offeringId.isBlank()) {
                                    skippedClassCount++;
                                } else {
                                    currentClass =
                                            new UnitimeClassBuilder(
                                                    id,
                                                    offeringId,
                                                    attribute(reader, "classLimit", "0"),
                                                    attribute(reader, "nrRooms", "-1"));
                                }
                            }
                        }
                        case "instructor" -> {
                            if (currentClass != null && currentClass.instructorId == null)
                                currentClass.instructorId = attribute(reader, "id", "");
                        }
                        case "student" -> {
                            if ("students".equals(section)) studentCount++;
                        }
                        default -> {
                            // Candidate times and nested candidate rooms are intentionally not
                            // fixed during conversion.
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String element = reader.getLocalName();
                    if ("class".equals(element) && currentClass != null) {
                        classes.add(currentClass.build());
                        currentClass = null;
                    } else if (element.equals(section)) {
                        section = "";
                    }
                }
            }
            reader.close();
            if (classes.isEmpty())
                throw new IllegalArgumentException("UniTime XML 没有班次数据: " + input);
            if (rooms.isEmpty()) throw new IllegalArgumentException("UniTime XML 没有教室数据: " + input);
            return new UnitimeData(term, rooms, classes, studentCount, skippedClassCount);
        } catch (XMLStreamException exception) {
            throw new IllegalArgumentException(
                    "无法解析 UniTime XML: " + exception.getMessage(), exception);
        }
    }

    private static final class UnitimeClassBuilder {
        private final String id;
        private final String offeringId;
        private final int classLimit;
        private final int requestedRoomCount;
        private final List<String> roomIds = new ArrayList<>();
        private String instructorId;

        private UnitimeClassBuilder(
                String id, String offeringId, String classLimit, String requestedRoomCount) {
            this.id = id;
            this.offeringId = offeringId;
            this.classLimit = integer(classLimit, id);
            this.requestedRoomCount = integer(requestedRoomCount, id);
        }

        private UnitimeClass build() {
            return new UnitimeClass(
                    id,
                    offeringId,
                    instructorId,
                    classLimit,
                    List.copyOf(roomIds),
                    requestedRoomCount < 0 ? roomIds.size() : requestedRoomCount);
        }
    }

    private static byte[] readUnitimeXml(Path input) throws IOException {
        if (!input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
            return Files.readAllBytes(input);
        try (ZipFile zip = new ZipFile(input.toFile())) {
            ZipEntry entry =
                    Collections.list(zip.entries()).stream()
                            .filter(
                                    candidate ->
                                            !candidate.isDirectory()
                                                    && candidate
                                                            .getName()
                                                            .toLowerCase(Locale.ROOT)
                                                            .endsWith(".xml"))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "UniTime ZIP 中没有 XML 文件: " + input));
            try (InputStream stream = zip.getInputStream(entry);
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                stream.transferTo(output);
                return output.toByteArray();
            }
        }
    }

    private static void setXmlProperty(XMLInputFactory factory, String property, Object value) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException ignored) {
            // The JDK StAX implementation may not expose every optional hardening property.
        }
    }

    private static String attribute(XMLStreamReader reader, String name, String fallback) {
        String value = reader.getAttributeValue(null, name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String requireAttribute(XMLStreamReader reader, String name, String element) {
        String value = attribute(reader, name, "");
        if (value.isBlank())
            throw new IllegalArgumentException("UniTime " + element + " 缺少属性 " + name);
        return value;
    }

    private static int integer(String value, String context) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("整数格式无效: " + context + " / " + value, exception);
        }
    }

    private static String requireText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String shorten(String value) {
        return value.length() <= 240 ? value : value.substring(0, 237) + "...";
    }

    private static boolean contains(String[] args, String value) {
        for (String arg : args) if (value.equals(arg)) return true;
        return false;
    }

    private static Options parseOptions(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index++) {
            String key = args[index];
            if (!key.startsWith("--")) throw new IllegalArgumentException("未知参数: " + key);
            if (index + 1 >= args.length || args[index + 1].startsWith("--"))
                throw new IllegalArgumentException("参数缺少值: " + key);
            values.put(key, args[++index]);
        }
        String source = values.get("--source");
        String input = values.get("--input");
        String output = values.get("--output");
        if (source == null || input == null || output == null)
            throw new IllegalArgumentException("必须提供 --source、--input 和 --output");
        SourceFormat sourceFormat =
                switch (source.toLowerCase(Locale.ROOT)) {
                    case "unitime" -> SourceFormat.UNITIME;
                    case "itc2007", "itc" -> SourceFormat.ITC2007;
                    default -> throw new IllegalArgumentException("不支持的来源格式: " + source);
                };
        return new Options(
                sourceFormat,
                Path.of(input),
                Path.of(output),
                values.getOrDefault("--term-code", DEFAULT_TERM_CODE),
                values.getOrDefault("--term-name", DEFAULT_TERM_NAME));
    }

    private static void printUsage() {
        System.out.println("用法:");
        System.out.println(
                "  bash scripts/convert-external-timetable.sh --source unitime --input input.zip --output output.xlsx [--term-code 2026-FALL]");
        System.out.println(
                "  bash scripts/convert-external-timetable.sh --source itc2007 --input comp01.ctt --output output.xlsx [--term-code 2026-FALL]");
    }
}
