package com.classschedule.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationCodeAttemptService {
    private final JdbcTemplate jdbc;

    public RegistrationCodeAttemptService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void increment(long codeId) {
        jdbc.update(
                "UPDATE auth_email_verification_code SET attempts = attempts + 1 WHERE id = ? AND consumed_at IS NULL",
                codeId);
    }
}
