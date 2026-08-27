ALTER TABLE schedule_assignment
    ADD COLUMN duration INTEGER NOT NULL DEFAULT 1 CHECK (duration > 0);

CREATE INDEX idx_schedule_assignment_version_resource
    ON schedule_assignment (schedule_version_id, teacher_code, student_group_code, room_code);
