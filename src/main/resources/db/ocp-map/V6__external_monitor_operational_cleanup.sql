UPDATE external_service
SET description = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE description = 'Migrado desde la prueba de concepto Node';

CREATE INDEX idx_external_run_scheduled_started
    ON external_run(started_at DESC)
    WHERE manual = FALSE;
