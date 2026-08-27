package com.classschedule.rules;

import com.classschedule.api.ScheduleRuleRequest;
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
@RequestMapping("/api/schedule-rules")
public class ScheduleRuleController {
    private final ScheduleRuleRepository repository;
    public ScheduleRuleController(ScheduleRuleRepository repository) { this.repository = repository; }

    @GetMapping
    public Object list(@RequestParam(defaultValue = "2026-FALL") String termCode) { return repository.list(termCode); }

    @GetMapping("/catalog")
    public Object catalog() { return repository.catalog(); }

    @PostMapping
    public ResponseEntity<?> upsert(@Valid @RequestBody ScheduleRuleRequest request) {
        try { repository.upsert(request); return ResponseEntity.ok(Map.of("status", "UPDATED")); }
        catch (IllegalArgumentException exception) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "REJECTED", "message", exception.getMessage())); }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        try { repository.delete(id); return ResponseEntity.noContent().build(); }
        catch (IllegalArgumentException exception) { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage())); }
    }
}
