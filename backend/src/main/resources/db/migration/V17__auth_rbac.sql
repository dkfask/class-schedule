CREATE TABLE app_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(128) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMPTZ
);

CREATE TABLE app_role (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE app_permission (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(128) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE app_user_role (
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES app_role(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE app_role_permission (
    role_id BIGINT NOT NULL REFERENCES app_role(id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES app_permission(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

INSERT INTO app_role(code, name) VALUES
    ('PLANNER', '排课员'),
    ('VIEWER', '只读用户'),
    ('AUDIT_READ', '审计查看者'),
    ('USER_ADMIN', '用户管理员')
ON CONFLICT (code) DO NOTHING;

INSERT INTO app_permission(code, name) VALUES
    ('SCHEDULE_READ', '查看课表'),
    ('SCHEDULE_EDIT', '调整课表'),
    ('SCHEDULE_PUBLISH', '发布课表'),
    ('MASTER_DATA_EDIT', '维护基础数据'),
    ('RULE_FACT_EDIT', '维护排课规则'),
    ('IMPORT_EXECUTE', '执行数据导入'),
    ('AUDIT_READ', '查看审计日志'),
    ('USER_MANAGE', '管理用户')
ON CONFLICT (code) DO NOTHING;

INSERT INTO app_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM app_role r CROSS JOIN app_permission p
WHERE r.code = 'PLANNER' AND p.code IN ('SCHEDULE_READ','SCHEDULE_EDIT','SCHEDULE_PUBLISH','MASTER_DATA_EDIT','RULE_FACT_EDIT','IMPORT_EXECUTE')
ON CONFLICT DO NOTHING;
INSERT INTO app_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM app_role r CROSS JOIN app_permission p
WHERE r.code = 'VIEWER' AND p.code = 'SCHEDULE_READ'
ON CONFLICT DO NOTHING;
INSERT INTO app_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM app_role r CROSS JOIN app_permission p
WHERE r.code = 'AUDIT_READ' AND p.code = 'AUDIT_READ'
ON CONFLICT DO NOTHING;
INSERT INTO app_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM app_role r CROSS JOIN app_permission p
WHERE r.code = 'USER_ADMIN' AND p.code = 'USER_MANAGE'
ON CONFLICT DO NOTHING;

CREATE INDEX app_user_role_user_idx ON app_user_role(user_id);
CREATE INDEX app_role_permission_role_idx ON app_role_permission(role_id);
