CREATE INDEX audit_event_aggregate_time_idx
    ON audit_event(aggregate_type, aggregate_id, created_at DESC);
CREATE INDEX audit_event_actor_time_idx
    ON audit_event(actor_user_id, created_at DESC);
CREATE INDEX import_batch_owner_idx ON import_batch(created_by_user_id, created_at DESC);
CREATE INDEX solve_job_owner_idx ON solve_job(submitted_by_user_id, created_at DESC);
