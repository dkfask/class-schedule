package com.classschedule.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schedule-versions")
public class RuleImpactPreviewController {
    private final RuleImpactPreviewService previews;

    public RuleImpactPreviewController(RuleImpactPreviewService previews) {
        this.previews = previews;
    }

    @PostMapping("/{versionId}/trial-solve/impact-preview")
    public ResponseEntity<?> preview(
            @PathVariable long versionId,
            @Valid @RequestBody ScheduleRuleRequest request,
            Authentication authentication) {
        try {
            return ResponseEntity.ok(
                    previews.preview(versionId, request, authentication.getName()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(java.util.Map.of("code", "IMPACT_PREVIEW_REJECTED", "message", exception.getMessage()));
        }
    }
}
