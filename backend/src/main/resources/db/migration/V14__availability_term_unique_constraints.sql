ALTER TABLE teacher_availability
    DROP CONSTRAINT IF EXISTS teacher_availability_teacher_id_period_code_key;

ALTER TABLE room_availability
    DROP CONSTRAINT IF EXISTS room_availability_room_id_period_code_key;

ALTER TABLE teacher_availability
    ADD CONSTRAINT uq_teacher_availability_term_period
    UNIQUE (teacher_id, term_id, period_code);

ALTER TABLE room_availability
    ADD CONSTRAINT uq_room_availability_term_period
    UNIQUE (room_id, term_id, period_code);
