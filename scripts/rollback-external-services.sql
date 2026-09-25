-- Reversión deliberadamente separada de Flyway.
-- Requiere respaldo confirmado y que la aplicación 0.6.0 esté detenida o restaurada.
BEGIN;
DROP TABLE IF EXISTS external_incident;
DROP TABLE IF EXISTS external_probe_result;
DROP TABLE IF EXISTS external_run;
DROP TABLE IF EXISTS external_probe;
DROP TABLE IF EXISTS external_service;
DROP TABLE IF EXISTS external_credential;
DROP TABLE IF EXISTS external_group;
DELETE FROM flyway_schema_history WHERE version = '5';
COMMIT;
