package com.classschedule.api;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/terms/copy")
public class TermCopyController {
    private final TermCopyService service;

    public TermCopyController(TermCopyService service) {
        this.service = service;
    }

    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody TermCopyRequest request) {
        try {
            return ResponseEntity.ok(service.preview(request));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_TERM_COPY", "message", exception.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> copy(@RequestBody TermCopyRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(service.copy(request));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_TERM_COPY", "message", exception.getMessage()));
        }
    }
}
