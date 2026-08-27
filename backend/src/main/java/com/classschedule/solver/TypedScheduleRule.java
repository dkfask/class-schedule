package com.classschedule.solver;

public record TypedScheduleRule(String ruleCode, String scopeType, String scopeCode,
        Integer intValue, String textValue, String severity, int weight) {
    public static final String TERM_SCOPE_SENTINEL = "__TERM__";

    public String effectiveScopeCode() {
        return scopeCode == null || TERM_SCOPE_SENTINEL.equals(scopeCode) ? null : scopeCode;
    }

    public boolean appliesTo(String resourceCode) {
        String scope = effectiveScopeCode();
        return scope == null || scope.equals(resourceCode);
    }

    public int limit() { return intValue == null ? 0 : intValue; }

    public boolean isHard() { return "HARD".equalsIgnoreCase(severity); }
    public boolean isMedium() { return "MEDIUM".equalsIgnoreCase(severity); }
}
