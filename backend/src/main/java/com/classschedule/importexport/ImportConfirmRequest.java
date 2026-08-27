package com.classschedule.importexport;

import jakarta.validation.constraints.NotNull;

public record ImportConfirmRequest(@NotNull Long batchId) {}
