package com.classschedule.api;

import jakarta.validation.constraints.NotBlank;

public record RoomFeatureRequest(
        @NotBlank String roomCode, @NotBlank String featureCode, String featureName) {}
