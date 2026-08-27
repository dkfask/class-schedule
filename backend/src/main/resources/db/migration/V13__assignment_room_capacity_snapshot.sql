ALTER TABLE schedule_assignment
    ADD COLUMN room_capacity INTEGER NOT NULL DEFAULT 0 CHECK (room_capacity >= 0);
