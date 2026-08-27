ALTER TABLE teaching_requirement ADD COLUMN code VARCHAR(64);
UPDATE teaching_requirement SET code = 'REQ-' || id WHERE code IS NULL;
ALTER TABLE teaching_requirement ALTER COLUMN code SET NOT NULL;
ALTER TABLE teaching_requirement ADD CONSTRAINT uq_teaching_requirement_code UNIQUE (code);

CREATE TABLE import_batch (
    id BIGSERIAL PRIMARY KEY,
    sha256 VARCHAR(64) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_bytes BYTEA NOT NULL,
    status VARCHAR(32) NOT NULL,
    term_code VARCHAR(64),
    row_count INTEGER NOT NULL DEFAULT 0,
    issue_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    imported_at TIMESTAMPTZ
);

CREATE INDEX idx_import_batch_sha256 ON import_batch (sha256);
