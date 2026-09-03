package com.classschedule.api;

import com.classschedule.schedule.ScheduleOptionsRepository;
import com.classschedule.schedule.ScheduleRepository;
import com.classschedule.schedule.ScheduleVersionRepositoryModels;
import com.classschedule.schedule.ScheduleVersionView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schedule-versions")
public class ScheduleVersionQueryController {
    private final ScheduleRepository versions;
    private final ScheduleOptionsRepository options;

    public ScheduleVersionQueryController(
            ScheduleRepository versions, ScheduleOptionsRepository options) {
        this.versions = versions;
        this.options = options;
    }

    @GetMapping
    public ScheduleVersionRepositoryModels.VersionPage versions(
            @RequestParam(required = false) String termCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return versions.listVersions(
                termCode,
                isViewer(authentication) ? "PUBLISHED" : status,
                page,
                size,
                authentication.getName());
    }

    @GetMapping("/{versionId}/diff")
    public ResponseEntity<?> diff(
            @PathVariable long versionId,
            @RequestParam(required = false) Long againstVersionId,
            Authentication authentication) {
        if (!versions.canAccessVersion(versionId, authentication.getName()))
            return ResponseEntity.notFound().build();
        if (againstVersionId != null
                && !versions.canAccessVersion(againstVersionId, authentication.getName()))
            return ResponseEntity.notFound().build();
        if (isViewer(authentication))
            return ResponseEntity.status(403)
                    .body(
                            java.util.Map.of(
                                    "code", "VIEWER_PUBLISHED_ONLY", "message", "只读用户不能读取内部版本差异"));
        return ResponseEntity.ok(versions.diff(versionId, againstVersionId));
    }

    @GetMapping("/{versionId}/options")
    public ResponseEntity<?> options(@PathVariable long versionId, Authentication authentication) {
        if (!versions.canAccessVersion(versionId, authentication.getName()))
            return ResponseEntity.notFound().build();
        if (isViewer(authentication))
            return ResponseEntity.status(403)
                    .body(
                            java.util.Map.of(
                                    "code", "VIEWER_PUBLISHED_ONLY", "message", "只读用户不能读取内部版本选项"));
        return ResponseEntity.ok(options.options(versionId));
    }

    @GetMapping("/{versionId}/filtered")
    public ResponseEntity<?> filtered(
            @PathVariable long versionId,
            @RequestParam(defaultValue = "CLASS") String view,
            @RequestParam(required = false) String resourceCode,
            Authentication authentication) {
        ScheduleVersionView version;
        try {
            version = versions.findVersion(versionId);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
        if (isPublishedOnlyViewer(authentication)
                && !java.util.Set.of("PUBLISHED").contains(version.status()))
            return ResponseEntity.status(403)
                    .body(
                            java.util.Map.of(
                                    "code", "VIEWER_PUBLISHED_ONLY", "message", "只读用户只能查看已发布课表"));
        if (!versions.canAccessVersion(versionId, authentication.getName()))
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok(versions.findVersionFiltered(versionId, view, resourceCode));
    }

    private boolean isViewer(Authentication authentication) {
        return isPublishedOnlyViewer(authentication);
    }

    private boolean isPublishedOnlyViewer(Authentication authentication) {
        if (authentication == null) return false;
        boolean viewer =
                authentication.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_VIEWER".equals(a.getAuthority()));
        boolean planner =
                authentication.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_PLANNER".equals(a.getAuthority()));
        return viewer && !planner;
    }
}
