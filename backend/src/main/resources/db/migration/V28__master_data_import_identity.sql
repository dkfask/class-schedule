ALTER TABLE import_batch
    ADD COLUMN template_type VARCHAR(32),
    ADD COLUMN template_version VARCHAR(32),
    ADD COLUMN schema_hash VARCHAR(64),
    ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX idx_import_batch_template_identity
    ON import_batch (template_type, template_version, schema_hash);
