ALTER TABLE schedule_version
    ADD COLUMN IF NOT EXISTS owner_approval_status VARCHAR(32) NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS owner_approval_comment TEXT,
    ADD COLUMN IF NOT EXISTS owner_approved_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS owner_approved_at TIMESTAMPTZ;

UPDATE schedule_version v
SET owner_approval_status = 'PENDING',
    owner_approval_comment = NULL,
    owner_approved_by = NULL,
    owner_approved_at = NULL
WHERE v.status IN ('DRAFT', 'CANDIDATE')
  AND v.owner_approval_status = 'NONE'
  AND EXISTS (
        SELECT 1
        FROM app_user u
        JOIN app_user_role ur ON ur.user_id = u.id
        JOIN app_role r ON r.id = ur.role_id
        WHERE u.enabled = TRUE
          AND r.active = TRUE
          AND r.code = 'BUSINESS_OWNER'
    );
