package com.classschedule.aiassist;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiModelSettingsRepository {
    private static final short SETTINGS_ID = 1;
    private final JdbcTemplate jdbc;

    public AiModelSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredSettings> find() {
        List<StoredSettings> rows =
                jdbc.query(
                        "SELECT base_url, model, protocol, api_key_ciphertext, updated_at FROM ai_model_settings WHERE id = ?",
                        (rs, rowNum) ->
                                new StoredSettings(
                                        rs.getString("base_url"),
                                        rs.getString("model"),
                                        rs.getString("protocol"),
                                        rs.getString("api_key_ciphertext"),
                                        rs.getObject("updated_at", OffsetDateTime.class)),
                        SETTINGS_ID);
        return rows.stream().findFirst();
    }

    public void save(
            String baseUrl, String model, String protocol, String apiKeyCiphertext, String username) {
        jdbc.update(
                """
                INSERT INTO ai_model_settings(id, base_url, model, protocol, api_key_ciphertext, updated_by_user_id, updated_at)
                VALUES (?, ?, ?, ?, ?, (SELECT id FROM app_user WHERE username = ?), CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO UPDATE SET
                    base_url = EXCLUDED.base_url,
                    model = EXCLUDED.model,
                    protocol = EXCLUDED.protocol,
                    api_key_ciphertext = EXCLUDED.api_key_ciphertext,
                    updated_by_user_id = EXCLUDED.updated_by_user_id,
                    updated_at = CURRENT_TIMESTAMP
                """,
                SETTINGS_ID,
                baseUrl,
                model,
                protocol,
                apiKeyCiphertext,
                username);
    }

    public record StoredSettings(
            String baseUrl,
            String model,
            String protocol,
            String apiKeyCiphertext,
            OffsetDateTime updatedAt) {}
}
