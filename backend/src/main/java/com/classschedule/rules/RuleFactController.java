package com.classschedule.rules;

import com.classschedule.api.ActivityGroupRequest;
import com.classschedule.api.AvailabilityRequest;
import com.classschedule.api.RequirementFeatureRequest;
import com.classschedule.api.RoomFeatureRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rule-facts")
public class RuleFactController {
    private final RuleFactRepository repository;
    public RuleFactController(RuleFactRepository repository) { this.repository = repository; }

    @GetMapping("/availability")
    public ResponseEntity<?> availabilityList(@RequestParam(required = false) String termCode) {
        try {
            return ResponseEntity.ok(repository.listAvailability(termCode));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage()));
        }
    }

    @DeleteMapping("/availability/{resourceType}")
    public ResponseEntity<?> deleteAvailability(@PathVariable String resourceType, @Valid @RequestBody AvailabilityRequest request) {
        return execute(() -> repository.deleteAvailability(resourceType, request));
    }

    @GetMapping("/features")
    public Object features() { return repository.listFeatureCatalog(); }

    @GetMapping("/room-features")
    public Object roomFeatures(@RequestParam(required = false) String roomCode) { return repository.listRoomFeatures(roomCode); }

    @DeleteMapping("/room-features")
    public ResponseEntity<?> deleteRoomFeature(@RequestParam String roomCode, @RequestParam String featureCode) {
        return execute(() -> repository.deleteRoomFeature(roomCode, featureCode));
    }

    @GetMapping("/requirement-features")
    public Object requirementFeatures(@RequestParam(required = false) String requirementCode) { return repository.listRequirementFeatures(requirementCode); }

    @DeleteMapping("/requirement-features")
    public ResponseEntity<?> deleteRequirementFeature(@RequestParam String requirementCode, @RequestParam String featureCode) {
        return execute(() -> repository.deleteRequirementFeature(requirementCode, featureCode));
    }

    @GetMapping("/activity-groups")
    public Object activityGroups(@RequestParam(required = false) String termCode) { return repository.listActivityGroups(termCode); }

    @DeleteMapping("/activity-groups/{code}")
    public ResponseEntity<?> deleteActivityGroup(@PathVariable String code, @RequestParam(required = false) String termCode) { return execute(() -> repository.deleteActivityGroup(code, termCode)); }

    @PostMapping("/availability/{resourceType}")
    public ResponseEntity<?> availability(@PathVariable String resourceType, @Valid @RequestBody AvailabilityRequest request) {
        return execute(() -> repository.upsertAvailability(resourceType, request));
    }

    @PostMapping("/room-features")
    public ResponseEntity<?> roomFeature(@Valid @RequestBody RoomFeatureRequest request) {
        return execute(() -> repository.upsertRoomFeature(request));
    }

    @PostMapping("/requirement-features")
    public ResponseEntity<?> requirementFeature(@Valid @RequestBody RequirementFeatureRequest request) {
        return execute(() -> repository.addRequirementFeature(request));
    }

    @PostMapping("/activity-groups")
    public ResponseEntity<?> activityGroup(@Valid @RequestBody ActivityGroupRequest request) {
        return execute(() -> repository.upsertActivityGroup(request));
    }

    private ResponseEntity<?> execute(Runnable action) {
        try {
            action.run();
            return ResponseEntity.ok(Map.of("status", "UPDATED"));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "REJECTED", "message", exception.getMessage()));
        }
    }
}
