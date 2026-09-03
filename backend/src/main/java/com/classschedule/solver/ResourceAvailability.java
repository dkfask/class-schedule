package com.classschedule.solver;

public record ResourceAvailability(
        String resourceType, String resourceCode, String periodCode, boolean available) {}
