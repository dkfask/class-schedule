package com.classschedule.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AvailabilityRequest(@NotBlank String resourceCode, @NotBlank String termCode, @NotBlank String periodCode, @NotNull Boolean available) {}
