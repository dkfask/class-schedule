INSERT INTO app_role(code, name) VALUES
    ('REVIEWER', '排课审核员'),
    ('BUSINESS_OWNER', '业务负责人')
ON CONFLICT (code) DO NOTHING;

INSERT INTO app_permission(code, name) VALUES
    ('SCHEDULE_REVIEW', '审核候选课表和发布门禁')
ON CONFLICT (code) DO NOTHING;

INSERT INTO app_role_permission(role_id, permission_id)
SELECT r.id, p.id
FROM app_role r CROSS JOIN app_permission p
WHERE r.code IN ('REVIEWER', 'BUSINESS_OWNER')
  AND p.code IN ('SCHEDULE_READ', 'SCHEDULE_REVIEW')
ON CONFLICT DO NOTHING;
