-- Rollback de V8. Solo elimina índices de rendimiento; no borra datos.
DROP INDEX IF EXISTS idx_external_incident_confirmed_period;
DROP INDEX IF EXISTS idx_external_run_scheduled_finished_service;

-- Si se necesita que Flyway vuelva a aplicar V8, retirar después su fila:
-- DELETE FROM flyway_schema_history WHERE version = '8';
