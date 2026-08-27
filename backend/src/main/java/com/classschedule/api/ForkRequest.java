package com.classschedule.api;

import jakarta.validation.constraints.NotBlank;

public record ForkRequest(@NotBlank String name) {}
