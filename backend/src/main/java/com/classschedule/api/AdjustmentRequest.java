package com.classschedule.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record AdjustmentRequest(
        @NotBlank String timeslotCode,
        @NotBlank String roomCode,
        @NotBlank String reason,
        @PositiveOrZero Long expectedRevision) {
    public AdjustmentRequest(String timeslotCode, String roomCode, String reason) {
        this(timeslotCode, roomCode, reason, null);
    }
}
