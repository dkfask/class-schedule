ALTER TABLE ai_model_settings
    ADD COLUMN protocol VARCHAR(16) NOT NULL DEFAULT 'OPENAI';

ALTER TABLE ai_model_settings
    ADD CONSTRAINT ai_model_settings_protocol_check
        CHECK (protocol IN ('OPENAI', 'ANTHROPIC'));
