package com.classschedule.security;

import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 公开注册：新账号默认只读角色，排课员仍由引导账号/管理员控制。 */
@Service
public class RegistrationService {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{3,64}$");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AppUserRepository users;
    private final boolean enabled;
    private final String defaultRole;

    public RegistrationService(
            JdbcTemplate jdbc,
            PasswordEncoder encoder,
            AppUserRepository users,
            @Value("${app.auth.registration.enabled:true}") boolean enabled,
            @Value("${app.auth.registration.default-role:VIEWER}") String defaultRole) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.users = users;
        this.enabled = enabled;
        this.defaultRole = defaultRole == null ? "VIEWER" : defaultRole.trim();
    }

    /** 注册被拒绝时抛出；status/code 供控制器映射响应。 */
    public static class RegistrationRejected extends RuntimeException {
        public final int status;
        public final String code;

        public RegistrationRejected(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    public AppUserRepository.UserProfile register(String username, String password, String displayName) {
        if (!enabled) {
            throw new RegistrationRejected(403, "REGISTRATION_DISABLED", "当前未开放注册");
        }
        String normalized = username == null ? "" : username.trim();
        if (!USERNAME_PATTERN.matcher(normalized).matches()) {
            throw new RegistrationRejected(
                    400, "USERNAME_INVALID", "用户名需为 3-64 位字母、数字、下划线或中划线");
        }
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new RegistrationRejected(400, "PASSWORD_INVALID", "密码长度需为 8-128 位");
        }
        String name = displayName == null ? "" : displayName.trim();
        if (name.length() > 128) {
            throw new RegistrationRejected(400, "DISPLAY_NAME_INVALID", "显示名称过长");
        }
        Integer existing =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM app_user WHERE username = ?", Integer.class, normalized);
        if (existing != null && existing > 0) {
            throw new RegistrationRejected(409, "USERNAME_EXISTS", "用户名已被占用");
        }
        Long userId;
        try {
            userId =
                    jdbc.queryForObject(
                            "INSERT INTO app_user(username, password_hash, display_name) VALUES (?, ?, ?) RETURNING id",
                            Long.class,
                            normalized,
                            encoder.encode(password),
                            name.isBlank() ? normalized : name);
        } catch (DuplicateKeyException exception) {
            throw new RegistrationRejected(409, "USERNAME_EXISTS", "用户名已被占用");
        }
        jdbc.update(
                "INSERT INTO app_user_role(user_id, role_id) SELECT ?, id FROM app_role WHERE code = ? AND active = TRUE",
                userId,
                defaultRole);
        Map<String, Object> role =
                jdbc.queryForMap(
                        "SELECT COUNT(*) AS matched FROM app_user_role ur JOIN app_role r ON r.id = ur.role_id WHERE ur.user_id = ? AND r.code = ?",
                        userId,
                        defaultRole);
        if (((Number) role.get("matched")).intValue() == 0) {
            throw new RegistrationRejected(500, "ROLE_UNAVAILABLE", "默认角色不可用: " + defaultRole);
        }
        return users.profile(normalized);
    }
}
