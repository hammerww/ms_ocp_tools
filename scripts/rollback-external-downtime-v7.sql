-- Reversión física opcional de V7. No forma parte del rollback normal.
-- Requisitos: pg_dump validado y aplicación 0.7.3 detenida/restaurada.
-- Destruye horarios, excepciones y clasificaciones; conserva servicios,
-- ejecuciones, resultados e incidentes de V5/V6.
BEGIN;
DROP TABLE IF EXISTS external_incident_classification;
DROP TABLE IF EXISTS external_schedule_exception;
DROP TABLE IF EXISTS external_availability_schedule;
DELETE FROM flyway_schema_history WHERE version = '7';
COMMIT;
