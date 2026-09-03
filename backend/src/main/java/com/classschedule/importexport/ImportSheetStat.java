package com.classschedule.importexport;

public record ImportSheetStat(String sheet, int rows, int created, int updated, int deactivated) {
    public static ImportSheetStat empty(String sheet) {
        return new ImportSheetStat(sheet, 0, 0, 0, 0);
    }
}
