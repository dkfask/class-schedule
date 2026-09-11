ALTER TABLE student_group
    ADD COLUMN home_room_id BIGINT REFERENCES room(id);

ALTER TABLE teaching_requirement
    ADD COLUMN room_assignment_mode VARCHAR(16) NOT NULL DEFAULT 'HOME'
        CHECK (room_assignment_mode IN ('HOME', 'FLEXIBLE'));

CREATE UNIQUE INDEX uq_student_group_home_room
    ON student_group (home_room_id)
    WHERE home_room_id IS NOT NULL;

-- Keep the seeded sample aligned with the primary-school default: one
-- administrative class owns one ordinary classroom.
UPDATE student_group g
SET home_room_id = r.id
FROM room r
WHERE (g.code, r.code) IN (('G7-1', 'A101'), ('G7-2', 'A102'))
  AND g.home_room_id IS NULL;
