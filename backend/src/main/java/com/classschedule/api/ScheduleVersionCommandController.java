package com.classschedule.api;

import com.classschedule.schedule.ScheduleRepository;
import com.classschedule.schedule.ScheduleVersionRepositoryModels;
import com.classschedule.schedule.VersionMutationException;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/schedule-versions")
public class ScheduleVersionCommandController {
    private final ScheduleRepository repository;

    public ScheduleVersionCommandController(ScheduleRepository repository) {
        this.repository = repository;
    }

    @PostMapping("/{versionId}/fork")
    public ResponseEntity<Map<String, Object>> fork(
            @PathVariable long versionId, @Valid @RequestBody ForkRequest request, Authentication authentication) {
        try {
            long newVersionId = repository.fork(versionId, request.name(), authentication.getName());
            return ResponseEntity.ok(Map.of("versionId", newVersionId, "status", "DRAFT"));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/{versionId}/lock")
    public ResponseEntity<?> lock(@PathVariable long versionId,
            @Valid @RequestBody VersionLockRequest request, Authentication authentication) {
        String actor = authentication.getName();
        try {
            repository.lockVersion(versionId, actor, request.normalizedReason(), request.expectedRevision());
            return ResponseEntity.ok(Map.of("versionId", versionId, "status", "LOCKED"));
        } catch (VersionMutationException exception) {
            return conflict(exception);
        }
    }

    @DeleteMapping("/{versionId}/lock")
    public ResponseEntity<?> unlock(@PathVariable long versionId,
            @RequestHeader(value = "If-Match", required = false) Long expectedRevision,
            Authentication authentication) {
        String actor = authentication.getName();
        try {
            repository.unlockVersion(versionId, actor, expectedRevision);
            return ResponseEntity.ok(Map.of("versionId", versionId, "status", "UNLOCKED"));
        } catch (VersionMutationException exception) {
            return conflict(exception);
        }
    }

    @PostMapping("/{versionId}/archive")
    public ResponseEntity<?> archive(@PathVariable long versionId,
            @RequestHeader(value = "If-Match", required = false) Long expectedRevision,
            Authentication authentication) {
        String actor = authentication.getName();
        try {
            repository.archive(versionId, expectedRevision, actor);
            return ResponseEntity.ok(Map.of("versionId", versionId, "status", "ARCHIVED"));
        } catch (VersionMutationException exception) {
            return conflict(exception);
        }
    }

    @GetMapping("/{versionId}/adjustments/commands")
    public ResponseEntity<?> commandHistory(@PathVariable long versionId, Authentication authentication) {
        try {
            if (isPublishedOnlyViewer(authentication)) return ResponseEntity.status(403).body(Map.of("code", "VIEWER_PUBLISHED_ONLY", "message", "只读用户不能读取内部命令历史"));
            if (!repository.canAccessVersion(versionId, authentication.getName())) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(repository.commandHistory(versionId));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage()));
        }
    }

    private boolean isPublishedOnlyViewer(Authentication authentication) {
        if (authentication == null) return false;
        boolean viewer = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_VIEWER".equals(a.getAuthority()));
        boolean planner = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_PLANNER".equals(a.getAuthority()));
        return viewer && !planner;
    }

    @PostMapping("/{versionId}/adjustments/commands/{groupId}/undo")
    public ResponseEntity<?> undo(@PathVariable long versionId, @PathVariable UUID groupId,
            @Valid @RequestBody(required = false) CommandActionRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        String actor = authentication.getName();
        try {
            String key = idempotencyKey != null ? idempotencyKey : request == null ? null : request.normalizedKey();
            ScheduleVersionRepositoryModels.MutationResult result = repository.undo(versionId, groupId, null, actor, key);
            return mutationResponse(versionId, result, "UNDONE");
        } catch (VersionMutationException exception) {
            return conflict(exception);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "UNDO_REJECTED", "message", exception.getMessage()));
        }
    }

    @PostMapping("/{versionId}/adjustments/commands/{groupId}/redo")
    public ResponseEntity<?> redo(@PathVariable long versionId, @PathVariable UUID groupId,
            @Valid @RequestBody(required = false) CommandActionRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        String actor = authentication.getName();
        try {
            String key = idempotencyKey != null ? idempotencyKey : request == null ? null : request.normalizedKey();
            ScheduleVersionRepositoryModels.MutationResult result = repository.redo(versionId, groupId, null, actor, key);
            return mutationResponse(versionId, result, "REDONE");
        } catch (VersionMutationException exception) {
            return conflict(exception);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "REDO_REJECTED", "message", exception.getMessage()));
        }
    }

    @PostMapping("/{versionId}/adjustments/exchange-candidates")
    public ResponseEntity<?> exchangeCandidates(@PathVariable long versionId, @Valid @RequestBody ExchangeCandidatesRequest request, Authentication authentication) {
        try {
            if (!repository.canAccessVersion(versionId, authentication.getName())) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(repository.exchangeCandidates(versionId, request));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/{versionId}/adjustments/exchange")
    public ResponseEntity<?> exchange(@PathVariable long versionId, @Valid @RequestBody ExchangeRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        try {
            ScheduleVersionRepositoryModels.MutationResult result = repository.exchange(versionId, request, authentication.getName(), idempotencyKey);
            return mutationResponse(versionId, result, "EXCHANGED");
        } catch (VersionMutationException exception) {
            return conflict(exception);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "EXCHANGE_REJECTED", "message", exception.getMessage()));
        }
    }

    @PostMapping("/{versionId}/adjustments/{occurrenceId}")
    public ResponseEntity<?> adjust(@PathVariable long versionId, @PathVariable long occurrenceId,
            @Valid @RequestBody AdjustmentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        try {
            ScheduleVersionRepositoryModels.MutationResult result = repository.adjust(versionId, occurrenceId,
                    request.timeslotCode(), request.roomCode(), request.reason(), authentication.getName(), request.expectedRevision(), idempotencyKey);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("commandId", result.commandIds().get(0));
            response.put("commandIds", result.commandIds());
            response.put("groupId", result.groupId());
            response.put("versionId", versionId);
            response.put("occurrenceId", occurrenceId);
            response.put("revision", result.revision());
            response.put("status", "ADJUSTED");
            response.put("validation", Map.of("allowed", true));
            return ResponseEntity.ok(response);
        } catch (VersionMutationException exception) {
            return conflict(exception);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "ADJUSTMENT_REJECTED", "validation", Map.of("allowed", false), "message", exception.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> mutationResponse(long versionId,
            ScheduleVersionRepositoryModels.MutationResult result, String status) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("versionId", versionId);
        response.put("groupId", result.groupId());
        response.put("commandIds", result.commandIds());
        response.put("revision", result.revision());
        response.put("status", status);
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<Map<String, Object>> conflict(VersionMutationException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "CONFLICT");
        body.put("code", exception.code());
        body.put("versionId", exception.versionId());
        body.put("currentRevision", exception.currentRevision());
        body.put("message", exception.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
