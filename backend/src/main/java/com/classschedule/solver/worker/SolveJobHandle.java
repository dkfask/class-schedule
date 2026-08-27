package com.classschedule.solver.worker;

public record SolveJobHandle(long jobId, long versionId, String status) {}
