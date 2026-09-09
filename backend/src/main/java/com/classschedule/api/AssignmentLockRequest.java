package com.classschedule.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AssignmentLockRequest(
        @NotBlank @Size(max = 512) String reason, @PositiveOrZero Long expectedRevision) {
    public AssignmentLockRequest(String reason) {
        this(reason, null);
    }

    public String normalizedReason() {
        return reason == null ? "" : reason.trim();
    }
}
