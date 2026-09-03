package com.classschedule.api;

import jakarta.validation.constraints.Size;

public record CommandActionRequest(@Size(max = 256) String idempotencyKey) {
    public String normalizedKey() {
        return idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
    }
}
