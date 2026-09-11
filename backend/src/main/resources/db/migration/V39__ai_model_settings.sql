CREATE TABLE ai_model_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    base_url VARCHAR(512) NOT NULL DEFAULT '',
    model VARCHAR(128) NOT NULL DEFAULT '',
    api_key_ciphertext TEXT,
    updated_by_user_id BIGINT REFERENCES app_user(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO app_permission(code, name)
VALUES ('AI_CONFIG_MANAGE', '管理 AI 模型配置')
ON CONFLICT (code) DO NOTHING;

INSERT INTO app_role_permission(role_id, permission_id)
SELECT r.id, p.id
FROM app_role r
CROSS JOIN app_permission p
WHERE r.code = 'USER_ADMIN' AND p.code = 'AI_CONFIG_MANAGE'
ON CONFLICT DO NOTHING;
