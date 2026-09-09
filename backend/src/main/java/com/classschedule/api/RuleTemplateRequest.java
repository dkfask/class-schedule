package com.classschedule.api;

public record RuleTemplateRequest(
        String sourceTermCode,
        String code,
        String name,
        String description,
        String maintainedBy,
        String changeNote) {}
