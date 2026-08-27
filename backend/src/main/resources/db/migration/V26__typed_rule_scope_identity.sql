UPDATE schedule_rule_instance
SET scope_code = '__TERM__'
WHERE scope_type = 'TERM' AND scope_code IS NULL;

ALTER TABLE schedule_rule_instance
    ALTER COLUMN scope_code SET NOT NULL;
