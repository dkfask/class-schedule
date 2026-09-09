package com.classschedule.api;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/retrospectives")
public class RetrospectiveController {
    private final RetrospectiveService service;

    public RetrospectiveController(RetrospectiveService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> get(@RequestParam String termCode) {
        try {
            return ResponseEntity.ok(service.get(termCode));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_RETROSPECTIVE", "message", exception.getMessage()));
        }
    }

    @PatchMapping
    public ResponseEntity<?> save(@RequestBody RetrospectiveRequest request, Authentication authentication) {
        try {
            return ResponseEntity.ok(service.save(request, authentication.getName()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_RETROSPECTIVE", "message", exception.getMessage()));
        }
    }
}
