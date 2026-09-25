CREATE TABLE external_group (
    id BIGSERIAL PRIMARY KEY,
    parent_id BIGINT REFERENCES external_group(id),
    name VARCHAR(120) NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_at TIMESTAMPTZ
);

CREATE TABLE external_credential (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    environment VARCHAR(80) NOT NULL,
    credential_type VARCHAR(80) NOT NULL DEFAULT 'GENERAL',
    system_name VARCHAR(160) NOT NULL,
    encrypted_payload BYTEA NOT NULL,
    iv BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_at TIMESTAMPTZ
);

CREATE TABLE external_service (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT REFERENCES external_group(id),
    name VARCHAR(200) NOT NULL,
    environment VARCHAR(80) NOT NULL,
    system_name VARCHAR(160) NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (status IN ('UP', 'WARNING', 'DOWN', 'UNKNOWN')),
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    last_scheduled_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_at TIMESTAMPTZ
);

CREATE TABLE external_probe (
    id BIGSERIAL PRIMARY KEY,
    service_id BIGINT NOT NULL REFERENCES external_service(id) ON DELETE CASCADE,
    depends_on_probe_id BIGINT REFERENCES external_probe(id),
    credential_id BIGINT REFERENCES external_credential(id),
    name VARCHAR(180) NOT NULL,
    probe_type VARCHAR(20) NOT NULL
        CHECK (probe_type IN ('TCP', 'HTTP', 'TOKEN_HTTP', 'DATABASE')),
    mandatory BOOLEAN NOT NULL DEFAULT TRUE,
    display_order INTEGER NOT NULL DEFAULT 0,
    host VARCHAR(253),
    port INTEGER CHECK (port IS NULL OR port BETWEEN 1 AND 65535),
    url TEXT,
    http_method VARCHAR(10),
    request_headers TEXT,
    request_body TEXT,
    expected_statuses VARCHAR(200),
    expected_body TEXT,
    token_json_field VARCHAR(240),
    auth_type VARCHAR(30) NOT NULL DEFAULT 'NONE'
        CHECK (auth_type IN ('NONE', 'BASIC', 'BEARER', 'API_KEY', 'OAUTH_CLIENT')),
    auth_header VARCHAR(120),
    db_engine VARCHAR(20)
        CHECK (db_engine IS NULL OR db_engine IN ('ORACLE', 'POSTGRESQL', 'SQLSERVER', 'MYSQL')),
    db_name VARCHAR(160),
    db_service VARCHAR(160),
    validation_query TEXT,
    timeout_ms INTEGER NOT NULL DEFAULT 10000 CHECK (timeout_ms BETWEEN 250 AND 120000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_at TIMESTAMPTZ,
    UNIQUE (service_id, name)
);

CREATE TABLE external_run (
    id BIGSERIAL PRIMARY KEY,
    service_id BIGINT NOT NULL REFERENCES external_service(id) ON DELETE CASCADE,
    manual BOOLEAN NOT NULL DEFAULT FALSE,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'UP', 'WARNING', 'DOWN', 'ERROR')),
    duration_ms BIGINT,
    trigger_source VARCHAR(40) NOT NULL
);

CREATE TABLE external_probe_result (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES external_run(id) ON DELETE CASCADE,
    probe_id BIGINT REFERENCES external_probe(id) ON DELETE SET NULL,
    probe_name VARCHAR(180) NOT NULL,
    probe_type VARCHAR(20) NOT NULL,
    mandatory BOOLEAN NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('UP', 'WARNING', 'DOWN', 'SKIPPED')),
    phase VARCHAR(40),
    message TEXT,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    response_code INTEGER,
    checked_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE external_incident (
    id BIGSERIAL PRIMARY KEY,
    service_id BIGINT NOT NULL REFERENCES external_service(id) ON DELETE CASCADE,
    opened_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    recovered_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'OPEN', 'RECOVERED')),
    first_failure_run_id BIGINT REFERENCES external_run(id),
    confirmed_run_id BIGINT REFERENCES external_run(id),
    recovery_run_id BIGINT REFERENCES external_run(id)
);

CREATE INDEX idx_external_service_active_status ON external_service(status) WHERE archived_at IS NULL;
CREATE UNIQUE INDEX uq_external_group_active_parent_name
    ON external_group(COALESCE(parent_id, 0), lower(name)) WHERE archived_at IS NULL;
CREATE UNIQUE INDEX uq_external_credential_active_identity
    ON external_credential(lower(name), lower(environment), lower(credential_type), lower(system_name))
    WHERE archived_at IS NULL;
CREATE UNIQUE INDEX uq_external_service_active_name_environment
    ON external_service(lower(name), lower(environment)) WHERE archived_at IS NULL;
CREATE INDEX idx_external_probe_service ON external_probe(service_id, display_order) WHERE archived_at IS NULL;
CREATE INDEX idx_external_run_service_started ON external_run(service_id, started_at DESC);
CREATE INDEX idx_external_probe_result_run ON external_probe_result(run_id);
CREATE INDEX idx_external_incident_service_status ON external_incident(service_id, status);
