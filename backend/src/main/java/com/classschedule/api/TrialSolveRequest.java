package com.classschedule.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/** 试排请求：把一条条件写入学期规则，并 fork 当前版本为草稿带新条件求解。 */
public record TrialSolveRequest(
        @Valid @jakarta.validation.constraints.NotNull ScheduleRuleRequest rule,
        @Size(max = 128) String idempotencyKey) {}
