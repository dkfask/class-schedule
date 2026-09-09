CREATE TABLE app_notification (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    audit_event_id BIGINT NOT NULL REFERENCES audit_event(id) ON DELETE CASCADE,
    kind VARCHAR(32) NOT NULL,
    title VARCHAR(200) NOT NULL,
    message TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'READ', 'DONE')),
    aggregate_type VARCHAR(128),
    aggregate_id VARCHAR(128),
    term_code VARCHAR(64),
    mandatory BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE (user_id, audit_event_id)
);

CREATE INDEX app_notification_user_status_idx
    ON app_notification(user_id, status, created_at DESC);
