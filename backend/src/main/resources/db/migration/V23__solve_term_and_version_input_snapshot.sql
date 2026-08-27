ALTER TABLE schedule_version
    ADD COLUMN rule_snapshot_hash VARCHAR(128),
    ADD COLUMN snapshot_term_code VARCHAR(64),
    ADD COLUMN input_snapshot_at TIMESTAMPTZ;

CREATE INDEX idx_schedule_version_snapshot
    ON schedule_version(snapshot_term_code, input_snapshot_hash, rule_snapshot_hash);
