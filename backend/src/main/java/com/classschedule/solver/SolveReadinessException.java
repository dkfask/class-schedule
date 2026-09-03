package com.classschedule.solver;

public class SolveReadinessException extends IllegalArgumentException {
    private final SolveReadiness readiness;

    public SolveReadinessException(SolveReadiness readiness) {
        super(message(readiness));
        this.readiness = readiness;
    }

    public SolveReadiness readiness() {
        return readiness;
    }

    private static String message(SolveReadiness readiness) {
        return readiness.issues().stream().map(SolveReadiness.Issue::message).reduce((left, right) -> left + "；" + right).orElse("排课基础数据未就绪");
    }
}
