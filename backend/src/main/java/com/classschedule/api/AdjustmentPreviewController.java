package com.classschedule.api;

import com.classschedule.schedule.ScheduleAssignmentView;
import com.classschedule.schedule.ScheduleRepository;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schedule-versions")
public class AdjustmentPreviewController {
    private final ScheduleRepository repository;
    public AdjustmentPreviewController(ScheduleRepository repository) { this.repository = repository; }

    @PreAuthorize("hasRole('PLANNER')")
    @PostMapping("/{versionId}/adjustments/preview")
    public ResponseEntity<AdjustmentPreviewResponse> preview(@PathVariable long versionId, @Valid @RequestBody AdjustmentPreviewRequest request) {
        return ResponseEntity.ok(repository.previewAdjustment(versionId, request));
    }
}
