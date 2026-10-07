-- Las vistas históricas filtran por finished_at; los índices anteriores estaban
-- orientados a started_at y no evitaban el recorrido completo del historial.
CREATE INDEX idx_external_run_scheduled_finished_service
    ON external_run(finished_at DESC, service_id)
    WHERE manual = FALSE AND status <> 'RUNNING';

-- Los reportes de downtime sin filtro de servicio recorren incidentes por rango.
CREATE INDEX idx_external_incident_confirmed_period
    ON external_incident(opened_at, recovered_at)
    WHERE confirmed_run_id IS NOT NULL;
