package com.classschedule.api;

import com.classschedule.solver.SolveReadiness;
import com.classschedule.solver.SolveReadinessException;
import com.classschedule.solver.SolveReadinessService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/solve-readiness")
@PreAuthorize("hasRole('PLANNER')")
public class SolveReadinessController {
    private final SolveReadinessService readiness;

    public SolveReadinessController(SolveReadinessService readiness) {
        this.readiness = readiness;
    }

    @GetMapping
    public SolveReadiness check(@RequestParam String termCode) {
        return readiness.check(termCode);
    }

    public static ResponseEntity<Map<String, Object>> blocked(SolveReadinessException exception) {
        SolveReadiness result = exception.readiness();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "SOLVER_DATA_NOT_READY",
                "message", exception.getMessage(),
                "readiness", result));
    }
}
