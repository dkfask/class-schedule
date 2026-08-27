package com.classschedule.api;

import jakarta.validation.constraints.NotBlank;

public record ScheduleRuleRequest(
        @NotBlank String termCode,
        @NotBlank String ruleCode,
        @NotBlank String scopeType,
        String scopeCode,
        Integer intValue,
        String textValue,
        String severity,
        Integer weight) {
    public String normalizedSeverity() { return severity == null || severity.isBlank() ? "HARD" : severity.trim().toUpperCase(); }
    public int normalizedIntValue() { return intValue == null ? 0 : intValue; }
    public int normalizedWeight() { return weight == null || weight <= 0 ? 1 : weight; }
}
