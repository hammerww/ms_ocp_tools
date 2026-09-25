CREATE TABLE test_case_annotation (
    test_case_id BIGINT PRIMARY KEY REFERENCES test_case(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE test_case_flow (
    test_case_id BIGINT PRIMARY KEY REFERENCES test_case(id) ON DELETE CASCADE,
    schema_version INTEGER NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    definition JSONB NOT NULL CHECK (jsonb_typeof(definition) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
