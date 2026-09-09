package com.classschedule.security;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 公开邮箱注册：验证邮箱后默认赋予排课员角色（支持注册即可排课）。 */
@Service
public class RegistrationService {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AppUserRepository users;
    private final RegistrationEmailSender emailSender;
    private final RegistrationCodeAttemptService codeAttempts;
    private final boolean enabled;
    private final String defaultRole;
    private final Duration codeTtl;
    private final Duration resendInterval;
    private final int maxAttempts;
    private final String codeSecret;

    public RegistrationService(
            JdbcTemplate jdbc,
            PasswordEncoder encoder,
            AppUserRepository users,
            RegistrationEmailSender emailSender,
            RegistrationCodeAttemptService codeAttempts,
            @Value("${app.auth.registration.enabled:true}") boolean enabled,
            @Value("${app.auth.registration.default-role:PLANNER}") String defaultRole,
            @Value("${app.auth.email.code-ttl:10m}") Duration codeTtl,
            @Value("${app.auth.email.resend-interval:60s}") Duration resendInterval,
            @Value("${app.auth.email.max-attempts:5}") int maxAttempts,
            @Value("${app.auth.email.code-secret:change-this-email-code-secret}") String codeSecret) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.users = users;
        this.emailSender = emailSender;
        this.codeAttempts = codeAttempts;
        this.enabled = enabled;
        this.defaultRole = defaultRole == null ? "PLANNER" : defaultRole.trim();
        this.codeTtl = codeTtl;
        this.resendInterval = resendInterval;
        this.maxAttempts = maxAttempts;
        this.codeSecret = codeSecret == null ? "" : codeSecret;
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

    @Transactional
    public void requestRegistrationCode(String rawEmail) {
        ensureRegistrationEnabled();
        String email = normalizeEmail(rawEmail);
        Instant now = Instant.now();
        Integer recent =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM auth_email_verification_code WHERE email = ? AND purpose = 'REGISTRATION' AND created_at > ?",
                        Integer.class,
                        email,
                        java.sql.Timestamp.from(now.minus(resendInterval)));
        if (recent != null && recent > 0) {
            throw new RegistrationRejected(429, "CODE_RATE_LIMITED", "验证码发送过于频繁，请稍后再试");
        }

        Integer existingEmail =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM app_user WHERE LOWER(email) = LOWER(?)", Integer.class, email);
        Integer existingUsername =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM app_user WHERE username = ? AND email IS NULL",
                        Integer.class,
                        email);
        if ((existingEmail != null && existingEmail > 0)
                || (existingUsername != null && existingUsername > 0)) {
            // Do not disclose whether the address belongs to an account.
            return;
        }

        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        jdbc.update(
                "INSERT INTO auth_email_verification_code(email,purpose,code_hash,expires_at) VALUES(?,?,?,?)",
                email,
                "REGISTRATION",
                hashCode(email, code),
                java.sql.Timestamp.from(now.plus(codeTtl)));
        emailSender.sendRegistrationCode(email, code);
    }

    @Transactional
    public AppUserRepository.UserProfile register(
            String rawEmail, String password, String displayName, String verificationCode) {
        ensureRegistrationEnabled();
        String email = normalizeEmail(rawEmail);
        validatePassword(password);
        String name = displayName == null ? "" : displayName.trim();
        if (name.length() > 128) {
            throw new RegistrationRejected(400, "DISPLAY_NAME_INVALID", "显示名称过长");
        }
        if (verificationCode == null || !verificationCode.matches("\\d{6}")) {
            throw new RegistrationRejected(400, "CODE_INVALID", "请输入 6 位验证码");
        }

        Integer existingEmail =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM app_user WHERE LOWER(email) = LOWER(?)", Integer.class, email);
        if (existingEmail != null && existingEmail > 0) {
            throw new RegistrationRejected(409, "EMAIL_EXISTS", "邮箱已被注册");
        }
        Integer existingUsername =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM app_user WHERE username = ? AND email IS NULL",
                        Integer.class,
                        email);
        if (existingUsername != null && existingUsername > 0) {
            throw new RegistrationRejected(409, "EMAIL_USERNAME_CONFLICT", "邮箱与现有用户名冲突，请更换邮箱");
        }

        consumeCode(email, verificationCode);
        Long userId;
        try {
            userId =
                    jdbc.queryForObject(
                            "INSERT INTO app_user(username,password_hash,display_name,email,email_verified_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP) RETURNING id",
                            Long.class,
                            email,
                            encoder.encode(password),
                            name.isBlank() ? email : name,
                            email);
        } catch (DuplicateKeyException exception) {
            throw new RegistrationRejected(409, "EMAIL_EXISTS", "邮箱已被注册");
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
        return users.profile(email);
    }

    private void consumeCode(String email, String code) {
        var rows =
                jdbc.queryForList(
                        "SELECT id, code_hash, expires_at, attempts FROM auth_email_verification_code WHERE email = ? AND purpose = 'REGISTRATION' AND consumed_at IS NULL ORDER BY created_at DESC LIMIT 1",
                        email);
        if (rows.isEmpty()) {
            throw new RegistrationRejected(400, "CODE_INVALID", "验证码不正确或已失效");
        }
        Map<String, Object> row = rows.get(0);
        long id = ((Number) row.get("id")).longValue();
        int attempts = ((Number) row.get("attempts")).intValue();
        if (attempts >= maxAttempts) {
            throw new RegistrationRejected(400, "CODE_ATTEMPTS_EXCEEDED", "验证码错误次数过多，请重新获取");
        }
        Instant expiresAt = ((java.sql.Timestamp) row.get("expires_at")).toInstant();
        if (!expiresAt.isAfter(Instant.now())) {
            throw new RegistrationRejected(400, "CODE_EXPIRED", "验证码已过期，请重新获取");
        }
        String expected = (String) row.get("code_hash");
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), hashCode(email, code).getBytes(StandardCharsets.UTF_8))) {
            int nextAttempts = attempts + 1;
            codeAttempts.increment(id);
            if (nextAttempts >= maxAttempts) {
                throw new RegistrationRejected(400, "CODE_ATTEMPTS_EXCEEDED", "验证码错误次数过多，请重新获取");
            }
            throw new RegistrationRejected(400, "CODE_INVALID", "验证码不正确");
        }
        jdbc.update(
                "UPDATE auth_email_verification_code SET consumed_at = CURRENT_TIMESTAMP WHERE id = ? AND consumed_at IS NULL",
                id);
    }

    private void ensureRegistrationEnabled() {
        if (!enabled) {
            throw new RegistrationRejected(403, "REGISTRATION_DISABLED", "当前未开放注册");
        }
    }

    private String normalizeEmail(String rawEmail) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 320 || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new RegistrationRejected(400, "EMAIL_INVALID", "请输入有效的邮箱地址");
        }
        return email;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new RegistrationRejected(400, "PASSWORD_INVALID", "密码长度需为 8-128 位");
        }
    }

    private String hashCode(String email, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(codeSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HEX.formatHex(mac.doFinal((email + ":REGISTRATION:" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("无法生成邮箱验证码摘要", exception);
        }
    }
}
