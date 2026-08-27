CREATE TABLE schedule_rule_profile (
    id BIGSERIAL PRIMARY KEY,
    term_id BIGINT NOT NULL REFERENCES academic_term(id) ON DELETE CASCADE,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE(term_id, code)
);

CREATE TABLE schedule_rule_instance (
    id BIGSERIAL PRIMARY KEY,
    profile_id BIGINT NOT NULL REFERENCES schedule_rule_profile(id) ON DELETE CASCADE,
    rule_code VARCHAR(64) NOT NULL,
    scope_type VARCHAR(32) NOT NULL CHECK (scope_type IN ('TERM','TEACHER','STUDENT_GROUP','SUBJECT','TEACHING_REQUIREMENT')),
    scope_code VARCHAR(128),
    int_value INTEGER,
    text_value VARCHAR(512),
    severity VARCHAR(16) NOT NULL DEFAULT 'HARD' CHECK (severity IN ('HARD','MEDIUM','SOFT')),
    weight INTEGER NOT NULL DEFAULT 1 CHECK (weight > 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE(profile_id, rule_code, scope_type, scope_code)
);

CREATE INDEX schedule_rule_profile_term_idx ON schedule_rule_profile(term_id, active);
CREATE INDEX schedule_rule_instance_scope_idx ON schedule_rule_instance(scope_type, scope_code, active);
