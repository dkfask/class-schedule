CREATE TABLE problem_record (
    id BIGSERIAL PRIMARY KEY,
    term_id BIGINT REFERENCES academic_term(id),
    schedule_version_id BIGINT REFERENCES schedule_version(id),
    solve_job_id BIGINT REFERENCES solve_job(id),
    import_batch_id BIGINT REFERENCES import_batch(id),
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    priority VARCHAR(16) NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    category VARCHAR(32) NOT NULL DEFAULT 'TO_CONFIRM' CHECK (category IN ('DATA', 'RULE', 'PRODUCT', 'OPERATION', 'TO_CONFIRM')),
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    evidence TEXT,
    resolution TEXT,
    reported_by_user_id BIGINT REFERENCES app_user(id),
    assignee VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ
);

CREATE INDEX problem_record_term_status_idx ON problem_record(term_id, status, created_at DESC);
CREATE INDEX problem_record_context_idx ON problem_record(schedule_version_id, solve_job_id, import_batch_id);
