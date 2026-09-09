package com.classschedule.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProblemCreateRequest(
        String termCode,
        Long versionId,
        Long solveJobId,
        Long importBatchId,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 10000) String description,
        @Size(max = 16) String priority,
        @Size(max = 32) String category,
        @Size(max = 10000) String evidence) {}
