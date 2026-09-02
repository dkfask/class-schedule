UPDATE solve_job
SET submitted_by_user_id = (SELECT id FROM app_user WHERE username = 'system')
WHERE submitted_by_user_id IS NULL;

ALTER TABLE schedule_scenario
    DROP CONSTRAINT IF EXISTS schedule_scenario_owner_user_id_fkey;

ALTER TABLE schedule_scenario
    ALTER COLUMN owner_user_id SET NOT NULL;

ALTER TABLE schedule_scenario
    ADD CONSTRAINT schedule_scenario_owner_user_id_fkey
    FOREIGN KEY (owner_user_id) REFERENCES app_user(id) ON DELETE RESTRICT;

ALTER TABLE schedule_version
    DROP CONSTRAINT IF EXISTS schedule_version_owner_user_id_fkey;

ALTER TABLE schedule_version
    ALTER COLUMN owner_user_id SET NOT NULL;

ALTER TABLE schedule_version
    ADD CONSTRAINT schedule_version_owner_user_id_fkey
    FOREIGN KEY (owner_user_id) REFERENCES app_user(id) ON DELETE RESTRICT;

ALTER TABLE solve_job
    ALTER COLUMN submitted_by_user_id SET NOT NULL;
