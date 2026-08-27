DROP INDEX IF EXISTS uq_solve_job_active_idempotency;

CREATE UNIQUE INDEX uq_solve_job_owner_active_idempotency
    ON solve_job (submitted_by_user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL
      AND status IN ('QUEUED', 'RUNNING')
      AND submitted_by_user_id IS NOT NULL;

CREATE UNIQUE INDEX uq_solve_job_legacy_active_idempotency
    ON solve_job (idempotency_key)
    WHERE idempotency_key IS NOT NULL
      AND status IN ('QUEUED', 'RUNNING')
      AND submitted_by_user_id IS NULL;
