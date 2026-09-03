package com.classschedule.importexport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class WorkbookImportService {
    public static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final long MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_ROWS_PER_SHEET = 10_000;
    private static final int MAX_CELL_TEXT_LENGTH = 256;
    private static final int MAX_ISSUES = 1_000;
    private static final ThreadLocal<DataFormatter> FORMATTER = ThreadLocal.withInitial(DataFormatter::new);

    private final JdbcTemplate jdbc;

    public WorkbookImportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public byte[] masterDataTemplate() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (MasterDataSchemaRegistry.Sheet definition : MasterDataSchemaRegistry.SHEETS) {
                Sheet sheet = workbook.createSheet(definition.name());
                Row header = sheet.createRow(0);
                for (int column = 0; column < definition.headers().size(); column++) {
                    header.createCell(column).setCellValue(definition.headers().get(column));
                }
                sheet.createFreezePane(0, 1);
                if ("说明".equals(definition.name())) {
                    sheet.createRow(1).createCell(0).setCellValue(
                            "MASTER_DATA v1；必填 Sheet：教师、班级、课程、教学需求；教室、资源可用性、特征目录、教室特征、教学需求特征和活动组可省略或留空；学期和节次只校验引用，不在本模板写入；缺少的行不会删除已有数据。");
                }
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("无法生成基础数据模板", exception);
        }
    }

    public ImportPreview preview(MultipartFile file) {
        return preview(file, null);
    }

    public ImportPreview preview(MultipartFile file, String actor) {
        return preview(file, actor, null);
    }

    public ImportPreview preview(MultipartFile file, String actor, String expectedTermCode) {
        if (file == null || file.isEmpty()) {
            return invalidPreview("EMPTY_FILE", "上传文件为空");
        }
        if (file.getSize() > MAX_BYTES) {
            return invalidPreview("FILE_TOO_LARGE", "文件不能超过 10 MB");
        }
        try {
            byte[] bytes = file.getBytes();
            String sha256 = sha256(bytes);
            ParseResult parsed = parse(bytes, expectedTermCode);
            long batchId = saveBatch(file.getOriginalFilename(), bytes, sha256, parsed, actor);
            return new ImportPreview(batchId, parsed.issues().isEmpty() ? "VALIDATED" : "INVALID", sha256,
                    parsed.sheets(), parsed.issues(), parsed.templateType(), parsed.templateVersion(),
                    parsed.schemaHash(), parsed.sheetStats());
        } catch (IOException | RuntimeException exception) {
            return new ImportPreview(0, "REJECTED", "", List.of(), List.of(
                    new ImportIssue("", 0, "", "INVALID_WORKBOOK", "无法读取 Excel: " + exception.getMessage())),
                    "", "", "", List.of());
        }
    }

    public ImportResult confirm(long batchId) {
        return confirm(batchId, null);
    }

    @Transactional
    public ImportResult confirm(long batchId, String actor) {
        Batch batch = findBatch(batchId);
        if (batch.ownerUsername() == null || actor == null || !batch.ownerUsername().equals(actor)) {
            throw new IllegalArgumentException("历史或无所有者导入批次不可确认，且批次仅可由原提交者确认");
        }
        if (batch.templateType() == null || batch.templateVersion() == null || batch.schemaHash() == null) {
            throw new IllegalArgumentException("历史导入批次缺少模板身份，不可确认");
        }
        if (!"VALIDATED".equals(batch.status())) {
            throw new IllegalArgumentException("只有预检通过的批次可以确认导入");
        }
        if (!sha256(batch.fileBytes()).equals(batch.sha256())) {
            throw new IllegalArgumentException("导入批次文件摘要不一致，请重新预检");
        }

        try {
            ParseResult parsed = parseForType(batch.fileBytes(), batch.templateType());
            if (!parsed.issues().isEmpty()) {
                throw new IllegalArgumentException("确认时二次校验失败，共 " + parsed.issues().size() + " 个问题");
            }
            if (!batch.templateVersion().equals(parsed.templateVersion())
                    || !batch.schemaHash().equals(parsed.schemaHash())) {
                throw new IllegalArgumentException("导入批次模板版本或 Schema 摘要不一致，请重新预检");
            }
            ImportApplyResult applied;
            try (InputStream input = new ByteArrayInputStream(batch.fileBytes());
                    Workbook workbook = new XSSFWorkbook(input)) {
                applied = importWorkbook(workbook, batch.templateType());
            }
            int updated = jdbc.update(
                    "UPDATE import_batch SET status = 'IMPORTED', imported_at = CURRENT_TIMESTAMP, metadata = metadata || ?::jsonb WHERE id = ? AND status = 'VALIDATED'",
                    metadataJson(parsed, applied.sheetStats()), batchId);
            if (updated != 1) {
                throw new IllegalArgumentException("导入批次已被其他请求处理: " + batchId);
            }
            return new ImportResult(batchId, "IMPORTED", applied.importedRows(), 0, "导入成功", applied.sheetStats());
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalStateException("导入失败，事务将回滚: " + exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("导入失败，事务将回滚: " + exception.getMessage(), exception);
        }
    }

    private ParseResult parse(byte[] bytes) throws IOException {
        return parse(bytes, null);
    }

    private ParseResult parse(byte[] bytes, String expectedTermCode) throws IOException {
        try (InputStream input = new ByteArrayInputStream(bytes); Workbook workbook = new XSSFWorkbook(input)) {
            if (workbook.getNumberOfSheets() > 0 && "说明".equals(workbook.getSheetName(0))) {
                ParseResult result = parseMasterData(workbook);
                return expectedTermCode == null || expectedTermCode.isBlank() ? result : addExpectedTermIssue(workbook, result, expectedTermCode.trim());
            }
        }
        return parseLegacy(bytes);
    }

    private ParseResult addExpectedTermIssue(Workbook workbook, ParseResult parsed, String expectedTermCode) {
        Sheet requirements = workbook.getSheet("教学需求");
        Set<String> terms = new LinkedHashSet<>();
        if (requirements != null) {
            for (int rowIndex = 1; rowIndex <= dataRowLimit(requirements); rowIndex++) {
                Row row = requirements.getRow(rowIndex);
                if (!blankRow(row, 10)) terms.add(text(row, 1));
            }
        }
        Set<String> mismatches = terms.stream().filter(code -> !expectedTermCode.equals(code)).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (mismatches.isEmpty()) return parsed;
        List<ImportIssue> issues = new ArrayList<>(parsed.issues());
        issues.add(new ImportIssue("教学需求", 0, "B", "TERM_MISMATCH", "教学需求学期必须与当前选择学期一致: " + expectedTermCode + "，文件包含: " + String.join("、", mismatches)));
        return new ParseResult(parsed.sheets(), issues, parsed.rowCount(), parsed.templateType(), parsed.templateVersion(), parsed.schemaHash(), parsed.sheetStats());
    }


    private ParseResult parseForType(byte[] bytes, String templateType) throws IOException {
        if (MasterDataSchemaRegistry.TEMPLATE_TYPE.equals(templateType)) {
            try (InputStream input = new ByteArrayInputStream(bytes); Workbook workbook = new XSSFWorkbook(input)) {
                return parseMasterData(workbook);
            }
        }
        if ("LEGACY".equals(templateType)) return parseLegacy(bytes);
        return new ParseResult(List.of(), List.of(new ImportIssue("", 0, "", "UNKNOWN_TEMPLATE", "不支持的导入模板类型")),
                0, templateType, "", "", List.of());
    }

    private ParseResult parseMasterData(Workbook workbook) {
        List<String> sheets = sheetNames(workbook);
        List<ImportIssue> issues = new BoundedIssueList();
        Map<String, MutableStat> stats = new LinkedHashMap<>();
        Map<String, Map<String, Boolean>> activeCodes = new HashMap<>();
        Map<String, MasterDataSchemaRegistry.Sheet> definitions = new LinkedHashMap<>();
        Map<String, Integer> positions = new HashMap<>();
        for (int index = 0; index < MasterDataSchemaRegistry.SHEETS.size(); index++) {
            MasterDataSchemaRegistry.Sheet definition = MasterDataSchemaRegistry.SHEETS.get(index);
            definitions.put(definition.name(), definition);
            positions.put(definition.name(), index);
        }
        checkSheetRowLimits(workbook, issues);
        checkFormulas(workbook, issues);
        checkCellTextLengths(workbook, issues);

        int previousPosition = -1;
        Set<String> seenSheets = new HashSet<>();
        for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
            Sheet sheet = workbook.getSheetAt(index);
            MasterDataSchemaRegistry.Sheet definition = definitions.get(sheet.getSheetName());
            if (definition == null) {
                issues.add(new ImportIssue(sheet.getSheetName(), 0, "", "UNKNOWN_SHEET",
                        "不支持的 MASTER_DATA Sheet: " + sheet.getSheetName()));
                continue;
            }
            if (!seenSheets.add(sheet.getSheetName())) {
                issues.add(new ImportIssue(sheet.getSheetName(), 0, "", "DUPLICATE_SHEET",
                        "Sheet 不能重复: " + sheet.getSheetName()));
                continue;
            }
            int position = positions.get(sheet.getSheetName());
            if (position < previousPosition) {
                issues.add(new ImportIssue(sheet.getSheetName(), 0, "", "INVALID_SHEET_ORDER",
                        "Sheet 顺序必须遵循 MASTER_DATA v1 模板顺序"));
                continue;
            }
            previousPosition = position;
            if (!validateHeader(sheet, definition.headers(), issues)) continue;
            if (!"说明".equals(definition.name())) {
                validateMasterRows(sheet, activeCodes, issues, stats);
            }
        }
        for (MasterDataSchemaRegistry.Sheet definition : MasterDataSchemaRegistry.SHEETS) {
            if (definition.required() && !seenSheets.contains(definition.name())) {
                issues.add(new ImportIssue(definition.name(), 0, "", "MISSING_SHEET", "缺少必需 Sheet"));
            }
        }
        validateMasterReferences(workbook, activeCodes, issues);
        List<ImportSheetStat> sheetStats = new ArrayList<>();
        for (MasterDataSchemaRegistry.Sheet definition : MasterDataSchemaRegistry.SHEETS) {
            MutableStat stat = stats.get(definition.name());
            sheetStats.add(stat == null ? ImportSheetStat.empty(definition.name()) : stat.value());
        }
        int rows = sheetStats.stream().mapToInt(ImportSheetStat::rows).sum();
        return new ParseResult(sheets, issues, rows, MasterDataSchemaRegistry.TEMPLATE_TYPE,
                MasterDataSchemaRegistry.TEMPLATE_VERSION, MasterDataSchemaRegistry.schemaHash(), sheetStats);
    }

    private void validateMasterRows(Sheet sheet, Map<String, Map<String, Boolean>> activeCodes,
            List<ImportIssue> issues, Map<String, MutableStat> stats) {
        MutableStat stat = stats.computeIfAbsent(sheet.getSheetName(), ignored -> new MutableStat(sheet.getSheetName()));
        Set<String> keys = new HashSet<>();
        Set<String> relationKeys = new HashSet<>();
            Map<String, String> activityDefinitions = new HashMap<>();
            Map<String, Boolean> activityActives = new HashMap<>();
            List<String> headers = MasterDataSchemaRegistry.headers(sheet.getSheetName());
        for (int rowIndex = 1; rowIndex <= dataRowLimit(sheet); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (blankRow(row, headers.size())) continue;
            stat.rows++;
            if (row.getLastCellNum() > headers.size()) {
                issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, columnName(headers.size()), "EXTRA_COLUMN",
                        "不允许额外列"));
            }
            switch (sheet.getSheetName()) {
                case "教师", "课程", "特征目录" -> {
                    String code = text(row, 0);
                    validateCode(code, sheet, rowIndex, keys, issues);
                    requireText(row, 1, sheet, rowIndex, "MISSING_NAME", "名称不能为空", issues);
                    Boolean active = booleanValue(row, 2, sheet, rowIndex, issues);
                    if (active != null) activeCodes.computeIfAbsent(sheet.getSheetName(), ignored -> new HashMap<>()).put(code, active);
                }
                case "班级" -> {
                    String code = text(row, 0);
                    validateCode(code, sheet, rowIndex, keys, issues);
                    requireText(row, 1, sheet, rowIndex, "MISSING_NAME", "名称不能为空", issues);
                    requireText(row, 2, sheet, rowIndex, "MISSING_GROUP_TYPE", "班级类型不能为空", issues);
                    nonNegativeInt(row, 3, sheet, rowIndex, "INVALID_STUDENT_COUNT", issues);
                    Boolean active = booleanValue(row, 4, sheet, rowIndex, issues);
                    if (active != null) activeCodes.computeIfAbsent(sheet.getSheetName(), ignored -> new HashMap<>()).put(code, active);
                }
                case "教室" -> {
                    String code = text(row, 0);
                    validateCode(code, sheet, rowIndex, keys, issues);
                    requireText(row, 1, sheet, rowIndex, "MISSING_NAME", "名称不能为空", issues);
                    positiveInt(row, 2, sheet, rowIndex, "INVALID_CAPACITY", issues);
                    requireText(row, 3, sheet, rowIndex, "MISSING_ROOM_TYPE", "教室类型不能为空", issues);
                    Boolean active = booleanValue(row, 4, sheet, rowIndex, issues);
                    if (active != null) activeCodes.computeIfAbsent(sheet.getSheetName(), ignored -> new HashMap<>()).put(code, active);
                }
                case "教学需求" -> {
                    String code = text(row, 0);
                    validateCode(code, sheet, rowIndex, keys, issues);
                    requireText(row, 1, sheet, rowIndex, "MISSING_TERM", "学期不能为空", issues);
                    requireText(row, 2, sheet, rowIndex, "MISSING_GROUP", "班级不能为空", issues);
                    requireText(row, 3, sheet, rowIndex, "MISSING_SUBJECT", "课程不能为空", issues);
                    requireText(row, 4, sheet, rowIndex, "MISSING_TEACHER", "教师不能为空", issues);
                    positiveInt(row, 5, sheet, rowIndex, "INVALID_WEEKLY_PERIODS", issues);
                    positiveInt(row, 6, sheet, rowIndex, "INVALID_DURATION_PERIODS", issues);
                    nonNegativeInt(row, 7, sheet, rowIndex, "INVALID_STUDENT_COUNT", issues);
                    Boolean active = booleanValue(row, 9, sheet, rowIndex, issues);
                    if (active != null) activeCodes.computeIfAbsent(sheet.getSheetName(), ignored -> new HashMap<>()).put(code, active);
                }
                case "资源可用性" -> {
                    requireText(row, 0, sheet, rowIndex, "MISSING_RESOURCE_TYPE", "资源类型不能为空", issues);
                    requireText(row, 1, sheet, rowIndex, "MISSING_RESOURCE", "资源编码不能为空", issues);
                    requireText(row, 2, sheet, rowIndex, "MISSING_TERM", "学期不能为空", issues);
                    requireText(row, 3, sheet, rowIndex, "MISSING_PERIOD", "节次不能为空", issues);
                    Boolean available = booleanValue(row, 4, sheet, rowIndex, issues);
                    relationKey(row, List.of(0, 1, 2, 3), relationKeys, sheet, rowIndex, issues);
                    if (available == null) break;
                }
                case "教室特征" -> {
                    requireText(row, 0, sheet, rowIndex, "MISSING_ROOM", "教室编码不能为空", issues);
                    requireText(row, 1, sheet, rowIndex, "MISSING_FEATURE", "特征编码不能为空", issues);
                    booleanValue(row, 2, sheet, rowIndex, issues);
                    relationKey(row, List.of(0, 1), relationKeys, sheet, rowIndex, issues);
                }
                case "教学需求特征" -> {
                    requireText(row, 0, sheet, rowIndex, "MISSING_REQUIREMENT", "教学需求编码不能为空", issues);
                    requireText(row, 1, sheet, rowIndex, "MISSING_FEATURE", "特征编码不能为空", issues);
                    booleanValue(row, 2, sheet, rowIndex, issues);
                    relationKey(row, List.of(0, 1), relationKeys, sheet, rowIndex, issues);
                }
                case "活动组" -> {
                    String code = text(row, 0);
                    String termCode = text(row, 3);
                    String activityKey = termCode + "|" + code;
                    requireText(row, 0, sheet, rowIndex, "MISSING_CODE", "活动组编码不能为空", issues);
                    requireText(row, 1, sheet, rowIndex, "MISSING_NAME", "名称不能为空", issues);
                    String activityType = text(row, 2);
                    if (!Set.of("JOINED", "SYNCHRONIZED", "CONSECUTIVE").contains(activityType)) {
                        issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, "C", "INVALID_ACTIVITY_TYPE", "活动组类型无效"));
                    }
                    requireText(row, 3, sheet, rowIndex, "MISSING_TERM", "学期不能为空", issues);
                    nonNegativeInt(row, 4, sheet, rowIndex, "INVALID_MEMBER_INDEX", issues);
                    requireText(row, 5, sheet, rowIndex, "MISSING_REQUIREMENT", "教学需求编码不能为空", issues);
                    Boolean active = booleanValue(row, 6, sheet, rowIndex, issues);
                    String definition = text(row, 1) + "\t" + activityType + "\t" + termCode;
                    String prior = activityDefinitions.putIfAbsent(activityKey, definition);
                    if (prior != null && !prior.equals(definition)) {
                        issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, "A", "INCONSISTENT_ACTIVITY", "同一活动组的名称、类型和学期必须一致"));
                    }
                    Boolean priorActive = activityActives.putIfAbsent(activityKey, active);
                    if (priorActive != null && !priorActive.equals(active)) {
                        issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, "G", "INCONSISTENT_ACTIVITY_ACTIVE", "同一活动组的 active 值必须一致"));
                    }
                    relationKey(row, List.of(3, 0, 4), relationKeys, sheet, rowIndex, issues);
                    relationKey(row, List.of(3, 0, 5), keys, sheet, rowIndex, issues);
                }
                default -> throw new IllegalStateException("未处理的 MASTER_DATA Sheet: " + sheet.getSheetName());
            }
        }
    }

    private void validateMasterReferences(Workbook workbook, Map<String, Map<String, Boolean>> activeCodes,
            List<ImportIssue> issues) {
        Sheet requirements = workbook.getSheet("教学需求");
        Map<String, String> requirementTerms = new HashMap<>();
        if (requirements != null) {
            for (int rowIndex = 1; rowIndex <= dataRowLimit(requirements); rowIndex++) {
                Row row = requirements.getRow(rowIndex);
                if (blankRow(row, 10)) continue;
                String code = text(row, 0);
                if (!code.isBlank()) requirementTerms.put(code, text(row, 1));
                checkTermAndPeriod(requirements, rowIndex, text(row, 1), text(row, 8), issues);
                checkActiveReference(requirements, rowIndex, "C", "班级", text(row, 2), "GROUP_NOT_FOUND", activeCodes, issues);
                checkActiveReference(requirements, rowIndex, "D", "课程", text(row, 3), "SUBJECT_NOT_FOUND", activeCodes, issues);
                checkActiveReference(requirements, rowIndex, "E", "教师", text(row, 4), "TEACHER_NOT_FOUND", activeCodes, issues);
            }
        }

        Sheet availability = workbook.getSheet("资源可用性");
        if (availability != null) {
            for (int rowIndex = 1; rowIndex <= dataRowLimit(availability); rowIndex++) {
                Row row = availability.getRow(rowIndex);
                if (blankRow(row, 5)) continue;
                String resourceType = text(row, 0).toUpperCase();
                String resourceSheet = switch (resourceType) {
                    case "TEACHER" -> "教师";
                    case "ROOM" -> "教室";
                    case "STUDENT_GROUP" -> "班级";
                    default -> "";
                };
                if (resourceSheet.isEmpty()) {
                    issues.add(new ImportIssue(availability.getSheetName(), rowIndex + 1, "A", "INVALID_RESOURCE_TYPE", "资源类型必须为 TEACHER、ROOM 或 STUDENT_GROUP"));
                } else {
                    checkActiveReference(availability, rowIndex, "B", resourceSheet, text(row, 1), "RESOURCE_NOT_FOUND", activeCodes, issues);
                }
                checkTermAndPeriod(availability, rowIndex, text(row, 2), text(row, 3), issues);
            }
        }

        Sheet roomFeatures = workbook.getSheet("教室特征");
        if (roomFeatures != null) {
            for (int rowIndex = 1; rowIndex <= dataRowLimit(roomFeatures); rowIndex++) {
                Row row = roomFeatures.getRow(rowIndex);
                if (blankRow(row, 3)) continue;
                checkActiveReference(roomFeatures, rowIndex, "A", "教室", text(row, 0), "ROOM_NOT_FOUND", activeCodes, issues);
                checkActiveReference(roomFeatures, rowIndex, "B", "特征目录", text(row, 1), "FEATURE_NOT_FOUND", activeCodes, issues);
            }
        }

        Sheet requirementFeatures = workbook.getSheet("教学需求特征");
        if (requirementFeatures != null) {
            for (int rowIndex = 1; rowIndex <= dataRowLimit(requirementFeatures); rowIndex++) {
                Row row = requirementFeatures.getRow(rowIndex);
                if (blankRow(row, 3)) continue;
                checkActiveReference(requirementFeatures, rowIndex, "A", "教学需求", text(row, 0), "REQUIREMENT_NOT_FOUND", activeCodes, issues);
                checkActiveReference(requirementFeatures, rowIndex, "B", "特征目录", text(row, 1), "FEATURE_NOT_FOUND", activeCodes, issues);
            }
        }

        Sheet activityGroups = workbook.getSheet("活动组");
        if (activityGroups != null) {
            Map<String, String> importedRequirementGroups = new HashMap<>();
            for (int rowIndex = 1; rowIndex <= dataRowLimit(activityGroups); rowIndex++) {
                Row row = activityGroups.getRow(rowIndex);
                if (blankRow(row, 7)) continue;
                checkTerm(activityGroups, rowIndex, text(row, 3), issues);
                String requirementCode = text(row, 5);
                String termCode = text(row, 3);
                String groupCode = text(row, 0);
                if (booleanValue(text(row, 6))) {
                    String requirementKey = termCode + "|" + requirementCode;
                    String previousGroup = importedRequirementGroups.putIfAbsent(requirementKey, groupCode);
                    if (previousGroup != null && !previousGroup.equals(groupCode)) {
                        issues.add(new ImportIssue(activityGroups.getSheetName(), rowIndex + 1, "F", "REQUIREMENT_MULTIPLE_ACTIVITY_GROUPS", "同一教学需求不能属于多个活动组"));
                    }
                    if (jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM activity_group_member m JOIN activity_group g ON g.id=m.activity_group_id WHERE m.teaching_requirement_id=(SELECT id FROM teaching_requirement WHERE code=?) AND NOT (g.term_id=(SELECT id FROM academic_term WHERE code=?) AND g.code=?)",
                                    Integer.class, requirementCode, termCode, groupCode) > 0) {
                        issues.add(new ImportIssue(activityGroups.getSheetName(), rowIndex + 1, "F", "REQUIREMENT_MULTIPLE_ACTIVITY_GROUPS", "同一教学需求不能属于多个活动组"));
                    }
                }
                Boolean importedActive = activeCodes.getOrDefault("教学需求", Map.of()).get(requirementCode);
                if (importedActive != null) {
                    if (!importedActive || !text(row, 3).equals(requirementTerms.get(requirementCode))) {
                        issues.add(new ImportIssue(activityGroups.getSheetName(), rowIndex + 1, "F", "REQUIREMENT_TERM_MISMATCH", "教学需求不存在、已停用或不属于活动组学期"));
                    }
                } else if (jdbc.queryForObject("SELECT COUNT(*) FROM teaching_requirement r JOIN academic_term t ON t.id=r.term_id WHERE r.code=? AND r.active=TRUE AND t.code=? AND t.status <> 'ARCHIVED'", Integer.class, requirementCode, text(row, 3)) == 0) {
                    issues.add(new ImportIssue(activityGroups.getSheetName(), rowIndex + 1, "F", "REQUIREMENT_NOT_FOUND", "教学需求不存在、已停用或不属于活动组学期: " + requirementCode));
                }
            }
        }
    }

    private void checkTermAndPeriod(Sheet sheet, int rowIndex, String termCode, String periodCode,
            List<ImportIssue> issues) {
        if (!checkTerm(sheet, rowIndex, termCode, issues)) return;
        if (periodCode.isBlank()) return;
        if (jdbc.queryForObject("SELECT COUNT(*) FROM period_template p JOIN academic_term t ON t.id=p.term_id WHERE t.code=? AND p.code=?", Integer.class, termCode, periodCode) == 0) {
            issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, "D", "PERIOD_NOT_FOUND", "节次不属于目标学期: " + periodCode));
        }
    }

    private boolean checkTerm(Sheet sheet, int rowIndex, String termCode, List<ImportIssue> issues) {
        if (termCode.isBlank()) return false;
        String status = jdbc.query("SELECT status FROM academic_term WHERE code=?", (rs, row) -> rs.getString("status"), termCode)
                .stream().findFirst().orElse(null);
        if (status == null) {
            issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, "", "TERM_NOT_FOUND", "学期不存在: " + termCode));
            return false;
        }
        if ("ARCHIVED".equals(status)) {
            issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, "", "TERM_ARCHIVED", "归档学期不可导入: " + termCode));
            return false;
        }
        return true;
    }

    private void checkActiveReference(Sheet sheet, int rowIndex, String column, String referenceSheet,
            String code, String missingCode, Map<String, Map<String, Boolean>> activeCodes, List<ImportIssue> issues) {
        if (code.isBlank()) return;
        Boolean imported = activeCodes.getOrDefault(referenceSheet, Map.of()).get(code);
        if (imported != null) {
            if (!imported) issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, column, "INACTIVE_REFERENCE", "引用的资源已停用: " + code));
            return;
        }
        String table = switch (referenceSheet) {
            case "教师" -> "teacher";
            case "班级" -> "student_group";
            case "课程" -> "subject";
            case "教室" -> "room";
            case "特征目录" -> "room_feature_catalog";
            case "教学需求" -> "teaching_requirement";
            default -> throw new IllegalArgumentException("未知引用 Sheet: " + referenceSheet);
        };
        if (jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE code=? AND active=TRUE", Integer.class, code) == 0) {
            issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, column, missingCode, "引用不存在或已停用: " + code));
        }
    }

    private ParseResult parseLegacy(byte[] bytes) throws IOException {
        List<String> sheets = new ArrayList<>();
        List<ImportIssue> issues = new BoundedIssueList();
        Map<String, Set<String>> codesBySheet = new HashMap<>();
        int rowCount = 0;
        try (InputStream input = new ByteArrayInputStream(bytes); Workbook workbook = new XSSFWorkbook(input)) {
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) sheets.add(workbook.getSheetName(index));
            checkCellTextLengths(workbook, issues);
            checkSheetRowLimits(workbook, issues);
            checkFormulas(workbook, issues);
            for (var entry : ImportHeaders.REQUIRED.entrySet()) {
                Sheet sheet = workbook.getSheet(entry.getKey());
                if (sheet == null) {
                    issues.add(new ImportIssue(entry.getKey(), 0, "", "MISSING_SHEET", "缺少必需 Sheet"));
                    continue;
                }
                Set<String> codes = validateLegacySheet(sheet, entry.getValue(), issues);
                codesBySheet.put(entry.getKey(), codes);
                rowCount += Math.min(Math.max(0, sheet.getLastRowNum()), MAX_ROWS_PER_SHEET);
            }
            validateRequirementReferences(workbook.getSheet("教学需求"), codesBySheet, issues);
        }
        List<ImportSheetStat> stats = new ArrayList<>();
        for (String sheet : ImportHeaders.REQUIRED.keySet()) {
            SheetStatRows rows = legacyRows(bytes, sheet);
            stats.add(new ImportSheetStat(sheet, rows.rows(), 0, 0, 0));
        }
        return new ParseResult(sheets, issues, rowCount, "LEGACY", "legacy", "", stats);
    }

    private SheetStatRows legacyRows(byte[] bytes, String name) {
        try (InputStream input = new ByteArrayInputStream(bytes); Workbook workbook = new XSSFWorkbook(input)) {
            Sheet sheet = workbook.getSheet(name);
            return new SheetStatRows(sheet == null ? 0 : Math.min(Math.max(0, sheet.getLastRowNum()), MAX_ROWS_PER_SHEET));
        } catch (IOException exception) {
            return new SheetStatRows(0);
        }
    }

    private Set<String> validateLegacySheet(Sheet sheet, String[] headers, List<ImportIssue> issues) {
        Set<String> codes = new HashSet<>();
        Row header = sheet.getRow(0);
        if (header == null) {
            issues.add(new ImportIssue(sheet.getSheetName(), 1, "", "EMPTY_SHEET", "Sheet 没有表头"));
            return codes;
        }
        for (int column = 0; column < headers.length; column++) {
            if (!headers[column].equals(text(header, column))) {
                issues.add(new ImportIssue(sheet.getSheetName(), 1, columnName(column), "INVALID_HEADER", "表头必须为 " + headers[column]));
            }
        }
        for (int rowIndex = 1; rowIndex <= dataRowLimit(sheet); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || row.getLastCellNum() < 1) continue;
            String code = text(row, 0);
            if (code.isEmpty()) {
                issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, "A", "MISSING_CODE", "第一列必须填写稳定编码"));
            } else if (!codes.add(code)) {
                issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, "A", "DUPLICATE_CODE", "同一 Sheet 内编码重复: " + code));
            }
            if ("教室".equals(sheet.getSheetName()) && !isInteger(text(row, 2))) {
                issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, "C", "INVALID_CAPACITY", "容量必须是正整数"));
            }
            if ("教学需求".equals(sheet.getSheetName())) {
                if (!isInteger(text(row, 5)) || !isInteger(text(row, 6))) {
                    issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, "F/G", "INVALID_PERIODS", "课时和单次节数必须是正整数"));
                }
            }
        }
        return codes;
    }

    private void validateRequirementReferences(Sheet sheet, Map<String, Set<String>> codesBySheet,
            List<ImportIssue> issues) {
        if (sheet == null) return;
        Set<String> groups = codesBySheet.getOrDefault("班级", Set.of());
        Set<String> subjects = codesBySheet.getOrDefault("课程", Set.of());
        Set<String> teachers = codesBySheet.getOrDefault("教师", Set.of());
        for (int rowIndex = 1; rowIndex <= dataRowLimit(sheet); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            checkLegacyReference(sheet, rowIndex, "B", text(row, 1), "academic_term", "code", "TERM_NOT_FOUND", "学期不存在", issues, Set.of());
            checkLegacyReference(sheet, rowIndex, "C", text(row, 2), "student_group", "code", "GROUP_NOT_FOUND", "班级不存在", issues, groups);
            checkLegacyReference(sheet, rowIndex, "D", text(row, 3), "subject", "code", "SUBJECT_NOT_FOUND", "课程不存在", issues, subjects);
            checkLegacyReference(sheet, rowIndex, "E", text(row, 4), "teacher", "code", "TEACHER_NOT_FOUND", "教师不存在", issues, teachers);
        }
    }

    private void checkLegacyReference(Sheet sheet, int rowIndex, String column, String code, String table,
            String columnName, String errorCode, String message, List<ImportIssue> issues, Set<String> importedCodes) {
        if (code.isBlank()) {
            issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, column, errorCode, message + ": 编码为空"));
            return;
        }
        if (!importedCodes.isEmpty() && importedCodes.contains(code)) return;
        if (!exists(table, columnName, code)) {
            issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, column, errorCode, message + ": " + code));
        }
    }

    private ImportApplyResult importWorkbook(Workbook workbook, String templateType) {
        if (MasterDataSchemaRegistry.TEMPLATE_TYPE.equals(templateType)) return importMasterWorkbook(workbook);
        int importedRows = 0;
        importedRows += upsertLegacySimpleSheet(workbook.getSheet("教师"), "teacher");
        importedRows += upsertLegacySimpleSheet(workbook.getSheet("班级"), "student_group");
        importedRows += upsertLegacySimpleSheet(workbook.getSheet("课程"), "subject");
        importedRows += upsertLegacyRoomSheet(workbook.getSheet("教室"));
        importedRows += upsertLegacyRequirementSheet(workbook.getSheet("教学需求"));
        List<ImportSheetStat> stats = new ArrayList<>();
        for (String sheet : ImportHeaders.REQUIRED.keySet()) stats.add(new ImportSheetStat(sheet, legacySheetRows(workbook.getSheet(sheet)), 0, 0, 0));
        return new ImportApplyResult(importedRows, stats);
    }

    private ImportApplyResult importMasterWorkbook(Workbook workbook) {
        Map<String, MutableStat> stats = new LinkedHashMap<>();
        for (MasterDataSchemaRegistry.Sheet definition : MasterDataSchemaRegistry.SHEETS) {
            if (!"说明".equals(definition.name())) stats.put(definition.name(), new MutableStat(definition.name()));
        }
        importMasterTeachers(workbook.getSheet("教师"), stats.get("教师"));
        importMasterGroups(workbook.getSheet("班级"), stats.get("班级"));
        importMasterSubjects(workbook.getSheet("课程"), stats.get("课程"));
        importMasterRooms(workbook.getSheet("教室"), stats.get("教室"));
        importMasterRequirements(workbook.getSheet("教学需求"), stats.get("教学需求"));
        importMasterAvailability(workbook.getSheet("资源可用性"), stats.get("资源可用性"));
        importMasterFeatures(workbook.getSheet("特征目录"), stats.get("特征目录"));
        importMasterRoomFeatures(workbook.getSheet("教室特征"), stats.get("教室特征"));
        importMasterRequirementFeatures(workbook.getSheet("教学需求特征"), stats.get("教学需求特征"));
        importMasterActivityGroups(workbook.getSheet("活动组"), stats.get("活动组"));
        List<ImportSheetStat> result = new ArrayList<>();
        for (MasterDataSchemaRegistry.Sheet definition : MasterDataSchemaRegistry.SHEETS) {
            MutableStat stat = stats.get(definition.name());
            result.add(stat == null ? ImportSheetStat.empty(definition.name()) : stat.value());
        }
        return new ImportApplyResult(result.stream().mapToInt(ImportSheetStat::rows).sum(), result);
    }

    private void importMasterTeachers(Sheet sheet, MutableStat stat) {
        forEachDataRow(sheet, row -> {
            stat.rows++;
            String code = text(row, 0);
            boolean existing = exists("teacher", "code", code);
            if (existing) {
                jdbc.update("UPDATE teacher SET name=?, active=? WHERE code=?", text(row, 1), booleanValue(text(row, 2)), code);
                stat.updated++;
            } else {
                jdbc.update("INSERT INTO teacher(code,name,active) VALUES(?,?,?)", code, text(row, 1), booleanValue(text(row, 2)));
                stat.created++;
            }
        });
    }

    private void importMasterGroups(Sheet sheet, MutableStat stat) {
        forEachDataRow(sheet, row -> {
            stat.rows++;
            String code = text(row, 0);
            boolean existing = exists("student_group", "code", code);
            if (existing) {
                jdbc.update("UPDATE student_group SET name=?, group_type=?, student_count=?, active=? WHERE code=?", text(row, 1), text(row, 2), Integer.parseInt(text(row, 3)), booleanValue(text(row, 4)), code);
                stat.updated++;
            } else {
                jdbc.update("INSERT INTO student_group(code,name,group_type,student_count,active) VALUES(?,?,?,?,?)", code, text(row, 1), text(row, 2), Integer.parseInt(text(row, 3)), booleanValue(text(row, 4)));
                stat.created++;
            }
        });
    }

    private void importMasterSubjects(Sheet sheet, MutableStat stat) {
        forEachDataRow(sheet, row -> {
            stat.rows++;
            String code = text(row, 0);
            boolean existing = exists("subject", "code", code);
            if (existing) {
                jdbc.update("UPDATE subject SET name=?, active=? WHERE code=?", text(row, 1), booleanValue(text(row, 2)), code);
                stat.updated++;
            } else {
                jdbc.update("INSERT INTO subject(code,name,active) VALUES(?,?,?)", code, text(row, 1), booleanValue(text(row, 2)));
                stat.created++;
            }
        });
    }

    private void importMasterRooms(Sheet sheet, MutableStat stat) {
        forEachDataRow(sheet, row -> {
            stat.rows++;
            String code = text(row, 0);
            boolean existing = exists("room", "code", code);
            if (existing) {
                jdbc.update("UPDATE room SET name=?, capacity=?, room_type=?, active=? WHERE code=?", text(row, 1), Integer.parseInt(text(row, 2)), text(row, 3), booleanValue(text(row, 4)), code);
                stat.updated++;
            } else {
                jdbc.update("INSERT INTO room(code,name,capacity,room_type,active) VALUES(?,?,?,?,?)", code, text(row, 1), Integer.parseInt(text(row, 2)), text(row, 3), booleanValue(text(row, 4)));
                stat.created++;
            }
        });
    }

    private void importMasterRequirements(Sheet sheet, MutableStat stat) {
        forEachDataRow(sheet, row -> {
            stat.rows++;
            String code = text(row, 0);
            Object[] args = {text(row, 1), text(row, 2), text(row, 3), text(row, 4), Integer.parseInt(text(row, 5)), Integer.parseInt(text(row, 6)), Integer.parseInt(text(row, 7)), nullable(text(row, 8)), booleanValue(text(row, 9)), code};
            int updated = jdbc.update("UPDATE teaching_requirement SET term_id=(SELECT id FROM academic_term WHERE code=?), student_group_id=(SELECT id FROM student_group WHERE code=?), subject_id=(SELECT id FROM subject WHERE code=?), teacher_id=(SELECT id FROM teacher WHERE code=?), weekly_periods=?, duration_periods=?, student_count=?, pinned_period_code=?, active=? WHERE code=?", args);
            if (updated == 0) {
                jdbc.update("INSERT INTO teaching_requirement(code,term_id,student_group_id,subject_id,teacher_id,weekly_periods,duration_periods,student_count,pinned_period_code,active) VALUES(?,(SELECT id FROM academic_term WHERE code=?),(SELECT id FROM student_group WHERE code=?),(SELECT id FROM subject WHERE code=?),(SELECT id FROM teacher WHERE code=?),?,?,?,?,?)", code, text(row, 1), text(row, 2), text(row, 3), text(row, 4), Integer.parseInt(text(row, 5)), Integer.parseInt(text(row, 6)), Integer.parseInt(text(row, 7)), nullable(text(row, 8)), booleanValue(text(row, 9)));
                stat.created++;
            } else stat.updated++;
        });
    }

    private void importMasterAvailability(Sheet sheet, MutableStat stat) {
        forEachDataRow(sheet, row -> {
            stat.rows++;
            String resourceType = text(row, 0).toUpperCase();
            String table;
            String resourceColumn;
            String resourceTable;
            switch (resourceType) {
                case "TEACHER" -> { table = "teacher_availability"; resourceColumn = "teacher_id"; resourceTable = "teacher"; }
                case "ROOM" -> { table = "room_availability"; resourceColumn = "room_id"; resourceTable = "room"; }
                case "STUDENT_GROUP" -> { table = "student_group_availability"; resourceColumn = "student_group_id"; resourceTable = "student_group"; }
                default -> throw new IllegalArgumentException("不支持的资源可用性类型: " + resourceType);
            }
            String resourceCode = text(row, 1), termCode = text(row, 2), periodCode = text(row, 3);
            boolean available = booleanValue(text(row, 4));
            int updated = jdbc.update("UPDATE " + table + " SET available=? WHERE " + resourceColumn + "=(SELECT id FROM " + resourceTable + " WHERE code=?) AND term_id=(SELECT id FROM academic_term WHERE code=?) AND period_code=?", available, resourceCode, termCode, periodCode);
            if (updated == 0) {
                jdbc.update("INSERT INTO " + table + " (" + resourceColumn + ",term_id,period_code,available) VALUES ((SELECT id FROM " + resourceTable + " WHERE code=?),(SELECT id FROM academic_term WHERE code=?),?,?)", resourceCode, termCode, periodCode, available);
                stat.created++;
            } else stat.updated++;
            if (!available) stat.deactivated++;
        });
    }

    private void importMasterFeatures(Sheet sheet, MutableStat stat) {
        forEachDataRow(sheet, row -> {
            stat.rows++;
            String code = text(row, 0);
            boolean existing = exists("room_feature_catalog", "code", code);
            if (existing) {
                jdbc.update("UPDATE room_feature_catalog SET name=?, active=? WHERE code=?", text(row, 1), booleanValue(text(row, 2)), code);
                stat.updated++;
            } else {
                jdbc.update("INSERT INTO room_feature_catalog(code,name,active) VALUES(?,?,?)", code, text(row, 1), booleanValue(text(row, 2)));
                stat.created++;
            }
            if (!booleanValue(text(row, 2))) stat.deactivated++;
        });
    }

    private void importMasterRoomFeatures(Sheet sheet, MutableStat stat) {
        forEachDataRow(sheet, row -> {
            stat.rows++;
            String roomCode = text(row, 0), featureCode = text(row, 1);
            boolean active = booleanValue(text(row, 2));
            if (active) {
                int exists = jdbc.queryForObject("SELECT COUNT(*) FROM room_feature WHERE room_id=(SELECT id FROM room WHERE code=?) AND feature_code=?", Integer.class, roomCode, featureCode);
                if (exists == 0) {
                    jdbc.update("INSERT INTO room_feature(room_id,feature_code) VALUES((SELECT id FROM room WHERE code=?),?)", roomCode, featureCode);
                    stat.created++;
                } else stat.updated++;
            } else {
                jdbc.update("DELETE FROM room_feature WHERE room_id=(SELECT id FROM room WHERE code=?) AND feature_code=?", roomCode, featureCode);
                stat.deactivated++;
            }
        });
    }

    private void importMasterRequirementFeatures(Sheet sheet, MutableStat stat) {
        forEachDataRow(sheet, row -> {
            stat.rows++;
            String requirementCode = text(row, 0), featureCode = text(row, 1);
            boolean active = booleanValue(text(row, 2));
            if (active) {
                int exists = jdbc.queryForObject("SELECT COUNT(*) FROM teaching_requirement_feature WHERE teaching_requirement_id=(SELECT id FROM teaching_requirement WHERE code=?) AND feature_code=?", Integer.class, requirementCode, featureCode);
                if (exists == 0) {
                    jdbc.update("INSERT INTO teaching_requirement_feature(teaching_requirement_id,feature_code) VALUES((SELECT id FROM teaching_requirement WHERE code=?),?)", requirementCode, featureCode);
                    stat.created++;
                } else stat.updated++;
            } else {
                jdbc.update("DELETE FROM teaching_requirement_feature WHERE teaching_requirement_id=(SELECT id FROM teaching_requirement WHERE code=?) AND feature_code=?", requirementCode, featureCode);
                stat.deactivated++;
            }
        });
    }

    private void importMasterActivityGroups(Sheet sheet, MutableStat stat) {
        forEachDataRow(sheet, row -> {
            stat.rows++;
            String code = text(row, 0), termCode = text(row, 3), requirementCode = text(row, 5);
            boolean active = booleanValue(text(row, 6));
            Long termId = jdbc.queryForObject("SELECT id FROM academic_term WHERE code=?", Long.class, termCode);
            boolean existing = jdbc.queryForObject("SELECT COUNT(*) FROM activity_group WHERE term_id=? AND code=?", Integer.class, termId, code) > 0;
            if (existing) {
                jdbc.update("UPDATE activity_group SET name=?, activity_type=?, active=? WHERE term_id=? AND code=?", text(row, 1), text(row, 2), active, termId, code);
                stat.updated++;
            } else {
                jdbc.update("INSERT INTO activity_group(code,name,activity_type,term_id,active) VALUES(?,?,?,?,?)", code, text(row, 1), text(row, 2), termId, active);
                stat.created++;
            }
            if (active) {
                Long groupId = jdbc.queryForObject("SELECT id FROM activity_group WHERE term_id=? AND code=?", Long.class, termId, code);
                Long requirementId = jdbc.queryForObject("SELECT id FROM teaching_requirement WHERE code=?", Long.class, requirementCode);
                int memberUpdated = jdbc.update("UPDATE activity_group_member SET member_index=? WHERE activity_group_id=? AND teaching_requirement_id=?", Integer.parseInt(text(row, 4)), groupId, requirementId);
                if (memberUpdated == 0) {
                    jdbc.update("INSERT INTO activity_group_member(activity_group_id,teaching_requirement_id,member_index) VALUES(?,?,?)", groupId, requirementId, Integer.parseInt(text(row, 4)));
                    stat.created++;
                } else stat.updated++;
            } else {
                stat.deactivated++;
            }
        });
    }

    private int upsertLegacySimpleSheet(Sheet sheet, String table) {
        int count = 0;
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            String code = text(row, 0), name = text(row, 1);
            if (code.isBlank()) continue;
            jdbc.update("INSERT INTO " + table + " (code, name) VALUES (?, ?) ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name", code, name);
            count++;
        }
        return count;
    }

    private int upsertLegacyRoomSheet(Sheet sheet) {
        int count = 0;
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            jdbc.update("INSERT INTO room (code, name, capacity) VALUES (?, ?, ?) ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, capacity = EXCLUDED.capacity", text(row, 0), text(row, 1), Integer.parseInt(text(row, 2)));
            count++;
        }
        return count;
    }

    private int upsertLegacyRequirementSheet(Sheet sheet) {
        int count = 0;
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            jdbc.update("INSERT INTO teaching_requirement (code, term_id, student_group_id, subject_id, teacher_id, weekly_periods, duration_periods, student_count) VALUES (?, (SELECT id FROM academic_term WHERE code = ?), (SELECT id FROM student_group WHERE code = ?), (SELECT id FROM subject WHERE code = ?), (SELECT id FROM teacher WHERE code = ?), ?, ?, ?) ON CONFLICT (code) DO UPDATE SET weekly_periods = EXCLUDED.weekly_periods, duration_periods = EXCLUDED.duration_periods, student_count = EXCLUDED.student_count, term_id = EXCLUDED.term_id, student_group_id = EXCLUDED.student_group_id, subject_id = EXCLUDED.subject_id, teacher_id = EXCLUDED.teacher_id", text(row, 0), text(row, 1), text(row, 2), text(row, 3), text(row, 4), Integer.parseInt(text(row, 5)), Integer.parseInt(text(row, 6)), optionalPositiveInt(row, 7));
            replaceImportedRequirementFeatures(row, text(row, 8));
            count++;
        }
        return count;
    }

    private int legacySheetRows(Sheet sheet) {
        return sheet == null ? 0 : Math.max(0, sheet.getLastRowNum());
    }

    private int optionalPositiveInt(Row row, int index) {
        String value = text(row, index);
        if (value.isBlank()) return 0;
        if (!isNonNegativeInteger(value)) throw new IllegalArgumentException("学生人数必须是非负整数");
        return Integer.parseInt(value);
    }

    private void replaceImportedRequirementFeatures(Row row, String value) {
        Long requirementId = jdbc.queryForObject("SELECT id FROM teaching_requirement WHERE code=?", Long.class, text(row, 0));
        jdbc.update("DELETE FROM teaching_requirement_feature WHERE teaching_requirement_id=?", requirementId);
        if (value == null || value.isBlank()) return;
        for (String feature : value.split(",")) {
            String code = feature.trim();
            if (!code.isBlank()) jdbc.update("INSERT INTO teaching_requirement_feature(teaching_requirement_id,feature_code) VALUES(?,?) ON CONFLICT DO NOTHING", requirementId, code);
        }
    }

    private long saveBatch(String fileName, byte[] bytes, String sha256, ParseResult parsed, String actor) {
        return jdbc.queryForObject(
                "INSERT INTO import_batch (sha256, file_name, file_bytes, status, template_type, template_version, schema_hash, metadata, row_count, issue_count, created_by_user_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, (SELECT id FROM app_user WHERE username = ?)) RETURNING id",
                Long.class, sha256, fileName == null ? "upload.xlsx" : fileName, bytes,
                parsed.issues().isEmpty() ? "VALIDATED" : "INVALID", parsed.templateType(), parsed.templateVersion(),
                parsed.schemaHash(), metadataJson(parsed, parsed.sheetStats()), parsed.rowCount(), parsed.issues().size(), actor);
    }

    private Batch findBatch(long batchId) {
        try {
            return jdbc.queryForObject(
                    "SELECT b.id, b.sha256, b.file_bytes, b.status, b.template_type, b.template_version, b.schema_hash, u.username AS owner_username "
                            + "FROM import_batch b LEFT JOIN app_user u ON u.id=b.created_by_user_id "
                            + "WHERE b.id=? FOR UPDATE OF b",
                    (rs, rowNum) -> new Batch(rs.getLong("id"), rs.getString("sha256"), rs.getBytes("file_bytes"), rs.getString("status"), rs.getString("template_type"), rs.getString("template_version"), rs.getString("schema_hash"), rs.getString("owner_username")), batchId);
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalArgumentException("导入批次不存在: " + batchId);
        }
    }

    private String metadataJson(ParseResult parsed, List<ImportSheetStat> stats) {
        StringBuilder json = new StringBuilder("{\"templateType\":\"").append(escapeJson(parsed.templateType()))
                .append("\",\"templateVersion\":\"").append(escapeJson(parsed.templateVersion()))
                .append("\",\"schemaHash\":\"").append(escapeJson(parsed.schemaHash())).append("\",\"sheetStats\":[");
        for (int index = 0; index < stats.size(); index++) {
            if (index > 0) json.append(',');
            ImportSheetStat stat = stats.get(index);
            json.append("{\"sheet\":\"").append(escapeJson(stat.sheet())).append("\",\"rows\":").append(stat.rows())
                    .append(",\"created\":").append(stat.created()).append(",\"updated\":").append(stat.updated())
                    .append(",\"deactivated\":").append(stat.deactivated()).append('}');
        }
        return json.append("]}").toString();
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private ImportPreview invalidPreview(String code, String message) {
        return new ImportPreview(0, "REJECTED", "", List.of(), List.of(new ImportIssue("", 0, "", code, message)), "", "", "", List.of());
    }

    private int dataRowLimit(Sheet sheet) {
        return sheet == null ? 0 : Math.min(sheet.getLastRowNum(), MAX_ROWS_PER_SHEET);
    }

    private void checkSheetRowLimits(Workbook workbook, List<ImportIssue> issues) {
        for (Sheet sheet : workbook) {
            if (sheet.getLastRowNum() > MAX_ROWS_PER_SHEET) {
                issues.add(new ImportIssue(sheet.getSheetName(), MAX_ROWS_PER_SHEET + 2, "", "TOO_MANY_ROWS",
                        "单个 Sheet 数据行不能超过 " + MAX_ROWS_PER_SHEET + " 行"));
            }
        }
    }

    private void checkCellTextLengths(Workbook workbook, List<ImportIssue> issues) {
        for (Sheet sheet : workbook) {
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell.getCellType() == CellType.STRING
                            && cell.getStringCellValue().length() > MAX_CELL_TEXT_LENGTH) {
                        issues.add(new ImportIssue(sheet.getSheetName(), row.getRowNum() + 1,
                                columnName(cell.getColumnIndex()), "CELL_TEXT_TOO_LONG",
                                "单元格文本不能超过 " + MAX_CELL_TEXT_LENGTH + " 个字符"));
                    }
                }
            }
        }
    }

    private static final class BoundedIssueList extends ArrayList<ImportIssue> {
        private boolean truncated;

        @Override
        public boolean add(ImportIssue issue) {
            if (size() < MAX_ISSUES - 1) return super.add(issue);
            if (!truncated) {
                truncated = true;
                return super.add(new ImportIssue("", 0, "", "ISSUES_TRUNCATED",
                        "问题数量超过 " + MAX_ISSUES + " 条，仅展示前 " + (MAX_ISSUES - 1) + " 条"));
            }
            return false;
        }
    }

    private void checkFormulas(Workbook workbook, List<ImportIssue> issues) {
        for (Sheet sheet : workbook) {
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell.getCellType() == CellType.FORMULA) {
                        issues.add(new ImportIssue(sheet.getSheetName(), row.getRowNum() + 1, columnName(cell.getColumnIndex()), "FORMULA_NOT_ALLOWED", "导入模板不允许公式"));
                    }
                }
            }
        }
    }

    private boolean validateHeader(Sheet sheet, List<String> headers, List<ImportIssue> issues) {
        Row header = sheet.getRow(0);
        boolean valid = true;
        if (header == null) {
            issues.add(new ImportIssue(sheet.getSheetName(), 1, "", "EMPTY_SHEET", "Sheet 没有表头"));
            return false;
        }
        if (header.getLastCellNum() != headers.size()) {
            issues.add(new ImportIssue(sheet.getSheetName(), 1, columnName(headers.size()), "INVALID_HEADER", "表头列数必须为 " + headers.size()));
            valid = false;
        }
        for (int column = 0; column < headers.size(); column++) {
            if (!headers.get(column).equals(text(header, column))) {
                issues.add(new ImportIssue(sheet.getSheetName(), 1, columnName(column), "INVALID_HEADER", "表头必须为 " + headers.get(column)));
                valid = false;
            }
        }
        return valid;
    }

    private void validateCode(String code, Sheet sheet, int rowIndex, Set<String> keys, List<ImportIssue> issues) {
        if (code.isBlank()) {
            issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, "A", "MISSING_CODE", "编码不能为空"));
        } else if (!keys.add(code)) {
            issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, "A", "DUPLICATE_KEY", "同一 Sheet 内键重复: " + code));
        }
    }

    private void relationKey(Row row, List<Integer> columns, Set<String> keys, Sheet sheet, int rowIndex, List<ImportIssue> issues) {
        String key = columns.stream().map(index -> text(row, index)).reduce((left, right) -> left + "\t" + right).orElse("");
        if (key.contains("\t") && key.replace("\t", "").isBlank()) return;
        if (!keys.add(key)) issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, columnName(columns.get(0)), "DUPLICATE_KEY", "关系键重复: " + key.replace('\t', ':')));
    }

    private void requireText(Row row, int index, Sheet sheet, int rowIndex, String code, String message, List<ImportIssue> issues) {
        if (text(row, index).isBlank()) issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, columnName(index), code, message));
    }

    private void positiveInt(Row row, int index, Sheet sheet, int rowIndex, String code, List<ImportIssue> issues) {
        if (!isPositiveInteger(text(row, index))) issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, columnName(index), code, "必须是正整数"));
    }

    private void nonNegativeInt(Row row, int index, Sheet sheet, int rowIndex, String code, List<ImportIssue> issues) {
        if (!isNonNegativeInteger(text(row, index))) issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, columnName(index), code, "必须是非负整数"));
    }

    private Boolean booleanValue(Row row, int index, Sheet sheet, int rowIndex, List<ImportIssue> issues) {
        String value = text(row, index);
        if ("TRUE".equalsIgnoreCase(value)) return Boolean.TRUE;
        if ("FALSE".equalsIgnoreCase(value)) return Boolean.FALSE;
        issues.add(new ImportIssue(sheet.getSheetName(), rowIndex + 1, columnName(index), "INVALID_BOOLEAN", "必须是 TRUE 或 FALSE"));
        return null;
    }

    private boolean booleanValue(String value) {
        return "TRUE".equalsIgnoreCase(value);
    }

    private boolean blankRow(Row row, int width) {
        if (row == null || row.getLastCellNum() < 1) return true;
        for (int index = 0; index < width; index++) if (!text(row, index).isBlank()) return false;
        return true;
    }

    private void forEachDataRow(Sheet sheet, RowConsumer consumer) {
        if (sheet == null) return;
        for (int index = 1; index <= dataRowLimit(sheet); index++) {
            Row row = sheet.getRow(index);
            if (row != null && row.getLastCellNum() >= 1 && !blankRow(row, MasterDataSchemaRegistry.headers(sheet.getSheetName()).size())) consumer.accept(row);
        }
    }

    private String text(Row row, int index) {
        Cell cell = row == null ? null : row.getCell(index);
        return cell == null ? "" : FORMATTER.get().formatCellValue(cell).trim();
    }

    private String text(Cell cell) {
        return cell == null ? "" : FORMATTER.get().formatCellValue(cell).trim();
    }

    private Object nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private boolean isInteger(String value) { return isPositiveInteger(value); }
    private boolean isPositiveInteger(String value) { try { return Integer.parseInt(value) > 0; } catch (NumberFormatException exception) { return false; } }
    private boolean isNonNegativeInteger(String value) { try { return Integer.parseInt(value) >= 0; } catch (NumberFormatException exception) { return false; } }

    private boolean exists(String table, String column, String value) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
        return count != null && count > 0;
    }

    private List<String> sheetNames(Workbook workbook) {
        List<String> names = new ArrayList<>();
        for (int index = 0; index < workbook.getNumberOfSheets(); index++) names.add(workbook.getSheetName(index));
        return names;
    }

    private String columnName(int index) { return index < 26 ? String.valueOf((char) ('A' + index)) : "COL" + (index + 1); }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法计算文件摘要", exception);
        }
    }

    private String metadataJson(ParseResult parsed, List<ImportSheetStat> stats, boolean ignored) {
        return metadataJson(parsed, stats);
    }

    private interface RowConsumer { void accept(Row row); }
    private record SheetStatRows(int rows) {}
    private record ParseResult(List<String> sheets, List<ImportIssue> issues, int rowCount, String templateType,
            String templateVersion, String schemaHash, List<ImportSheetStat> sheetStats) {}
    private record ImportApplyResult(int importedRows, List<ImportSheetStat> sheetStats) {}
    private record Batch(long id, String sha256, byte[] fileBytes, String status, String templateType,
            String templateVersion, String schemaHash, String ownerUsername) {}

    private static final class MutableStat {
        private final String sheet;
        private int rows;
        private int created;
        private int updated;
        private int deactivated;
        private MutableStat(String sheet) { this.sheet = sheet; }
        private ImportSheetStat value() { return new ImportSheetStat(sheet, rows, created, updated, deactivated); }
    }
}
