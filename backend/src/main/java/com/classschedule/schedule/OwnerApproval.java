package com.classschedule.schedule;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.OffsetDateTime;

public record OwnerApproval(
        String status, String comment, String decidedBy, OffsetDateTime decidedAt, boolean required) {
    public static OwnerApproval none() {
        return new OwnerApproval("NONE", null, null, null, false);
    }

    public static OwnerApproval of(
            String status,
            String comment,
            String decidedBy,
            OffsetDateTime decidedAt,
            boolean required) {
        String normalized = status == null || status.isBlank() ? "NONE" : status.trim();
        return new OwnerApproval(normalized, comment, decidedBy, decidedAt, required);
    }

    @JsonIgnore
    public boolean approved() {
        return "APPROVED".equals(status);
    }

    @JsonIgnore
    public boolean rejected() {
        return "REJECTED".equals(status);
    }

    @JsonIgnore
    public boolean pending() {
        return "PENDING".equals(status);
    }

    @JsonIgnore
    public boolean blocksPublish() {
        return required && !"APPROVED".equals(status);
    }
}
