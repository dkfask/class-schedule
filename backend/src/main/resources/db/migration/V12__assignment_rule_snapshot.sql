ALTER TABLE schedule_assignment
    ADD COLUMN student_count INTEGER NOT NULL DEFAULT 0 CHECK (student_count >= 0),
    ADD COLUMN required_features TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN room_features TEXT[] NOT NULL DEFAULT '{}';

CREATE INDEX idx_schedule_assignment_version_occurrence_key
    ON schedule_assignment (schedule_version_id, occurrence_key);
