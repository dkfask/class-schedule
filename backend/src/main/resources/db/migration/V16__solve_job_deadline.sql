ALTER TABLE solve_job
    ADD COLUMN deadline_at TIMESTAMPTZ;

UPDATE solve_job
SET deadline_at = created_at + INTERVAL '15 minutes'
WHERE deadline_at IS NULL;

ALTER TABLE solve_job
    ALTER COLUMN deadline_at SET NOT NULL;
