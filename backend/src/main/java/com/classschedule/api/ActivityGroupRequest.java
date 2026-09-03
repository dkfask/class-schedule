package com.classschedule.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ActivityGroupRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotBlank String activityType,
        @NotEmpty List<String> requirementCodes,
        @Size(max = 64) String termCode) {
    public ActivityGroupRequest(
            String code, String name, String activityType, List<String> requirementCodes) {
        this(code, name, activityType, requirementCodes, null);
    }

    public String normalizedTermCode() {
        return termCode == null || termCode.isBlank() ? null : termCode.trim();
    }
}
