package com.classschedule.importexport;

public record ImportResult(long batchId, String status, int importedRows, int issueCount, String message) {}
