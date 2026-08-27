ALTER TABLE schedule_assignment
    ADD COLUMN activity_member_index INTEGER NOT NULL DEFAULT -1;

UPDATE schedule_assignment a
SET activity_member_index = m.member_index
FROM activity_group_member m
JOIN activity_group g ON g.id = m.activity_group_id
WHERE a.teaching_requirement_id = m.teaching_requirement_id
  AND a.activity_group_code = g.code;

CREATE INDEX idx_schedule_assignment_activity_member
    ON schedule_assignment(schedule_version_id, activity_group_code, activity_index, activity_member_index);
