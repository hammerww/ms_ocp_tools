CREATE TABLE external_availability_schedule (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT REFERENCES external_group(id),
    service_id BIGINT REFERENCES external_service(id),
    name VARCHAR(160) NOT NULL,
    timezone VARCHAR(80) NOT NULL DEFAULT 'America/Lima',
    working_days VARCHAR(40) NOT NULL DEFAULT '1,2,3,4,5',
    start_time TIME NOT NULL DEFAULT TIME '08:00:00',
    end_time TIME NOT NULL DEFAULT TIME '19:00:00',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_at TIMESTAMPTZ,
    CHECK ((group_id IS NOT NULL AND service_id IS NULL)
        OR (group_id IS NULL AND service_id IS NOT NULL)),
    CHECK (start_time < end_time)
);

CREATE UNIQUE INDEX uq_external_schedule_active_group
    ON external_availability_schedule(group_id)
    WHERE archived_at IS NULL AND group_id IS NOT NULL;
CREATE UNIQUE INDEX uq_external_schedule_active_service
    ON external_availability_schedule(service_id)
    WHERE archived_at IS NULL AND service_id IS NOT NULL;

CREATE TABLE external_schedule_exception (
    id BIGSERIAL PRIMARY KEY,
    schedule_id BIGINT NOT NULL REFERENCES external_availability_schedule(id) ON DELETE CASCADE,
    exception_date DATE NOT NULL,
    available BOOLEAN NOT NULL DEFAULT FALSE,
    start_time TIME,
    end_time TIME,
    description VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((available = FALSE AND start_time IS NULL AND end_time IS NULL)
        OR (available = TRUE AND start_time IS NOT NULL AND end_time IS NOT NULL AND start_time < end_time)),
    UNIQUE (schedule_id, exception_date)
);

CREATE TABLE external_incident_classification (
    id BIGSERIAL PRIMARY KEY,
    incident_id BIGINT NOT NULL REFERENCES external_incident(id) ON DELETE CASCADE,
    classification_type VARCHAR(30) NOT NULL
        CHECK (classification_type IN ('UNPLANNED', 'REQUESTED_RESTART', 'PLANNED_WORK')),
    from_at TIMESTAMPTZ NOT NULL,
    to_at TIMESTAMPTZ NOT NULL,
    ticket_reference VARCHAR(160),
    requested_by VARCHAR(160),
    notes TEXT,
    confirmed_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_at TIMESTAMPTZ,
    CHECK (from_at < to_at)
);

CREATE INDEX idx_external_incident_range
    ON external_incident(service_id, opened_at, recovered_at);
CREATE INDEX idx_external_classification_incident
    ON external_incident_classification(incident_id, from_at, to_at)
    WHERE archived_at IS NULL;
