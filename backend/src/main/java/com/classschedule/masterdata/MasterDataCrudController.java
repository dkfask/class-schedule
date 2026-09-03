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
@RequestMapping("/api/master-data")
public class MasterDataCrudController {
    private final MasterDataRepository repository;

    public MasterDataCrudController(MasterDataRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{resource}")
    public ResponseEntity<?> list(
            @PathVariable String resource,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "true") boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ResponseEntity.ok(
                    repository.list(MasterDataResource.parse(resource), q, active, page, size));
        } catch (IllegalArgumentException exception) {
            return problem(HttpStatus.BAD_REQUEST, "UNSUPPORTED_RESOURCE", exception.getMessage());
        }
    }

    @GetMapping("/{resource}/{id}")
    public ResponseEntity<?> get(@PathVariable String resource, @PathVariable long id) {
        try {
            return ResponseEntity.ok(repository.get(MasterDataResource.parse(resource), id));
        } catch (IllegalArgumentException exception) {
            return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage());
        }
    }

    @PostMapping("/{resource}")
    public ResponseEntity<?> create(
            @PathVariable String resource, @Valid @RequestBody MasterDataRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(repository.save(MasterDataResource.parse(resource), request));
        } catch (IllegalArgumentException exception) {
            return problem(HttpStatus.CONFLICT, "MASTER_DATA_CONFLICT", exception.getMessage());
        }
    }

    @PatchMapping("/{resource}/{id}")
    public ResponseEntity<?> update(
            @PathVariable String resource,
            @PathVariable long id,
            @Valid @RequestBody MasterDataRequest request) {
        try {
            return ResponseEntity.ok(
                    repository.update(MasterDataResource.parse(resource), id, request));
        } catch (IllegalArgumentException exception) {
            return problem(HttpStatus.CONFLICT, "MASTER_DATA_CONFLICT", exception.getMessage());
        }
    }

    @DeleteMapping("/{resource}/{id}")
    public ResponseEntity<?> deactivate(@PathVariable String resource, @PathVariable long id) {
        try {
            repository.deactivate(MasterDataResource.parse(resource), id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException exception) {
            return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage());
        }
    }

    private ResponseEntity<Map<String, String>> problem(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}
