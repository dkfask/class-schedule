ALTER TABLE schedule_version
    ADD COLUMN legacy_identity_unverified BOOLEAN NOT NULL DEFAULT TRUE;

CREATE OR REPLACE FUNCTION prevent_terminal_schedule_version_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status IN ('PUBLISHED', 'ARCHIVED') THEN
        RAISE EXCEPTION 'VERSION_IMMUTABLE: terminal schedule version cannot be deleted'
            USING ERRCODE = 'P0001';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER schedule_version_terminal_delete
BEFORE DELETE ON schedule_version
FOR EACH ROW EXECUTE FUNCTION prevent_terminal_schedule_version_delete();

CREATE OR REPLACE FUNCTION protect_schedule_version_state()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'ARCHIVED' THEN
        RAISE EXCEPTION 'VERSION_IMMUTABLE: archived schedule version cannot be updated'
            USING ERRCODE = 'P0001';
    END IF;

    IF OLD.status = 'PUBLISHED' THEN
        IF NEW.status = 'ARCHIVED'
           AND NEW.scenario_id = OLD.scenario_id
           AND NEW.parent_version_id IS NOT DISTINCT FROM OLD.parent_version_id
           AND NEW.score IS NOT DISTINCT FROM OLD.score
           AND NEW.input_snapshot_hash IS NOT DISTINCT FROM OLD.input_snapshot_hash
           AND NEW.solver_version IS NOT DISTINCT FROM OLD.solver_version
           AND NEW.random_seed IS NOT DISTINCT FROM OLD.random_seed
           AND NEW.published_at IS NOT DISTINCT FROM OLD.published_at
           AND NEW.legacy_identity_unverified = OLD.legacy_identity_unverified
           AND NEW.revision = OLD.revision + 1
           AND NEW.archived_at IS NOT NULL
        THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'VERSION_IMMUTABLE: published schedule version is immutable'
            USING ERRCODE = 'P0001';
    END IF;

    IF OLD.status <> 'PUBLISHED' AND NEW.status = 'PUBLISHED'
       AND NEW.legacy_identity_unverified
    THEN
        RAISE EXCEPTION 'LEGACY_IDENTITY_UNVERIFIED: schedule version identity is incomplete'
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER schedule_version_state_protection
BEFORE UPDATE ON schedule_version
FOR EACH ROW EXECUTE FUNCTION protect_schedule_version_state();

CREATE OR REPLACE FUNCTION protect_terminal_schedule_assignment()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    version_status VARCHAR(32);
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT status INTO version_status FROM schedule_version WHERE id = NEW.schedule_version_id FOR UPDATE;
        IF version_status IN ('PUBLISHED', 'ARCHIVED') THEN
            RAISE EXCEPTION 'VERSION_IMMUTABLE: terminal schedule assignment is immutable'
                USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;

    SELECT status INTO version_status FROM schedule_version WHERE id = OLD.schedule_version_id FOR UPDATE;
    IF version_status IN ('PUBLISHED', 'ARCHIVED') THEN
        RAISE EXCEPTION 'VERSION_IMMUTABLE: terminal schedule assignment is immutable'
            USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.schedule_version_id <> OLD.schedule_version_id THEN
        RAISE EXCEPTION 'VERSION_IMMUTABLE: assignment cannot move between versions'
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER schedule_assignment_terminal_protection
BEFORE INSERT OR UPDATE OR DELETE ON schedule_assignment
FOR EACH ROW EXECUTE FUNCTION protect_terminal_schedule_assignment();

CREATE OR REPLACE FUNCTION protect_terminal_command_history()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    version_status VARCHAR(32);
    target_version BIGINT;
BEGIN
    target_version := CASE WHEN TG_OP = 'DELETE' THEN OLD.schedule_version_id ELSE NEW.schedule_version_id END;
    SELECT status INTO version_status FROM schedule_version WHERE id = target_version FOR UPDATE;
    IF version_status IN ('PUBLISHED', 'ARCHIVED') THEN
        RAISE EXCEPTION 'VERSION_IMMUTABLE: terminal command history is immutable'
            USING ERRCODE = 'P0001';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE TRIGGER adjustment_command_terminal_protection
BEFORE INSERT OR UPDATE OR DELETE ON adjustment_command
FOR EACH ROW EXECUTE FUNCTION protect_terminal_command_history();

CREATE TRIGGER adjustment_command_group_terminal_protection
BEFORE INSERT OR UPDATE OR DELETE ON adjustment_command_group
FOR EACH ROW EXECUTE FUNCTION protect_terminal_command_history();

CREATE TRIGGER adjustment_command_event_terminal_protection
BEFORE INSERT OR UPDATE OR DELETE ON adjustment_command_event
FOR EACH ROW EXECUTE FUNCTION protect_terminal_command_history();
