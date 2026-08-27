package com.classschedule.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record ExchangeRequest(
        long occurrenceId,
        long swapOccurrenceId,
        @NotBlank String reason,
        @PositiveOrZero Long expectedRevision) {
    public ExchangeRequest(long occurrenceId, long swapOccurrenceId, String reason) {
        this(occurrenceId, swapOccurrenceId, reason, null);
    }
}
