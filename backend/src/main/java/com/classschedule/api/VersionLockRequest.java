package com.classschedule.api;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record VersionLockRequest(
        @Size(max = 128) String owner,
        @Size(max = 512) String reason,
        @PositiveOrZero Long expectedRevision) {
    public VersionLockRequest(String owner, String reason) {
        this(owner, reason, null);
    }

    public String normalizedOwner() {
        return owner == null || owner.isBlank() ? "planner" : owner.trim();
    }

    public String normalizedReason() {
        return reason == null ? "" : reason.trim();
    }
}
