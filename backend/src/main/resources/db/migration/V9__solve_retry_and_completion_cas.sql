ALTER TABLE solve_job
    ADD COLUMN max_attempts INTEGER NOT NULL DEFAULT 3;

CREATE INDEX idx_solve_job_lease ON solve_job (status, lease_until);
