ALTER TABLE app_user
    ALTER COLUMN username TYPE VARCHAR(320),
    ADD COLUMN email VARCHAR(320),
    ADD COLUMN email_verified_at TIMESTAMPTZ;

CREATE UNIQUE INDEX app_user_email_lower_uq
    ON app_user (LOWER(email))
    WHERE email IS NOT NULL;

CREATE TABLE auth_email_verification_code (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    code_hash VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    consumed_at TIMESTAMPTZ,
    request_ip VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT auth_email_verification_code_purpose_ck CHECK (purpose IN ('REGISTRATION')),
    CONSTRAINT auth_email_verification_code_attempts_ck CHECK (attempts >= 0)
);

CREATE INDEX auth_email_verification_code_lookup_idx
    ON auth_email_verification_code (email, purpose, created_at DESC);
