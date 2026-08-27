ALTER TABLE solve_job
    ADD COLUMN idempotency_key VARCHAR(128),
    ADD COLUMN worker_id VARCHAR(128),
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN heartbeat_at TIMESTAMPTZ,
    ADD COLUMN attempt INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE UNIQUE INDEX uq_solve_job_active_idempotency
    ON solve_job (idempotency_key)
    WHERE idempotency_key IS NOT NULL AND status IN ('QUEUED', 'RUNNING');

CREATE INDEX idx_solve_job_claim
    ON solve_job (status, next_attempt_at, created_at);

UPDATE solve_job
SET status = 'QUEUED', next_attempt_at = CURRENT_TIMESTAMP
WHERE status = 'RUNNING';

UPDATE schedule_version
SET status = 'SOLVING'
WHERE status = 'SOLVING';
