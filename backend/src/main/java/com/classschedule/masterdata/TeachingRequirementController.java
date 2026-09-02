package com.classschedule.masterdata;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/master-data/teaching-requirements")
public class TeachingRequirementController {
    private final TeachingRequirementRepository repository;
    public TeachingRequirementController(TeachingRequirementRepository repository) { this.repository = repository; }

    @GetMapping public Object list(@RequestParam(required = false) String termCode, @RequestParam(defaultValue="true") boolean active) { return repository.list(termCode, active); }
    @GetMapping("/{id}") public Object get(@PathVariable long id) { return repository.get(id); }
    @PostMapping public ResponseEntity<?> create(@Valid @RequestBody TeachingRequirementRequest request) { try { return ResponseEntity.status(HttpStatus.CREATED).body(repository.create(request)); } catch (IllegalArgumentException e) { return problem(e.getMessage()); } }
    @PatchMapping("/{id}") public ResponseEntity<?> update(@PathVariable long id, @Valid @RequestBody TeachingRequirementRequest request) { try { return ResponseEntity.ok(repository.update(id, request)); } catch (IllegalArgumentException e) { return problem(e.getMessage()); } }
    @DeleteMapping("/{id}") public ResponseEntity<?> deactivate(@PathVariable long id) { try { repository.deactivate(id); return ResponseEntity.noContent().build(); } catch (IllegalArgumentException e) { return problem(e.getMessage()); } }
    private ResponseEntity<Map<String,String>> problem(String message) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code","TEACHING_REQUIREMENT_CONFLICT","message",message)); }
}
