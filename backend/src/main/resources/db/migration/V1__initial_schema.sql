CREATE TABLE academic_term (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    start_date DATE,
    end_date DATE,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE period_template (
    id BIGSERIAL PRIMARY KEY,
    term_id BIGINT NOT NULL REFERENCES academic_term(id),
    code VARCHAR(64) NOT NULL,
    weekday SMALLINT NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    period_no SMALLINT NOT NULL CHECK (period_no > 0),
    label VARCHAR(128) NOT NULL,
    start_time TIME,
    end_time TIME,
    UNIQUE (term_id, code),
    UNIQUE (term_id, weekday, period_no)
);

CREATE TABLE teacher (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE student_group (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    group_type VARCHAR(32) NOT NULL DEFAULT 'HOMEROOM',
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE subject (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE room (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    capacity INTEGER NOT NULL CHECK (capacity > 0),
    room_type VARCHAR(64),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE room_feature (
    id BIGSERIAL PRIMARY KEY,
    room_id BIGINT NOT NULL REFERENCES room(id) ON DELETE CASCADE,
    feature_code VARCHAR(64) NOT NULL,
    UNIQUE (room_id, feature_code)
);

CREATE TABLE teacher_availability (
    id BIGSERIAL PRIMARY KEY,
    teacher_id BIGINT NOT NULL REFERENCES teacher(id) ON DELETE CASCADE,
    period_code VARCHAR(64) NOT NULL,
    available BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (teacher_id, period_code)
);

CREATE TABLE room_availability (
    id BIGSERIAL PRIMARY KEY,
    room_id BIGINT NOT NULL REFERENCES room(id) ON DELETE CASCADE,
    period_code VARCHAR(64) NOT NULL,
    available BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (room_id, period_code)
);

CREATE TABLE teaching_requirement (
    id BIGSERIAL PRIMARY KEY,
    term_id BIGINT NOT NULL REFERENCES academic_term(id),
    student_group_id BIGINT NOT NULL REFERENCES student_group(id),
    subject_id BIGINT NOT NULL REFERENCES subject(id),
    teacher_id BIGINT NOT NULL REFERENCES teacher(id),
    weekly_periods INTEGER NOT NULL CHECK (weekly_periods > 0),
    duration_periods INTEGER NOT NULL DEFAULT 1 CHECK (duration_periods > 0),
    pinned_period_code VARCHAR(64),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE schedule_scenario (
    id BIGSERIAL PRIMARY KEY,
    term_id BIGINT NOT NULL REFERENCES academic_term(id),
    name VARCHAR(128) NOT NULL,
    parent_version_id BIGINT,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE schedule_version (
    id BIGSERIAL PRIMARY KEY,
    scenario_id BIGINT NOT NULL REFERENCES schedule_scenario(id),
    parent_version_id BIGINT REFERENCES schedule_version(id),
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    score VARCHAR(128),
    input_snapshot_hash VARCHAR(128),
    solver_version VARCHAR(128),
    random_seed BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ
);

CREATE TABLE solve_job (
    id BIGSERIAL PRIMARY KEY,
    schedule_version_id BIGINT NOT NULL REFERENCES schedule_version(id),
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    progress INTEGER NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    error_code VARCHAR(64),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);

CREATE TABLE audit_event (
    id BIGSERIAL PRIMARY KEY,
    action VARCHAR(128) NOT NULL,
    aggregate_type VARCHAR(128) NOT NULL,
    aggregate_id VARCHAR(128),
    actor VARCHAR(128) NOT NULL DEFAULT 'system',
    detail JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
