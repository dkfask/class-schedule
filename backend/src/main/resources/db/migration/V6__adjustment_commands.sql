CREATE TABLE adjustment_command (
    id BIGSERIAL PRIMARY KEY,
    schedule_version_id BIGINT NOT NULL REFERENCES schedule_version(id) ON DELETE CASCADE,
    occurrence_id BIGINT NOT NULL,
    from_timeslot_code VARCHAR(64),
    to_timeslot_code VARCHAR(64),
    from_room_code VARCHAR(64),
    to_room_code VARCHAR(64),
    reason VARCHAR(512) NOT NULL,
    actor VARCHAR(128) NOT NULL DEFAULT 'planner',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
