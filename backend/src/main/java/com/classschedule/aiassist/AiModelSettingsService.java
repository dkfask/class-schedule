package com.classschedule.aiassist;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiModelSettingsService {
    private static final int MAX_BASE_URL_LENGTH = 512;
    private static final int MAX_MODEL_LENGTH = 128;
    private static final int MAX_PROTOCOL_LENGTH = 16;
    private static final int MAX_API_KEY_LENGTH = 4096;
    private static final int NONCE_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final AiModelSettingsRepository repository;
    private final JdbcTemplate jdbc;
    private final String envBaseUrl;
    private final String envApiKey;
    private final String envModel;
    private final String encryptionKey;
    private final SecureRandom secureRandom = new SecureRandom();
    private volatile Snapshot snapshot;

    public AiModelSettingsService(
            AiModelSettingsRepository repository,
            JdbcTemplate jdbc,
            @Value("${app.ai.base-url:}") String envBaseUrl,
            @Value("${app.ai.api-key:}") String envApiKey,
            @Value("${app.ai.model:}") String envModel,
            @Value("${app.ai.config-encryption-key:}") String encryptionKey) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.envBaseUrl = trim(envBaseUrl);
        this.envApiKey = trim(envApiKey);
        this.envModel = trim(envModel);
        this.encryptionKey = trim(encryptionKey);
    }

    @PostConstruct
    void initialize() {
        reload();
    }

    synchronized void reload() {
        snapshot = load();
    }

    public Snapshot current() {
        Snapshot current = snapshot;
        if (current != null) return current;
        synchronized (this) {
            if (snapshot == null) snapshot = load();
            return snapshot;
        }
    }

    @Transactional
    public synchronized Snapshot update(
            String requestedBaseUrl,
            String requestedProtocol,
            String requestedModel,
            String requestedApiKey,
            boolean clearApiKey,
            String username) {
        String baseUrl = normalizeBaseUrl(requestedBaseUrl);
        String protocol = normalizeProtocol(requestedProtocol, baseUrl);
        String model = trim(requestedModel);
        if (model.length() > MAX_MODEL_LENGTH) {
            throw new IllegalArgumentException("模型名称不能超过 " + MAX_MODEL_LENGTH + " 个字符");
        }

        Snapshot previous = current();
        String apiKey = trim(requestedApiKey);
        String apiKeyCiphertext;
        boolean apiKeyConfigured;
        if (clearApiKey) {
            apiKeyCiphertext = null;
            apiKeyConfigured = false;
        } else if (!apiKey.isBlank()) {
            if (apiKey.length() > MAX_API_KEY_LENGTH) {
                throw new IllegalArgumentException("API Key 过长");
            }
            apiKeyCiphertext = encrypt(apiKey);
            apiKeyConfigured = true;
        } else if (previous.apiKeyCiphertext() != null) {
            apiKeyCiphertext = previous.apiKeyCiphertext();
            apiKeyConfigured = true;
        } else if (!previous.apiKey().isBlank()) {
            apiKeyCiphertext = encrypt(previous.apiKey());
            apiKeyConfigured = true;
        } else {
            apiKeyCiphertext = null;
            apiKeyConfigured = false;
        }

        repository.save(baseUrl, model, protocol, apiKeyCiphertext, username);
        jdbc.update(
                "INSERT INTO audit_event (action, aggregate_type, aggregate_id, actor, actor_user_id, actor_kind, outcome, detail) "
                        + "VALUES ('AI_MODEL_SETTINGS_UPDATED', 'AI_MODEL_SETTINGS', '1', ?, "
                        + "(SELECT id FROM app_user WHERE username = ?), 'USER', 'SUCCESS', "
                        + "jsonb_build_object('baseUrlHost', ?::text, 'protocol', ?::text, "
                        + "'model', ?::text, 'apiKeyConfigured', ?::boolean))",
                username,
                username,
                hostForAudit(baseUrl),
                protocol,
                model,
                apiKeyConfigured);
        snapshot = load();
        return snapshot;
    }

    public PublicSettings publicSettings() {
        Snapshot current = current();
        return new PublicSettings(
                current.baseUrl(),
                current.protocol(),
                current.model(),
                current.apiKeyConfigured(),
                current.chatEnabled(),
                current.persisted() ? "ADMIN" : "ENVIRONMENT",
                current.updatedAt());
    }

    private Snapshot load() {
        return repository
                .find()
                .map(
                        stored -> {
                            String storedCiphertext = trimToNull(stored.apiKeyCiphertext());
                            String apiKey =
                                    storedCiphertext == null ? "" : decryptOrEmpty(storedCiphertext);
                            return new Snapshot(
                                    trim(stored.baseUrl()),
                                    normalizeProtocol(stored.protocol(), stored.baseUrl()),
                                    trim(stored.model()),
                                    apiKey,
                                    storedCiphertext != null,
                                    storedCiphertext,
                                    true,
                                    stored.updatedAt());
                        })
                .orElseGet(
                        () ->
                        new Snapshot(
                                envBaseUrl,
                                "OPENAI",
                                envModel,
                                        envApiKey,
                                        !envApiKey.isBlank(),
                                        null,
                                        false,
                                        null));
    }

    private String normalizeBaseUrl(String value) {
        String normalized = trim(value);
        if (normalized.length() > MAX_BASE_URL_LENGTH) {
            throw new IllegalArgumentException("服务地址不能超过 " + MAX_BASE_URL_LENGTH + " 个字符");
        }
        if (normalized.isBlank()) return "";
        try {
            URI uri = URI.create(normalized);
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException("服务地址必须是合法的 HTTP(S) 地址");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("服务地址必须是合法的 HTTP(S) 地址", exception);
        }
        return normalized.replaceAll("/+$", "");
    }

    private String normalizeProtocol(String value, String baseUrl) {
        String normalized = trim(value).toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return baseUrl.toLowerCase(Locale.ROOT).contains("/anthropic")
                    ? "ANTHROPIC"
                    : "OPENAI";
        }
        if (normalized.length() > MAX_PROTOCOL_LENGTH
                || !("OPENAI".equals(normalized) || "ANTHROPIC".equals(normalized))) {
            throw new IllegalArgumentException("接口协议必须是 OPENAI 或 ANTHROPIC");
        }
        return normalized;
    }

    private String encrypt(String value) {
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_LENGTH, nonce));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder()
                    .encodeToString(ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法加密 AI API Key，请检查 APP_AI_CONFIG_ENCRYPTION_KEY", exception);
        }
    }

    private String decryptOrEmpty(String value) {
        if (encryptionKey.isBlank()) return "";
        try {
            byte[] packed = Base64.getDecoder().decode(value);
            if (packed.length <= NONCE_LENGTH) throw new GeneralSecurityException("密文长度无效");
            byte[] nonce = java.util.Arrays.copyOfRange(packed, 0, NONCE_LENGTH);
            byte[] ciphertext = java.util.Arrays.copyOfRange(packed, NONCE_LENGTH, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_LENGTH, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return "";
        }
    }

    private SecretKeySpec secretKey() throws GeneralSecurityException {
        if (encryptionKey.isBlank()) {
            throw new GeneralSecurityException("APP_AI_CONFIG_ENCRYPTION_KEY 未配置");
        }
        return new SecretKeySpec(
                MessageDigest.getInstance("SHA-256")
                        .digest(encryptionKey.getBytes(StandardCharsets.UTF_8)),
                "AES");
    }

    private String hostForAudit(String baseUrl) {
        if (baseUrl.isBlank()) return "";
        try {
            return URI.create(baseUrl).getHost();
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        String trimmed = trim(value);
        return trimmed.isBlank() ? null : trimmed;
    }

    public record Snapshot(
            String baseUrl,
            String protocol,
            String model,
            String apiKey,
            boolean apiKeyConfigured,
            String apiKeyCiphertext,
            boolean persisted,
            OffsetDateTime updatedAt) {
        public boolean chatEnabled() {
            return !baseUrl.isBlank() && !model.isBlank() && !apiKey.isBlank();
        }
    }

    public record PublicSettings(
            String baseUrl,
            String protocol,
            String model,
            boolean apiKeyConfigured,
            boolean chatEnabled,
            String source,
            OffsetDateTime updatedAt) {}
}
