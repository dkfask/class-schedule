package com.classschedule.importexport;

import java.util.List;

public record ImportResult(
        long batchId,
        String status,
        int importedRows,
        int issueCount,
        String message,
        List<ImportSheetStat> sheetStats) {
    public ImportResult(
            long batchId, String status, int importedRows, int issueCount, String message) {
        this(batchId, status, importedRows, issueCount, message, List.of());
    }
}
