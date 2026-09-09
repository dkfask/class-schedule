package com.classschedule.api;

import jakarta.validation.constraints.Size;

public record ProblemUpdateRequest(
        @Size(max = 16) String status,
        @Size(max = 32) String category,
        @Size(max = 16) String priority,
        @Size(max = 128) String assignee,
        @Size(max = 10000) String resolution,
        @Size(max = 10000) String evidence) {}
