package com.classschedule.api;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rule-templates")
public class RuleTemplateController {
    private final RuleTemplateService service;

    public RuleTemplateController(RuleTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public Object list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody RuleTemplateRequest request, Authentication authentication) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request, authentication.getName()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_RULE_TEMPLATE", "message", exception.getMessage()));
        }
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<?> preview(@PathVariable long id, @RequestParam String termCode) {
        try {
            return ResponseEntity.ok(service.preview(id, termCode));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_RULE_TEMPLATE", "message", exception.getMessage()));
        }
    }

    @PostMapping("/{id}/apply")
    public ResponseEntity<?> apply(
            @PathVariable long id,
            @RequestBody RuleTemplateApplyRequest request,
            Authentication authentication) {
        try {
            return ResponseEntity.ok(service.apply(id, request.termCode(), authentication.getName()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "RULE_TEMPLATE_NOT_APPLIED", "message", exception.getMessage()));
        }
    }
}
