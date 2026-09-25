CREATE TABLE ocp_namespace (
    name VARCHAR(63) PRIMARY KEY,
    last_attempt_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_error TEXT
);

CREATE TABLE ocp_deployment (
    id BIGSERIAL PRIMARY KEY,
    namespace VARCHAR(63) NOT NULL REFERENCES ocp_namespace(name),
    name VARCHAR(253) NOT NULL,
    desired_replicas INTEGER,
    ready_replicas INTEGER,
    available_replicas INTEGER,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    first_seen_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ,
    missing_since TIMESTAMPTZ,
    UNIQUE (namespace, name)
);

CREATE TABLE test_case (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    description TEXT,
    display_order INTEGER NOT NULL
);

CREATE TABLE test_case_element (
    id BIGSERIAL PRIMARY KEY,
    test_case_id BIGINT NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    display_order INTEGER NOT NULL,
    UNIQUE (test_case_id, name)
);

CREATE TABLE test_case_deployment (
    test_case_id BIGINT NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    deployment_id BIGINT NOT NULL REFERENCES ocp_deployment(id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL,
    PRIMARY KEY (test_case_id, deployment_id)
);

CREATE TABLE testing_mark (
    deployment_id BIGINT PRIMARY KEY REFERENCES ocp_deployment(id) ON DELETE CASCADE,
    responsible VARCHAR(120) NOT NULL,
    note VARCHAR(500),
    marked_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ
);

CREATE TABLE testing_mark_history (
    id BIGSERIAL PRIMARY KEY,
    deployment_id BIGINT NOT NULL REFERENCES ocp_deployment(id) ON DELETE CASCADE,
    action VARCHAR(20) NOT NULL CHECK (action IN ('MARKED', 'REMOVED')),
    responsible VARCHAR(120) NOT NULL,
    note VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ
);

CREATE INDEX idx_ocp_deployment_active ON ocp_deployment(active);
CREATE INDEX idx_ocp_deployment_namespace ON ocp_deployment(namespace);
CREATE INDEX idx_testing_mark_history_deployment ON testing_mark_history(deployment_id, occurred_at DESC);

