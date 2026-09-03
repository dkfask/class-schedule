package com.classschedule.masterdata;

public record MasterDataList(
        java.util.List<MasterDataItem> items, int page, int size, long total) {}
