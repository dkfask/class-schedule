CREATE TABLE rule_template (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    name VARCHAR(128) NOT NULL,
    description TEXT,
    maintained_by VARCHAR(128),
    change_note TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(code, version)
);

CREATE TABLE rule_template_item (
    id BIGSERIAL PRIMARY KEY,
    template_id BIGINT NOT NULL REFERENCES rule_template(id) ON DELETE CASCADE,
    rule_code VARCHAR(64) NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    scope_code VARCHAR(128) NOT NULL,
    int_value INTEGER,
    text_value VARCHAR(512),
    severity VARCHAR(16) NOT NULL,
    weight INTEGER NOT NULL CHECK (weight > 0),
    UNIQUE(template_id, rule_code, scope_type, scope_code)
);

CREATE INDEX rule_template_active_idx ON rule_template(active, code, version DESC);
CREATE INDEX rule_template_item_template_idx ON rule_template_item(template_id, rule_code);
