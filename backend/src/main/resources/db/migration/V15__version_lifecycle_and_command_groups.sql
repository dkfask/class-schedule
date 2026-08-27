ALTER TABLE schedule_version
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN archived_at TIMESTAMPTZ,
    ADD COLUMN edit_locked BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN edit_lock_owner VARCHAR(128),
    ADD COLUMN edit_lock_reason VARCHAR(512),
    ADD COLUMN edit_locked_at TIMESTAMPTZ;

CREATE TABLE adjustment_command_group (
    id UUID PRIMARY KEY,
    schedule_version_id BIGINT NOT NULL REFERENCES schedule_version(id) ON DELETE CASCADE,
    command_type VARCHAR(32) NOT NULL CHECK (command_type IN ('ADJUST', 'EXCHANGE')),
    base_revision BIGINT NOT NULL CHECK (base_revision >= 0),
    result_revision BIGINT NOT NULL CHECK (result_revision >= 0),
    state VARCHAR(32) NOT NULL DEFAULT 'APPLIED' CHECK (state IN ('APPLIED', 'UNDONE', 'SUPERSEDED')),
    idempotency_key VARCHAR(256) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    actor VARCHAR(128) NOT NULL DEFAULT 'planner',
    reason VARCHAR(512) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (schedule_version_id, idempotency_key)
);

CREATE INDEX adjustment_command_group_version_idx
    ON adjustment_command_group(schedule_version_id, created_at DESC, id);

ALTER TABLE adjustment_command
    ADD COLUMN command_group_id UUID REFERENCES adjustment_command_group(id) ON DELETE CASCADE,
    ADD COLUMN sequence SMALLINT,
    ADD COLUMN from_source VARCHAR(32),
    ADD COLUMN to_source VARCHAR(32),
    ADD COLUMN from_locked BOOLEAN,
    ADD COLUMN to_locked BOOLEAN;

CREATE UNIQUE INDEX adjustment_command_group_sequence_uq
    ON adjustment_command(command_group_id, sequence)
    WHERE command_group_id IS NOT NULL;

CREATE INDEX adjustment_command_version_idx
    ON adjustment_command(schedule_version_id, created_at DESC, id);

CREATE TABLE adjustment_command_event (
    id BIGSERIAL PRIMARY KEY,
    command_group_id UUID NOT NULL REFERENCES adjustment_command_group(id) ON DELETE CASCADE,
    schedule_version_id BIGINT NOT NULL REFERENCES schedule_version(id) ON DELETE CASCADE,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN ('APPLY', 'UNDO', 'REDO')),
    from_revision BIGINT NOT NULL CHECK (from_revision >= 0),
    to_revision BIGINT NOT NULL CHECK (to_revision >= 0),
    idempotency_key VARCHAR(256),
    actor VARCHAR(128) NOT NULL DEFAULT 'planner',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX adjustment_command_event_idempotency_uq
    ON adjustment_command_event(schedule_version_id, event_type, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX adjustment_command_event_group_idx
    ON adjustment_command_event(command_group_id, created_at DESC, id);
