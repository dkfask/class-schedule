package com.classschedule.importexport;

import java.util.List;

public record ImportPreview(long batchId, String status, String sha256, List<String> sheets, List<ImportIssue> issues) {}
