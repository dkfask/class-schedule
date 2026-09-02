ALTER TABLE activity_group
    DROP CONSTRAINT IF EXISTS activity_group_code_key;

ALTER TABLE activity_group
    ADD CONSTRAINT uq_activity_group_term_code UNIQUE (term_id, code);

-- A teaching requirement has one activity_group_code in the solver model. Do not
-- silently discard an existing membership when upgrading an old database.
DO $$
DECLARE
    duplicate_requirements TEXT;
BEGIN
    SELECT string_agg(teaching_requirement_id::TEXT, ', ' ORDER BY teaching_requirement_id)
    INTO duplicate_requirements
    FROM (
        SELECT teaching_requirement_id
        FROM activity_group_member
        GROUP BY teaching_requirement_id
        HAVING COUNT(*) > 1
        ORDER BY teaching_requirement_id
        LIMIT 20
    ) duplicates;

    IF duplicate_requirements IS NOT NULL THEN
        RAISE EXCEPTION 'V30 cannot enforce one activity group per teaching requirement; duplicate teaching_requirement_id values: %', duplicate_requirements;
    END IF;
END $$;

CREATE UNIQUE INDEX uq_activity_group_member_requirement
    ON activity_group_member (teaching_requirement_id);
