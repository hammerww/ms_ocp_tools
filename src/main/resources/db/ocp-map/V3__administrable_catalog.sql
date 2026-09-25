ALTER TABLE test_case
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN archived_at TIMESTAMPTZ;

ALTER TABLE test_case_deployment
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE catalog_element (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL UNIQUE,
    description TEXT,
    display_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_at TIMESTAMPTZ
);

INSERT INTO catalog_element(name, display_order)
SELECT name, MIN(display_order)
FROM test_case_element
GROUP BY name
ORDER BY MIN(display_order), name;

CREATE TABLE test_case_element_new (
    test_case_id BIGINT NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    catalog_element_id BIGINT NOT NULL REFERENCES catalog_element(id),
    display_order INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (test_case_id, catalog_element_id)
);

INSERT INTO test_case_element_new(test_case_id, catalog_element_id, display_order)
SELECT legacy.test_case_id, element.id, legacy.display_order
FROM test_case_element legacy
JOIN catalog_element element ON element.name = legacy.name;

DROP TABLE test_case_element;
ALTER TABLE test_case_element_new RENAME TO test_case_element;

CREATE TABLE test_case_metadata (
    id BIGSERIAL PRIMARY KEY,
    test_case_id BIGINT NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    value VARCHAR(200) NOT NULL,
    display_order INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (test_case_id, value)
);

INSERT INTO test_case_metadata(test_case_id, value, display_order)
SELECT tc.id, BTRIM(parts.value), parts.ordinality::INTEGER
FROM test_case tc
CROSS JOIN LATERAL regexp_split_to_table(COALESCE(tc.description, ''), E'\\s*·\\s*')
    WITH ORDINALITY AS parts(value, ordinality)
WHERE BTRIM(parts.value) <> ''
ON CONFLICT (test_case_id, value) DO NOTHING;

CREATE INDEX idx_test_case_archived ON test_case(archived_at);
CREATE INDEX idx_catalog_element_archived ON catalog_element(archived_at);
CREATE INDEX idx_test_case_element_catalog ON test_case_element(catalog_element_id);
