-- Preferred (original-timetable) timeslots per teaching requirement.
-- Semicolon-separated period-template codes, one per weekly occurrence by index;
-- blank means no preference. The solver treats mismatches as a soft penalty so a
-- re-solve stays close to the school's existing timetable.
ALTER TABLE teaching_requirement
    ADD COLUMN preferred_period_codes TEXT;
