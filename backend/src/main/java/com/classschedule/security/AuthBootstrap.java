package com.classschedule.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AuthBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;

    public AuthBootstrap(JdbcTemplate jdbc, PasswordEncoder encoder,
            @Value("${app.auth.bootstrap.username:}") String username,
            @Value("${app.auth.bootstrap.password:}") String password) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isBlank() || password.isBlank()) return;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username = ?", Integer.class, username);
        if (count != null && count > 0) return;
        Long userId = jdbc.queryForObject("INSERT INTO app_user(username,password_hash,display_name) VALUES(?,?,?) RETURNING id", Long.class, username, encoder.encode(password), username);
        jdbc.update("INSERT INTO app_user_role(user_id, role_id) SELECT ?, id FROM app_role WHERE code = 'PLANNER'", userId);
    }
}
