package com.classschedule.importexport;

import java.util.List;

public record ImportPreview(
        long batchId,
        String status,
        String sha256,
        List<String> sheets,
        List<ImportIssue> issues,
        String templateType,
        String templateVersion,
        String schemaHash,
        List<ImportSheetStat> sheetStats) {
    public ImportPreview(long batchId, String status, String sha256, List<String> sheets, List<ImportIssue> issues) {
        this(batchId, status, sha256, sheets, issues, "", "", "", List.of());
    }
}
