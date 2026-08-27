ALTER TABLE activity_group
    ADD COLUMN term_id BIGINT REFERENCES academic_term(id) ON DELETE CASCADE;

UPDATE activity_group ag
SET term_id = (
    SELECT r.term_id
    FROM activity_group_member m
    JOIN teaching_requirement r ON r.id = m.teaching_requirement_id
    WHERE m.activity_group_id = ag.id
    ORDER BY r.id
    LIMIT 1
)
WHERE ag.term_id IS NULL;

UPDATE activity_group
SET term_id = (SELECT id FROM academic_term WHERE code = '2026-FALL')
WHERE term_id IS NULL;

ALTER TABLE activity_group
    ALTER COLUMN term_id SET NOT NULL;

ALTER TABLE activity_group_member
    ADD COLUMN member_index INTEGER;

WITH numbered AS (
    SELECT activity_group_id, teaching_requirement_id,
           ROW_NUMBER() OVER (PARTITION BY activity_group_id ORDER BY teaching_requirement_id) - 1 AS idx
    FROM activity_group_member
)
UPDATE activity_group_member m
SET member_index = numbered.idx
FROM numbered
WHERE m.activity_group_id = numbered.activity_group_id
  AND m.teaching_requirement_id = numbered.teaching_requirement_id;

ALTER TABLE activity_group_member
    ALTER COLUMN member_index SET NOT NULL;

CREATE UNIQUE INDEX uq_activity_group_member_index
    ON activity_group_member(activity_group_id, member_index);
CREATE INDEX idx_activity_group_term ON activity_group(term_id, active);

ALTER TABLE schedule_assignment
    ADD COLUMN teaching_requirement_id BIGINT REFERENCES teaching_requirement(id),
    ADD COLUMN requirement_code VARCHAR(64),
    ADD COLUMN activity_index INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN pinned_period_code VARCHAR(64),
    ADD COLUMN activity_type_snapshot VARCHAR(32);

UPDATE schedule_assignment
SET teaching_requirement_id = NULLIF(split_part(COALESCE(occurrence_key, ''), '-', 1), '')::BIGINT
WHERE occurrence_key ~ '^[0-9]+-[0-9]+$';

UPDATE schedule_assignment a
SET requirement_code = r.code,
    pinned_period_code = r.pinned_period_code
FROM teaching_requirement r
WHERE a.teaching_requirement_id = r.id;

UPDATE schedule_assignment
SET activity_type_snapshot = (
    SELECT ag.activity_type
    FROM activity_group ag
    WHERE ag.code = schedule_assignment.activity_group_code
);

CREATE INDEX idx_schedule_assignment_requirement
    ON schedule_assignment(schedule_version_id, teaching_requirement_id, activity_index);
