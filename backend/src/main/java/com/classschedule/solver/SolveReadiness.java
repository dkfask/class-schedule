package com.classschedule.solver;

import java.util.List;

public record SolveReadiness(
        String termCode,
        boolean ready,
        int timeslotCount,
        int roomCount,
        int requirementCount,
        List<Issue> issues) {
    public record Issue(String code, String message) {}
}
