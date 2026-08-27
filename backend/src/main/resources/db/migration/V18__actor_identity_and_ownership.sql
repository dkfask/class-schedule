ALTER TABLE audit_event
    ADD COLUMN actor_user_id BIGINT REFERENCES app_user(id),
    ADD COLUMN actor_kind VARCHAR(32) NOT NULL DEFAULT 'USER',
    ADD COLUMN correlation_id VARCHAR(128),
    ADD COLUMN outcome VARCHAR(32) NOT NULL DEFAULT 'SUCCESS';

ALTER TABLE import_batch
    ADD COLUMN created_by_user_id BIGINT REFERENCES app_user(id);

ALTER TABLE solve_job
    ADD COLUMN submitted_by_user_id BIGINT REFERENCES app_user(id);

ALTER TABLE schedule_version
    ADD COLUMN edit_lock_owner_user_id BIGINT REFERENCES app_user(id);

INSERT INTO app_user(username, password_hash, display_name, enabled)
VALUES ('system', '{noop}system-service', '系统服务', TRUE)
ON CONFLICT (username) DO NOTHING;
