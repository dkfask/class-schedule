package com.classschedule.api;

public record RetrospectiveRequest(
        String termCode,
        String ruleAdaptation,
        String legacyIssues,
        String schoolFeedback,
        Integer supportInterventionCount) {}
