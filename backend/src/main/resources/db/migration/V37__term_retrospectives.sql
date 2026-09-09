CREATE TABLE term_retrospective (
    id BIGSERIAL PRIMARY KEY,
    term_id BIGINT NOT NULL UNIQUE REFERENCES academic_term(id) ON DELETE CASCADE,
    rule_adaptation TEXT,
    legacy_issues TEXT,
    school_feedback TEXT,
    support_intervention_count INTEGER NOT NULL DEFAULT 0 CHECK (support_intervention_count >= 0),
    updated_by_user_id BIGINT REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
