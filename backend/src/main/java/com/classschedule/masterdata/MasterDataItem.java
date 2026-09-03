package com.classschedule.masterdata;

import java.util.Map;

public record MasterDataItem(
        long id, String code, String name, boolean active, Map<String, Object> attributes) {}
