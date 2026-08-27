package com.classschedule.api;

import jakarta.validation.constraints.NotBlank;

public record RequirementFeatureRequest(@NotBlank String requirementCode, @NotBlank String featureCode) {}
