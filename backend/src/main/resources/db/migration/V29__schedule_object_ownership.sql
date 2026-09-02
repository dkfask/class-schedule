ALTER TABLE schedule_scenario
    ADD COLUMN owner_user_id BIGINT REFERENCES app_user(id) ON DELETE SET NULL;

ALTER TABLE schedule_version
    ADD COLUMN owner_user_id BIGINT REFERENCES app_user(id) ON DELETE SET NULL;

UPDATE schedule_scenario
SET owner_user_id = (SELECT id FROM app_user WHERE username = 'system')
WHERE owner_user_id IS NULL;

-- V27 protects published rows from all updates except the explicit archive
-- transition. Ownership is migration metadata, so suspend only that trigger
-- while backfilling the new column and restore it before the migration ends.
ALTER TABLE schedule_version DISABLE TRIGGER schedule_version_state_protection;
UPDATE schedule_version
SET owner_user_id = (SELECT id FROM app_user WHERE username = 'system')
WHERE owner_user_id IS NULL;
ALTER TABLE schedule_version ENABLE TRIGGER schedule_version_state_protection;

CREATE INDEX schedule_scenario_owner_idx ON schedule_scenario(owner_user_id);
CREATE INDEX schedule_version_owner_idx ON schedule_version(owner_user_id);
