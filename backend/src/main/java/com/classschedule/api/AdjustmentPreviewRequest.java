package com.classschedule.api;

import jakarta.validation.constraints.NotBlank;

public record AdjustmentPreviewRequest(long occurrenceId, @NotBlank String timeslotCode, @NotBlank String roomCode) {}
