package com.classschedule.api;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class DatabaseConstraintAdvice {
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handle(DataAccessException exception) {
        String message =
                exception.getMostSpecificCause() == null
                        ? String.valueOf(exception.getMessage())
                        : String.valueOf(exception.getMostSpecificCause().getMessage());
        if (message.contains("VERSION_IMMUTABLE")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(
                            Map.of(
                                    "status",
                                    "CONFLICT",
                                    "code",
                                    "VERSION_IMMUTABLE",
                                    "message",
                                    message));
        }
        if (message.contains("LEGACY_IDENTITY_UNVERIFIED")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(
                            Map.of(
                                    "status",
                                    "CONFLICT",
                                    "code",
                                    "LEGACY_IDENTITY_UNVERIFIED",
                                    "message",
                                    message));
        }
        throw exception;
    }
}
