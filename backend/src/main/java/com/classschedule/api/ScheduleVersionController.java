package com.classschedule.api;

import com.classschedule.schedule.ScheduleRepository;
import com.classschedule.schedule.ScheduleVersionView;
import com.classschedule.schedule.VersionMutationException;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schedule-versions")
public class ScheduleVersionController {
    private final ScheduleRepository repository;

    public ScheduleVersionController(ScheduleRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{versionId}")
    public ResponseEntity<ScheduleVersionView> get(@PathVariable Long versionId, Authentication authentication) {
        try {
            ScheduleVersionView version = repository.findVersion(versionId);
            if (isPublishedOnlyViewer(authentication) && !Set.of("PUBLISHED").contains(version.status())) return ResponseEntity.status(403).build();
            return ResponseEntity.ok(version);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    private boolean isPublishedOnlyViewer(Authentication authentication) {
        if (authentication == null) return false;
        boolean viewer = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_VIEWER".equals(a.getAuthority()));
        boolean planner = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_PLANNER".equals(a.getAuthority()));
        return viewer && !planner;
    }

    @PostMapping("/{versionId}/publish")
    public ResponseEntity<Map<String, Object>> publish(@PathVariable Long versionId,
            @RequestHeader(value = "If-Match", required = false) Long expectedRevision,
            Authentication authentication) {
        try {
            if (!repository.publish(versionId, expectedRevision, authentication.getName())) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "status", "NOT_PUBLISHABLE",
                        "message", "版本尚未通过全部任务已分配、硬约束为零和独立校验门禁"));
            }
            return ResponseEntity.ok(Map.of("versionId", versionId, "status", "PUBLISHED"));
        } catch (VersionMutationException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "status", "CONFLICT", "code", exception.code(), "versionId", exception.versionId(),
                    "currentRevision", exception.currentRevision(), "message", exception.getMessage()));
        }
    }
}
