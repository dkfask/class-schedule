ALTER TABLE student_group
    ADD COLUMN student_count INTEGER NOT NULL DEFAULT 0 CHECK (student_count >= 0);

ALTER TABLE teacher_availability
    ADD COLUMN term_id BIGINT REFERENCES academic_term(id);

ALTER TABLE room_availability
    ADD COLUMN term_id BIGINT REFERENCES academic_term(id);

UPDATE teacher_availability
SET term_id = (SELECT id FROM academic_term WHERE code = '2026-FALL')
WHERE term_id IS NULL;

UPDATE room_availability
SET term_id = (SELECT id FROM academic_term WHERE code = '2026-FALL')
WHERE term_id IS NULL;

ALTER TABLE teacher_availability
    ALTER COLUMN term_id SET NOT NULL;

ALTER TABLE room_availability
    ALTER COLUMN term_id SET NOT NULL;

CREATE TABLE student_group_availability (
    id BIGSERIAL PRIMARY KEY,
    student_group_id BIGINT NOT NULL REFERENCES student_group(id) ON DELETE CASCADE,
    term_id BIGINT NOT NULL REFERENCES academic_term(id) ON DELETE CASCADE,
    period_code VARCHAR(64) NOT NULL,
    available BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (student_group_id, term_id, period_code)
);

CREATE TABLE room_feature_catalog (
    code VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO room_feature_catalog (code, name)
SELECT DISTINCT feature_code, feature_code
FROM room_feature
ON CONFLICT (code) DO NOTHING;

ALTER TABLE room_feature
    ADD CONSTRAINT fk_room_feature_catalog
    FOREIGN KEY (feature_code) REFERENCES room_feature_catalog(code);

CREATE TABLE teaching_requirement_feature (
    teaching_requirement_id BIGINT NOT NULL REFERENCES teaching_requirement(id) ON DELETE CASCADE,
    feature_code VARCHAR(64) NOT NULL REFERENCES room_feature_catalog(code),
    PRIMARY KEY (teaching_requirement_id, feature_code)
);

ALTER TABLE teaching_requirement
    ADD COLUMN student_count INTEGER NOT NULL DEFAULT 0 CHECK (student_count >= 0);

CREATE TABLE activity_group (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    activity_type VARCHAR(32) NOT NULL CHECK (activity_type IN ('JOINED', 'SYNCHRONIZED', 'CONSECUTIVE')),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE activity_group_member (
    activity_group_id BIGINT NOT NULL REFERENCES activity_group(id) ON DELETE CASCADE,
    teaching_requirement_id BIGINT NOT NULL REFERENCES teaching_requirement(id) ON DELETE CASCADE,
    PRIMARY KEY (activity_group_id, teaching_requirement_id)
);

ALTER TABLE schedule_assignment
    ADD COLUMN occurrence_key VARCHAR(128),
    ADD COLUMN activity_group_code VARCHAR(64);

UPDATE schedule_assignment
SET occurrence_key = occurrence_id::VARCHAR
WHERE occurrence_key IS NULL;

-- Existing clients may omit the new key; current writers populate it and readers fall back to occurrence_id.
CREATE UNIQUE INDEX uq_schedule_assignment_version_occurrence_key
    ON schedule_assignment (schedule_version_id, occurrence_key)
    WHERE occurrence_key IS NOT NULL;

CREATE INDEX idx_teacher_availability_term_period
    ON teacher_availability (teacher_id, term_id, period_code);

CREATE INDEX idx_room_availability_term_period
    ON room_availability (room_id, term_id, period_code);

CREATE INDEX idx_student_group_availability_term_period
    ON student_group_availability (student_group_id, term_id, period_code);

CREATE INDEX idx_activity_group_member_requirement
    ON activity_group_member (teaching_requirement_id);
