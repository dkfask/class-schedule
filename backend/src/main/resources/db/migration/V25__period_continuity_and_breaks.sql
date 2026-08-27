ALTER TABLE period_template
    ADD COLUMN continuity_group VARCHAR(64),
    ADD COLUMN break_after BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE period_template
SET continuity_group = 'DAY-' || weekday
WHERE continuity_group IS NULL;

ALTER TABLE period_template
    ALTER COLUMN continuity_group SET NOT NULL;

CREATE INDEX idx_period_template_term_day_sequence
    ON period_template(term_id, weekday, period_no, continuity_group);
