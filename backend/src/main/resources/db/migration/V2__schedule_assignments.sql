CREATE TABLE schedule_assignment (
    id BIGSERIAL PRIMARY KEY,
    schedule_version_id BIGINT NOT NULL REFERENCES schedule_version(id) ON DELETE CASCADE,
    occurrence_id BIGINT NOT NULL,
    subject_code VARCHAR(64) NOT NULL,
    subject_name VARCHAR(128) NOT NULL,
    teacher_code VARCHAR(64) NOT NULL,
    teacher_name VARCHAR(128) NOT NULL,
    student_group_code VARCHAR(64) NOT NULL,
    student_group_name VARCHAR(128) NOT NULL,
    timeslot_code VARCHAR(64),
    timeslot_label VARCHAR(128),
    weekday SMALLINT,
    period_no SMALLINT,
    room_code VARCHAR(64),
    room_name VARCHAR(128),
    source VARCHAR(32) NOT NULL DEFAULT 'SOLVER',
    locked BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (schedule_version_id, occurrence_id)
);

CREATE INDEX idx_schedule_assignment_version_slot
    ON schedule_assignment (schedule_version_id, timeslot_code);
