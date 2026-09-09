package com.classschedule.api;

import java.time.LocalDate;
import java.util.Map;

public record TermCopyRequest(
        String sourceTermCode,
        String targetTermCode,
        String targetName,
        LocalDate startDate,
        LocalDate endDate,
        Map<String, String> teacherMappings,
        Map<String, String> studentGroupMappings,
        Map<String, String> subjectMappings,
        Map<String, String> roomMappings) {}
