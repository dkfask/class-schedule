package com.classschedule.importexport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
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
    private static final long MAX_BYTES = 10 * 1024 * 1024;
    private static final DataFormatter FORMATTER = new DataFormatter();

    private final JdbcTemplate jdbc;

    public WorkbookImportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ImportPreview preview(MultipartFile file) {
        return preview(file, null);
    }

    public ImportPreview preview(MultipartFile file, String actor) {
        if (file == null || file.isEmpty()) {
            return invalidPreview("EMPTY_FILE", "上传文件为空");
        }
        if (file.getSize() > MAX_BYTES) {
            return invalidPreview("FILE_TOO_LARGE", "文件不能超过 10 MB");
        }
        try {
            byte[] bytes = file.getBytes();
            String sha256 = sha256(bytes);
            ParseResult parsed = parse(bytes);
            long batchId = saveBatch(file.getOriginalFilename(), bytes, sha256, parsed, actor);
            return new ImportPreview(batchId, parsed.issues().isEmpty() ? "VALIDATED" : "INVALID",
                    sha256, parsed.sheets(), parsed.issues());
        } catch (IOException | RuntimeException exception) {
            return new ImportPreview(0, "REJECTED", "", List.of(), List.of(
                    new ImportIssue("", 0, "", "INVALID_WORKBOOK", "无法读取 Excel: " + exception.getMessage())));
        }
    }

    public ImportResult confirm(long batchId) {
        return confirm(batchId, null);
    }

    @Transactional
    public ImportResult confirm(long batchId, String actor) {
        Batch batch = findBatch(batchId);
        if (batch.ownerUsername() != null && actor != null && !batch.ownerUsername().equals(actor)) {
            throw new IllegalArgumentException("导入批次仅可由原提交者确认");
        }
        if (!"VALIDATED".equals(batch.status())) {
            throw new IllegalArgumentException("只有预检通过的批次可以确认导入");
        }
        if (!sha256(batch.fileBytes()).equals(batch.sha256())) {
            throw new IllegalArgumentException("导入批次文件摘要不一致，请重新预检");
        }

        try {
            ParseResult parsed = parse(batch.fileBytes());
            if (!parsed.issues().isEmpty()) {
                throw new IllegalArgumentException("确认时二次校验失败，共 " + parsed.issues().size() + " 个问题");
            }
            int importedRows;
            try (InputStream input = new ByteArrayInputStream(batch.fileBytes());
                    Workbook workbook = new XSSFWorkbook(input)) {
                importedRows = importWorkbook(workbook);
            }
            int updated = jdbc.update("UPDATE import_batch SET status = 'IMPORTED', imported_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'VALIDATED'", batchId);
            if (updated != 1) {
                throw new IllegalArgumentException("导入批次已被其他请求处理: " + batchId);
            }
            return new ImportResult(batchId, "IMPORTED", importedRows, 0, "导入成功");
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalStateException("导入失败，事务将回滚: " + exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("导入失败，事务将回滚: " + exception.getMessage(), exception);
        }
    }

    private ParseResult parse(byte[] bytes) throws IOException {
        List<String> sheets = new ArrayList<>();
        List<ImportIssue> issues = new ArrayList<>();
        Map<String, Set<String>> codesBySheet = new HashMap<>();
        int rowCount = 0;
        try (InputStream input = new ByteArrayInputStream(bytes); Workbook workbook = new XSSFWorkbook(input)) {
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                sheets.add(workbook.getSheetName(index));
            }
            for (var entry : ImportHeaders.REQUIRED.entrySet()) {
                Sheet sheet = workbook.getSheet(entry.getKey());
                if (sheet == null) {
                    issues.add(new ImportIssue(entry.getKey(), 0, "", "MISSING_SHEET", "缺少必需 Sheet"));
                    continue;
                }
                Set<String> codes = validateSheet(sheet, entry.getValue(), issues);
                codesBySheet.put(entry.getKey(), codes);
                rowCount += Math.max(0, sheet.getLastRowNum());
            }
            validateRequirementReferences(workbook.getSheet("教学需求"), codesBySheet, issues);
        }
        return new ParseResult(sheets, issues, rowCount);
    }

    private Set<String> validateSheet(Sheet sheet, String[] headers, List<ImportIssue> issues) {
        Set<String> codes = new HashSet<>();
        Row header = sheet.getRow(0);
        if (header == null) {
            issues.add(new ImportIssue(sheet.getSheetName(), 1, "", "EMPTY_SHEET", "Sheet 没有表头"));
            return codes;
        }
        for (int column = 0; column < headers.length; column++) {
            if (!headers[column].equals(text(header, column))) {
                issues.add(new ImportIssue(sheet.getSheetName(), 1, columnName(column), "INVALID_HEADER",
                        "表头必须为 " + headers[column]));
            }
        }
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
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
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            checkReference(sheet, rowIndex, "B", text(row, 1), "academic_term", "code", "TERM_NOT_FOUND", "学期不存在", issues, Set.of());
            checkReference(sheet, rowIndex, "C", text(row, 2), "student_group", "code", "GROUP_NOT_FOUND", "班级不存在", issues, groups);
            checkReference(sheet, rowIndex, "D", text(row, 3), "subject", "code", "SUBJECT_NOT_FOUND", "课程不存在", issues, subjects);
            checkReference(sheet, rowIndex, "E", text(row, 4), "teacher", "code", "TEACHER_NOT_FOUND", "教师不存在", issues, teachers);
        }
    }

    private void checkReference(Sheet sheet, int rowIndex, String column, String code, String table,
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

    private boolean exists(String table, String column, String value) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
        return count != null && count > 0;
    }

    private int importWorkbook(Workbook workbook) {
        int importedRows = 0;
        importedRows += upsertSimpleSheet(workbook.getSheet("教师"), "teacher");
        importedRows += upsertSimpleSheet(workbook.getSheet("班级"), "student_group");
        importedRows += upsertSimpleSheet(workbook.getSheet("课程"), "subject");
        importedRows += upsertRoomSheet(workbook.getSheet("教室"));
        importedRows += upsertRequirementSheet(workbook.getSheet("教学需求"));
        return importedRows;
    }

    private int upsertSimpleSheet(Sheet sheet, String table) {
        int count = 0;
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            String code = text(row, 0);
            String name = text(row, 1);
            if (code.isBlank()) continue;
            jdbc.update("INSERT INTO " + table + " (code, name) VALUES (?, ?) ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name", code, name);
            count++;
        }
        return count;
    }

    private int upsertRoomSheet(Sheet sheet) {
        int count = 0;
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            jdbc.update("INSERT INTO room (code, name, capacity) VALUES (?, ?, ?) ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, capacity = EXCLUDED.capacity", text(row, 0), text(row, 1), Integer.parseInt(text(row, 2)));
            count++;
        }
        return count;
    }

    private int upsertRequirementSheet(Sheet sheet) {
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

    private int optionalPositiveInt(Row row, int index) {
        String value = text(row, index);
        if (value.isBlank()) return 0;
        if (!isInteger(value)) throw new IllegalArgumentException("学生人数必须是非负整数");
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
        return jdbc.queryForObject("INSERT INTO import_batch (sha256, file_name, file_bytes, status, row_count, issue_count, created_by_user_id) VALUES (?, ?, ?, ?, ?, ?, (SELECT id FROM app_user WHERE username = ?)) RETURNING id", Long.class, sha256, fileName == null ? "upload.xlsx" : fileName, bytes, parsed.issues().isEmpty() ? "VALIDATED" : "INVALID", parsed.rowCount(), parsed.issues().size(), actor);
    }

    private Batch findBatch(long batchId) {
        try {
            Batch batch = jdbc.queryForObject(
                    "SELECT id, sha256, file_bytes, status FROM import_batch WHERE id = ? FOR UPDATE",
                    (rs, rowNum) -> new Batch(rs.getLong("id"), rs.getString("sha256"), rs.getBytes("file_bytes"), rs.getString("status"), null),
                    batchId);
            String owner = jdbc.query("SELECT u.username FROM import_batch b JOIN app_user u ON u.id = b.created_by_user_id WHERE b.id = ?",
                    (rs, rowNum) -> rs.getString("username"), batchId).stream().findFirst().orElse(null);
            return new Batch(batch.id(), batch.sha256(), batch.fileBytes(), batch.status(), owner);
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalArgumentException("导入批次不存在: " + batchId);
        }
    }

    private ImportPreview invalidPreview(String code, String message) {
        return new ImportPreview(0, "REJECTED", "", List.of(), List.of(new ImportIssue("", 0, "", code, message)));
    }

    private String text(Row row, int index) {
        Cell cell = row.getCell(index);
        return cell == null ? "" : FORMATTER.formatCellValue(cell).trim();
    }

    private boolean isInteger(String value) {
        try { return Integer.parseInt(value) > 0; } catch (NumberFormatException exception) { return false; }
    }

    private String columnName(int index) { return String.valueOf((char) ('A' + index)); }

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

    private record ParseResult(List<String> sheets, List<ImportIssue> issues, int rowCount) {}
    private record Batch(long id, String sha256, byte[] fileBytes, String status, String ownerUsername) {}
}
