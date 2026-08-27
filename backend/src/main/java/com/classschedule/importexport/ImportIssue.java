package com.classschedule.importexport;

public record ImportIssue(String sheet, int row, String column, String code, String message) {}
