package com.classschedule.masterdata;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record TeachingRequirementRequest(
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 64) String termCode,
        @NotBlank @Size(max = 64) String studentGroupCode,
        @NotBlank @Size(max = 64) String subjectCode,
        @NotBlank @Size(max = 64) String teacherCode,
        @Positive int weeklyPeriods,
        @Positive int durationPeriods,
        @PositiveOrZero int studentCount,
        @Size(max = 512) String requiredFeatures,
        @Size(max = 64) String pinnedPeriodCode) {}
