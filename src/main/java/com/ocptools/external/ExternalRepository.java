package com.ocptools.external;

import com.ocptools.config.ToolsConfig;
import com.ocptools.external.ExternalModels.CredentialInput;
import com.ocptools.external.ExternalModels.CredentialSecret;
import com.ocptools.external.ExternalModels.CredentialView;
import com.ocptools.external.ExternalModels.GroupView;
import com.ocptools.external.ExternalModels.HistoryPointView;
import com.ocptools.external.ExternalModels.HistoryExecutionView;
import com.ocptools.external.ExternalModels.HistorySummary;
import com.ocptools.external.ExternalModels.HistoryView;
import com.ocptools.external.ExternalModels.MonitorSettings;
import com.ocptools.external.ExternalModels.ProbeInput;
import com.ocptools.external.ExternalModels.ProbeResultView;
import com.ocptools.external.ExternalModels.ProbeView;
import com.ocptools.external.ExternalModels.RunView;
import com.ocptools.external.ExternalModels.ServiceInput;
import com.ocptools.external.ExternalModels.ServiceHistoryView;
import com.ocptools.external.ExternalModels.ServiceView;
import com.ocptools.external.ExternalModels.Snapshot;
import com.ocptools.external.ExternalModels.Summary;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ExternalRepository {
    @Inject
    @DataSource("inventory")
    javax.sql.DataSource dataSource;

    @Inject
    CredentialCipher cipher;

    @Inject
    ExternalValidator validator;

    @Inject
    ToolsConfig config;

    public Snapshot snapshot() {
        try (Connection connection = dataSource.getConnection()) {
            Map<Long, ServiceBuilder> services = loadServices(connection);
            loadProbes(connection, services);
            loadLatestRuns(connection, services);
            var monitor = config.externalMonitor();
            return new Snapshot(Instant.now(), loadSummary(connection),
                    services.values().stream().map(ServiceBuilder::build).toList(), loadGroups(connection),
                    new MonitorSettings(monitor.enabled(), monitor.tlsVerify(), monitor.interval().toSeconds(),
                            monitor.initialDelay().toSeconds(), monitor.parallelism()));
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public HistoryView history(ExternalHistoryRange range, Long serviceId) {
        try (Connection connection = dataSource.getConnection()) {
            return new HistoryView(Instant.now(), range.hours(), range.from(), range.to(), range.bucketSeconds(),
                    serviceId, loadHistorySummary(connection, range.from(), range.to(), serviceId),
                    loadServiceHistory(connection, range.from(), range.to(), serviceId),
                    loadHistoryTimeline(connection, range.from(), range.to(), serviceId, range.sqlBucket()));
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public List<HistoryExecutionView> historyExecutions(ExternalHistoryRange range, Long serviceId) {
        String servicePredicate = serviceId == null ? "" : " AND s.id=?";
        String sql = """
                SELECT r.id, s.id service_id, s.name service_name, s.environment, s.system_name,
                       r.started_at, r.finished_at, r.status, r.duration_ms, r.trigger_source
                FROM external_run r
                JOIN external_service s ON s.id=r.service_id
                WHERE r.manual=FALSE AND r.status <> 'RUNNING'
                  AND r.finished_at >= ? AND r.finished_at < ? AND s.archived_at IS NULL
                """ + servicePredicate + " ORDER BY r.started_at, s.name";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(range.from()));
            statement.setTimestamp(2, Timestamp.from(range.to()));
            if (serviceId != null) statement.setLong(3, serviceId);
            try (ResultSet rows = statement.executeQuery()) {
                List<HistoryExecutionView> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new HistoryExecutionView(rows.getLong("id"), rows.getLong("service_id"),
                            rows.getString("service_name"), rows.getString("environment"),
                            rows.getString("system_name"), instant(rows, "started_at"),
                            instant(rows, "finished_at"), rows.getString("status"),
                            nullableLong(rows, "duration_ms"), rows.getString("trigger_source")));
                }
                return result;
            }
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public List<CredentialView> credentials() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, name, environment, credential_type, system_name, encrypted_payload, iv
                     FROM external_credential WHERE archived_at IS NULL
                     ORDER BY environment, system_name, name
                     """); ResultSet rows = statement.executeQuery()) {
            List<CredentialView> result = new ArrayList<>();
            while (rows.next()) {
                CredentialSecret secret = cipher.decrypt(rows.getBytes("encrypted_payload"), rows.getBytes("iv"));
                result.add(new CredentialView(rows.getLong("id"), rows.getString("name"),
                        rows.getString("environment"), rows.getString("credential_type"),
                        rows.getString("system_name"), secret.username(),
                        present(secret.password()), present(secret.token()), present(secret.clientSecret()),
                        secret.extra() == null ? Map.of() : secret.extra()));
            }
            return result;
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public List<CredentialExport> credentialExports() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, name, environment, credential_type, system_name, encrypted_payload, iv
                     FROM external_credential WHERE archived_at IS NULL
                     ORDER BY environment, system_name, name
                     """); ResultSet rows = statement.executeQuery()) {
            List<CredentialExport> result = new ArrayList<>();
            while (rows.next()) {
                result.add(new CredentialExport(rows.getLong("id"), rows.getString("name"),
                        rows.getString("environment"), rows.getString("credential_type"), rows.getString("system_name"),
                        cipher.decrypt(rows.getBytes("encrypted_payload"), rows.getBytes("iv"))));
            }
            return result;
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public long saveCredential(Long id, CredentialInput input) {
        validator.credential(input);
        try (Connection connection = dataSource.getConnection()) {
            CredentialSecret previous = id == null ? null : credentialSecret(connection, id);
            CredentialSecret secret = new CredentialSecret(
                    valueOrPrevious(input.username(), previous == null ? null : previous.username()),
                    valueOrPrevious(input.password(), previous == null ? null : previous.password()),
                    valueOrPrevious(input.token(), previous == null ? null : previous.token()),
                    valueOrPrevious(input.clientId(), previous == null ? null : previous.clientId()),
                    valueOrPrevious(input.clientSecret(), previous == null ? null : previous.clientSecret()),
                    input.extra() == null || input.extra().isEmpty()
                            ? (previous == null || previous.extra() == null ? Map.of() : previous.extra()) : input.extra());
            CredentialCipher.Encrypted encrypted = cipher.encrypt(secret);
            if (id == null) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO external_credential(name, environment, credential_type, system_name, encrypted_payload, iv)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    bindCredential(statement, input, encrypted, false);
                    statement.executeUpdate();
                    return generatedId(statement);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE external_credential SET name=?, environment=?, credential_type=?, system_name=?, encrypted_payload=?, iv=?,
                        updated_at=CURRENT_TIMESTAMP WHERE id=? AND archived_at IS NULL
                    """)) {
                bindCredential(statement, input, encrypted, true);
                statement.setLong(7, id);
                if (statement.executeUpdate() == 0) throw new IllegalArgumentException("Credencial no encontrada");
                return id;
            }
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public void archiveCredential(long id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM external_probe p JOIN external_service s ON s.id=p.service_id
                     WHERE p.credential_id=? AND p.archived_at IS NULL AND s.archived_at IS NULL
                     """)) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                if (rows.getLong(1) > 0) throw new IllegalArgumentException("La credencial está asociada a un servicio activo");
            }
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
        executeArchive("external_credential", id);
    }

    public long createGroup(ExternalModels.GroupInput input) {
        if (input == null || input.name() == null || input.name().isBlank()) {
            throw new IllegalArgumentException("Nombre de grupo requerido");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO external_group(parent_id, name, display_order) VALUES (?, ?, ?)
                     """, Statement.RETURN_GENERATED_KEYS)) {
            setLong(statement, 1, input.parentId());
            statement.setString(2, input.name().trim());
            statement.setInt(3, input.displayOrder() == null ? 0 : input.displayOrder());
            statement.executeUpdate();
            return generatedId(statement);
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public long saveService(Long id, ServiceInput input) {
        validator.service(input);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long serviceId = id == null ? insertService(connection, input) : updateService(connection, id, input);
                replaceProbes(connection, serviceId, input.probes());
                connection.commit();
                return serviceId;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public long cloneService(long sourceId, String name) {
        ServiceDefinition source = serviceDefinition(sourceId);
        List<ProbeInput> probes = source.probes().stream().map(probe -> new ProbeInput(
                probe.id(), probe.dependsOnProbeId(), probe.credentialId(), probe.name(), probe.probeType(),
                probe.mandatory(), probe.displayOrder(), probe.host(), probe.port(), probe.url(), probe.httpMethod(),
                probe.requestHeaders(), probe.requestBody(), probe.expectedStatuses(), probe.expectedBody(),
                probe.tokenJsonField(), probe.authType(), probe.authHeader(), probe.dbEngine(), probe.dbName(),
                probe.dbService(), probe.validationQuery(), probe.timeoutMs())).toList();
        return saveService(null, new ServiceInput(name, source.environment(), source.systemName(),
                source.description(), source.groupId(), probes));
    }

    public void archiveService(long id) {
        executeArchive("external_service", id);
    }

    public List<ServiceDefinition> activeServiceDefinitions() {
        try (Connection connection = dataSource.getConnection()) {
            List<ServiceDefinition> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id FROM external_service WHERE archived_at IS NULL ORDER BY id
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(serviceDefinition(connection, rows.getLong(1)));
            }
            return result;
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public ServiceDefinition serviceDefinition(long id) {
        try (Connection connection = dataSource.getConnection()) {
            return serviceDefinition(connection, id);
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public CredentialSecret credentialSecret(Long id) {
        if (id == null) return null;
        try (Connection connection = dataSource.getConnection()) {
            return credentialSecret(connection, id);
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public long startRun(long serviceId, boolean manual, Instant startedAt, String source) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO external_run(service_id, manual, started_at, status, trigger_source)
                     VALUES (?, ?, ?, 'RUNNING', ?)
                     """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, serviceId);
            statement.setBoolean(2, manual);
            statement.setTimestamp(3, Timestamp.from(startedAt));
            statement.setString(4, source);
            statement.executeUpdate();
            return generatedId(statement);
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public RunView finishRun(long runId, long serviceId, boolean manual, Instant startedAt,
                             String runStatus, List<ProbeResultView> results) {
        Instant finishedAt = Instant.now();
        long duration = Math.max(0, finishedAt.toEpochMilli() - startedAt.toEpochMilli());
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE external_run SET finished_at=?, status=?, duration_ms=? WHERE id=?
                        """)) {
                    statement.setTimestamp(1, Timestamp.from(finishedAt));
                    statement.setString(2, runStatus);
                    statement.setLong(3, duration);
                    statement.setLong(4, runId);
                    statement.executeUpdate();
                }
                for (ProbeResultView result : results) insertResult(connection, runId, result);
                if (!manual) applyScheduledState(connection, serviceId, runId, runStatus, finishedAt);
                connection.commit();
                return new RunView(runId, manual, startedAt, finishedAt, runStatus, duration, results);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public int importHistory(List<ExternalModels.HistoricalRunInput> history) {
        if (history == null || history.isEmpty()) return 0;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int imported = 0;
            try {
                for (ExternalModels.HistoricalRunInput item : history) {
                    Long serviceId = findService(connection, item.serviceName(), item.environment());
                    if (serviceId == null || item.startedAt() == null) continue;
                    long runId;
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO external_run(service_id, manual, started_at, finished_at, status,
                                duration_ms, trigger_source)
                            SELECT ?, FALSE, ?, ?, ?, ?, 'POC_IMPORT'
                            WHERE NOT EXISTS (SELECT 1 FROM external_run
                              WHERE service_id=? AND started_at=? AND trigger_source='POC_IMPORT')
                            """, Statement.RETURN_GENERATED_KEYS)) {
                        String status = normalizeRunStatus(item.status());
                        long duration = item.durationMs() == null ? 0 : Math.max(0, item.durationMs());
                        statement.setLong(1, serviceId);
                        statement.setTimestamp(2, Timestamp.from(item.startedAt()));
                        statement.setTimestamp(3, Timestamp.from(item.startedAt().plusMillis(duration)));
                        statement.setString(4, status);
                        statement.setLong(5, duration);
                        statement.setLong(6, serviceId);
                        statement.setTimestamp(7, Timestamp.from(item.startedAt()));
                        if (statement.executeUpdate() == 0) continue;
                        runId = generatedId(statement);
                    }
                    for (ExternalModels.HistoricalResultInput result : item.results() == null ? List.<ExternalModels.HistoricalResultInput>of() : item.results()) {
                        Long probeId = findProbe(connection, serviceId, result.probeName());
                        insertResult(connection, runId, new ProbeResultView(probeId,
                                result.probeName() == null ? "Prueba importada" : result.probeName(),
                                result.probeType() == null ? "HTTP" : result.probeType(), result.mandatory(),
                                normalizeResultStatus(result.status()), result.phase(), result.message(),
                                Math.max(0, result.durationMs()), result.responseCode(), item.startedAt()));
                    }
                    imported++;
                }
                connection.commit();
                return imported;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    private static Map<Long, ServiceBuilder> loadServices(Connection connection) throws SQLException {
        Map<Long, ServiceBuilder> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, group_id, name, environment, system_name, description, status,
                       consecutive_failures, last_scheduled_at, last_success_at
                FROM external_service WHERE archived_at IS NULL
                ORDER BY environment, system_name, name
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                long id = rows.getLong("id");
                result.put(id, new ServiceBuilder(id, nullableLong(rows, "group_id"), rows.getString("name"),
                        rows.getString("environment"), rows.getString("system_name"), rows.getString("description"),
                        rows.getString("status"), rows.getInt("consecutive_failures"),
                        instant(rows, "last_scheduled_at"), instant(rows, "last_success_at")));
            }
        }
        return result;
    }

    private static void loadProbes(Connection connection, Map<Long, ServiceBuilder> services) throws SQLException {
        Map<Long, ProbeResultView> latest = latestProbeResults(connection);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT p.*, c.name AS credential_name
                FROM external_probe p LEFT JOIN external_credential c ON c.id=p.credential_id
                WHERE p.archived_at IS NULL ORDER BY p.service_id, p.display_order, p.id
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                ServiceBuilder service = services.get(rows.getLong("service_id"));
                if (service == null) continue;
                long id = rows.getLong("id");
                service.probes.add(new ProbeView(id, rows.getString("name"), rows.getString("probe_type"),
                        rows.getBoolean("mandatory"), rows.getInt("display_order"),
                        nullableLong(rows, "depends_on_probe_id"), nullableLong(rows, "credential_id"),
                        rows.getString("credential_name"), rows.getString("host"), nullableInt(rows, "port"),
                        rows.getString("url"), rows.getString("http_method"), rows.getString("request_headers"),
                        rows.getString("request_body"), rows.getString("expected_statuses"),
                        rows.getString("expected_body"), rows.getString("token_json_field"),
                        rows.getString("auth_type"), rows.getString("auth_header"), rows.getString("db_engine"),
                        rows.getString("db_name"), rows.getString("db_service"), rows.getString("validation_query"),
                        rows.getInt("timeout_ms"), latest.get(id)));
            }
        }
    }

    private static Map<Long, ProbeResultView> latestProbeResults(Connection connection) throws SQLException {
        Map<Long, ProbeResultView> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DISTINCT ON (r.probe_id) r.probe_id, r.probe_name, r.probe_type, r.mandatory,
                       r.status, r.phase, r.message, r.duration_ms, r.response_code, r.checked_at
                FROM external_probe_result r JOIN external_run run ON run.id=r.run_id
                WHERE r.probe_id IS NOT NULL AND run.manual=FALSE
                ORDER BY r.probe_id, r.checked_at DESC
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                long id = rows.getLong("probe_id");
                result.put(id, resultView(rows));
            }
        }
        return result;
    }

    private static void loadLatestRuns(Connection connection, Map<Long, ServiceBuilder> services) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DISTINCT ON (service_id) id, service_id, manual, started_at, finished_at, status, duration_ms
                FROM external_run WHERE status <> 'RUNNING' ORDER BY service_id, started_at DESC
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                ServiceBuilder service = services.get(rows.getLong("service_id"));
                if (service == null) continue;
                long runId = rows.getLong("id");
                service.lastRun = new RunView(runId, rows.getBoolean("manual"), instant(rows, "started_at"),
                        instant(rows, "finished_at"), rows.getString("status"), nullableLong(rows, "duration_ms"),
                        loadResults(connection, runId));
            }
        }
    }

    private static List<ProbeResultView> loadResults(Connection connection, long runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT probe_id, probe_name, probe_type, mandatory, status, phase, message,
                       duration_ms, response_code, checked_at
                FROM external_probe_result WHERE run_id=? ORDER BY id
                """)) {
            statement.setLong(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                List<ProbeResultView> result = new ArrayList<>();
                while (rows.next()) result.add(resultView(rows));
                return result;
            }
        }
    }

    private static ProbeResultView resultView(ResultSet rows) throws SQLException {
        return new ProbeResultView(nullableLong(rows, "probe_id"), rows.getString("probe_name"),
                rows.getString("probe_type"), rows.getBoolean("mandatory"), rows.getString("status"),
                rows.getString("phase"), rows.getString("message"), rows.getLong("duration_ms"),
                nullableInt(rows, "response_code"), instant(rows, "checked_at"));
    }

    private static Summary loadSummary(Connection connection) throws SQLException {
        long total = 0, up = 0, warning = 0, down = 0, unknown = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) total,
                       COUNT(*) FILTER (WHERE status='UP') up,
                       COUNT(*) FILTER (WHERE status='WARNING') warning,
                       COUNT(*) FILTER (WHERE status='DOWN') down,
                       COUNT(*) FILTER (WHERE status='UNKNOWN') unknown
                FROM external_service WHERE archived_at IS NULL
                """); ResultSet rows = statement.executeQuery()) {
            if (rows.next()) {
                total = rows.getLong("total"); up = rows.getLong("up"); warning = rows.getLong("warning");
                down = rows.getLong("down"); unknown = rows.getLong("unknown");
            }
        }
        Double availability24h = availability(connection, 1);
        Double availability7d = availability(connection, 7);
        Long p95 = null;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) p95
                FROM external_run WHERE manual=FALSE AND finished_at >= ? AND duration_ms IS NOT NULL
                """)) {
            statement.setTimestamp(1, Timestamp.from(Instant.now().minus(7, ChronoUnit.DAYS)));
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next() && rows.getObject("p95") != null) p95 = Math.round(rows.getDouble("p95"));
            }
        }
        long incidents;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM external_incident WHERE status IN ('PENDING','OPEN')");
             ResultSet rows = statement.executeQuery()) {
            rows.next(); incidents = rows.getLong(1);
        }
        return new Summary(total, up, warning, down, unknown, availability24h, availability7d, p95, incidents);
    }

    private static HistorySummary loadHistorySummary(Connection connection, Instant from, Instant to, Long serviceId)
            throws SQLException {
        String servicePredicate = serviceId == null ? "" : " AND s.id=?";
        String sql = """
                SELECT COUNT(*) executions,
                       100.0 * COUNT(*) FILTER (WHERE r.status IN ('UP','WARNING'))
                           / NULLIF(COUNT(*),0) availability,
                       AVG(r.duration_ms) average_duration,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY r.duration_ms) p95_duration,
                       COUNT(*) FILTER (WHERE r.status='WARNING') warnings,
                       COUNT(*) FILTER (WHERE r.status IN ('DOWN','ERROR')) downs
                FROM external_run r
                JOIN external_service s ON s.id=r.service_id
                WHERE r.manual=FALSE AND r.status <> 'RUNNING' AND r.finished_at >= ? AND r.finished_at < ?
                  AND s.archived_at IS NULL
                """ + servicePredicate;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(from));
            statement.setTimestamp(2, Timestamp.from(to));
            if (serviceId != null) statement.setLong(3, serviceId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return new HistorySummary(rows.getLong("executions"), nullableDouble(rows, "availability"),
                        nullableRoundedLong(rows, "average_duration"), nullableRoundedLong(rows, "p95_duration"),
                        rows.getLong("warnings"), rows.getLong("downs"));
            }
        }
    }

    private static List<ServiceHistoryView> loadServiceHistory(Connection connection, Instant from, Instant to,
                                                                Long serviceId)
            throws SQLException {
        String servicePredicate = serviceId == null ? "" : " AND s.id=?";
        String sql = """
                SELECT s.id, s.name, s.environment, s.system_name,
                       COUNT(*) executions,
                       100.0 * COUNT(*) FILTER (WHERE r.status IN ('UP','WARNING'))
                           / NULLIF(COUNT(*),0) availability,
                       AVG(r.duration_ms) average_duration,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY r.duration_ms) p95_duration,
                       COUNT(*) FILTER (WHERE r.status='WARNING') warnings,
                       COUNT(*) FILTER (WHERE r.status IN ('DOWN','ERROR')) downs,
                       (array_agg(r.status ORDER BY r.started_at DESC))[1] last_status,
                       MAX(r.started_at) last_checked_at
                FROM external_run r
                JOIN external_service s ON s.id=r.service_id
                WHERE r.manual=FALSE AND r.status <> 'RUNNING' AND r.finished_at >= ? AND r.finished_at < ?
                  AND s.archived_at IS NULL
                """ + servicePredicate + """
                GROUP BY s.id, s.name, s.environment, s.system_name
                ORDER BY downs DESC, warnings DESC, s.name
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(from));
            statement.setTimestamp(2, Timestamp.from(to));
            if (serviceId != null) statement.setLong(3, serviceId);
            try (ResultSet rows = statement.executeQuery()) {
                List<ServiceHistoryView> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new ServiceHistoryView(rows.getLong("id"), rows.getString("name"),
                            rows.getString("environment"), rows.getString("system_name"),
                            rows.getLong("executions"), nullableDouble(rows, "availability"),
                            nullableRoundedLong(rows, "average_duration"),
                            nullableRoundedLong(rows, "p95_duration"), rows.getLong("warnings"),
                            rows.getLong("downs"), rows.getString("last_status"),
                            instant(rows, "last_checked_at")));
                }
                return result;
            }
        }
    }

    private static List<HistoryPointView> loadHistoryTimeline(Connection connection, Instant from, Instant to,
                                                               Long serviceId, String bucket) throws SQLException {
        String servicePredicate = serviceId == null ? "" : " AND s.id=?";
        String sql = """
                SELECT s.id, s.name,
                       date_bin(INTERVAL '%s', r.started_at,
                           TIMESTAMPTZ '2001-01-01 00:00:00+00') bucket,
                       CASE
                           WHEN BOOL_OR(r.status IN ('DOWN','ERROR')) THEN 'DOWN'
                           WHEN BOOL_OR(r.status='WARNING') THEN 'WARNING'
                           ELSE 'UP'
                       END status,
                       AVG(r.duration_ms) average_duration
                FROM external_run r
                JOIN external_service s ON s.id=r.service_id
                WHERE r.manual=FALSE AND r.status <> 'RUNNING' AND r.finished_at >= ? AND r.finished_at < ?
                  AND s.archived_at IS NULL
                %s
                GROUP BY s.id, s.name, bucket
                ORDER BY bucket, s.name
                """.formatted(bucket, servicePredicate);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(from));
            statement.setTimestamp(2, Timestamp.from(to));
            if (serviceId != null) statement.setLong(3, serviceId);
            try (ResultSet rows = statement.executeQuery()) {
                List<HistoryPointView> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new HistoryPointView(rows.getLong("id"), rows.getString("name"),
                            instant(rows, "bucket"), rows.getString("status"),
                            nullableRoundedLong(rows, "average_duration")));
                }
                return result;
            }
        }
    }

    private static Double availability(Connection connection, int days) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 100.0 * COUNT(*) FILTER (WHERE status IN ('UP','WARNING')) / NULLIF(COUNT(*),0) value
                FROM external_run WHERE manual=FALSE AND finished_at >= ?
                """)) {
            statement.setTimestamp(1, Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS)));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getObject("value") == null) return null;
                return Math.round(rows.getDouble("value") * 100.0) / 100.0;
            }
        }
    }

    private static Double nullableDouble(ResultSet rows, String column) throws SQLException {
        Object value = rows.getObject(column);
        return value == null ? null : Math.round(rows.getDouble(column) * 100.0) / 100.0;
    }

    private static Long nullableRoundedLong(ResultSet rows, String column) throws SQLException {
        Object value = rows.getObject(column);
        return value == null ? null : Math.round(rows.getDouble(column));
    }

    private static List<GroupView> loadGroups(Connection connection) throws SQLException {
        List<GroupView> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, parent_id, name, display_order FROM external_group
                WHERE archived_at IS NULL ORDER BY display_order, name
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(new GroupView(rows.getLong("id"), nullableLong(rows, "parent_id"),
                    rows.getString("name"), rows.getInt("display_order")));
        }
        return result;
    }

    private static long insertService(Connection connection, ServiceInput input) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO external_service(group_id, name, environment, system_name, description)
                VALUES (?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            bindService(statement, input);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private static long updateService(Connection connection, long id, ServiceInput input) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE external_service SET group_id=?, name=?, environment=?, system_name=?, description=?,
                    updated_at=CURRENT_TIMESTAMP WHERE id=? AND archived_at IS NULL
                """)) {
            bindService(statement, input);
            statement.setLong(6, id);
            if (statement.executeUpdate() == 0) throw new IllegalArgumentException("Servicio no encontrado");
            return id;
        }
    }

    private static void bindService(PreparedStatement statement, ServiceInput input) throws SQLException {
        setLong(statement, 1, input.groupId());
        statement.setString(2, input.name().trim());
        statement.setString(3, input.environment().trim());
        statement.setString(4, input.systemName().trim());
        statement.setString(5, blankToNull(input.description()));
    }

    private static void replaceProbes(Connection connection, long serviceId, List<ProbeInput> probes)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM external_probe WHERE service_id=?")) {
            statement.setLong(1, serviceId);
            statement.executeUpdate();
        }
        Map<Long, Long> ids = new HashMap<>();
        List<Long> insertedIds = new ArrayList<>();
        for (int index = 0; index < probes.size(); index++) {
            ProbeInput probe = probes.get(index);
            long id;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO external_probe(service_id, credential_id, name, probe_type, mandatory, display_order,
                        host, port, url, http_method, request_headers, request_body, expected_statuses, expected_body,
                        token_json_field, auth_type, auth_header, db_engine, db_name, db_service, validation_query, timeout_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, serviceId);
                setLong(statement, 2, probe.credentialId());
                statement.setString(3, probe.name().trim());
                statement.setString(4, ExternalValidator.upper(probe.probeType()));
                statement.setBoolean(5, probe.mandatory() == null || probe.mandatory());
                statement.setInt(6, probe.displayOrder() == null ? index : probe.displayOrder());
                statement.setString(7, blankToNull(probe.host()));
                setInt(statement, 8, probe.port());
                statement.setString(9, blankToNull(probe.url()));
                statement.setString(10, blankToNull(probe.httpMethod()) == null ? null : ExternalValidator.upper(probe.httpMethod()));
                statement.setString(11, blankToNull(probe.requestHeaders()));
                statement.setString(12, blankToNull(probe.requestBody()));
                statement.setString(13, blankToNull(probe.expectedStatuses()));
                statement.setString(14, blankToNull(probe.expectedBody()));
                statement.setString(15, blankToNull(probe.tokenJsonField()));
                statement.setString(16, ExternalValidator.upper(ExternalValidator.defaultValue(probe.authType(), "NONE")));
                statement.setString(17, blankToNull(probe.authHeader()));
                statement.setString(18, blankToNull(probe.dbEngine()) == null ? null : ExternalValidator.upper(probe.dbEngine()));
                statement.setString(19, blankToNull(probe.dbName()));
                statement.setString(20, blankToNull(probe.dbService()));
                statement.setString(21, blankToNull(probe.validationQuery()));
                statement.setInt(22, probe.timeoutMs() == null ? 10000 : probe.timeoutMs());
                statement.executeUpdate();
                id = generatedId(statement);
            }
            insertedIds.add(id);
            if (probe.clientId() != null) ids.put(probe.clientId(), id);
        }
        for (int index = 0; index < probes.size(); index++) {
            ProbeInput probe = probes.get(index);
            if (probe.dependsOnClientId() == null) continue;
            Long id = insertedIds.get(index);
            Long dependency = ids.get(probe.dependsOnClientId());
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE external_probe SET depends_on_probe_id=? WHERE id=?")) {
                statement.setLong(1, dependency);
                statement.setLong(2, id);
                statement.executeUpdate();
            }
        }
    }

    private ServiceDefinition serviceDefinition(Connection connection, long id) throws SQLException {
        String name, environment, systemName, description;
        Long groupId;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT group_id, name, environment, system_name, description FROM external_service
                WHERE id=? AND archived_at IS NULL
                """)) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Servicio no encontrado");
                groupId = nullableLong(rows, "group_id"); name = rows.getString("name");
                environment = rows.getString("environment"); systemName = rows.getString("system_name");
                description = rows.getString("description");
            }
        }
        List<ProbeDefinition> probes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM external_probe WHERE service_id=? AND archived_at IS NULL
                ORDER BY display_order, id
                """)) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) probes.add(new ProbeDefinition(rows.getLong("id"),
                        nullableLong(rows, "depends_on_probe_id"), nullableLong(rows, "credential_id"),
                        rows.getString("name"), rows.getString("probe_type"), rows.getBoolean("mandatory"),
                        rows.getInt("display_order"), rows.getString("host"), nullableInt(rows, "port"),
                        rows.getString("url"), rows.getString("http_method"), rows.getString("request_headers"),
                        rows.getString("request_body"), rows.getString("expected_statuses"),
                        rows.getString("expected_body"), rows.getString("token_json_field"),
                        rows.getString("auth_type"), rows.getString("auth_header"), rows.getString("db_engine"),
                        rows.getString("db_name"), rows.getString("db_service"), rows.getString("validation_query"),
                        rows.getInt("timeout_ms")));
            }
        }
        return new ServiceDefinition(id, groupId, name, environment, systemName, description, probes);
    }

    private CredentialSecret credentialSecret(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT encrypted_payload, iv FROM external_credential WHERE id=? AND archived_at IS NULL
                """)) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Credencial no encontrada");
                return cipher.decrypt(rows.getBytes(1), rows.getBytes(2));
            }
        }
    }

    private void executeArchive(String table, long id) {
        String sql = "UPDATE " + table + " SET archived_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP WHERE id=? AND archived_at IS NULL";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            if (statement.executeUpdate() == 0) throw new IllegalArgumentException("Registro no encontrado");
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    private static void insertResult(Connection connection, long runId, ProbeResultView result) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO external_probe_result(run_id, probe_id, probe_name, probe_type, mandatory, status,
                    phase, message, duration_ms, response_code, checked_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, runId); setLong(statement, 2, result.probeId());
            statement.setString(3, result.probeName()); statement.setString(4, result.probeType());
            statement.setBoolean(5, result.mandatory()); statement.setString(6, result.status());
            statement.setString(7, result.phase()); statement.setString(8, trim(result.message(), 4000));
            statement.setLong(9, result.durationMs()); setInt(statement, 10, result.responseCode());
            statement.setTimestamp(11, Timestamp.from(result.checkedAt())); statement.executeUpdate();
        }
    }

    private static Long findService(Connection connection, String name, String environment) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM external_service WHERE archived_at IS NULL AND lower(name)=lower(?)
                    AND lower(environment)=lower(?) ORDER BY id LIMIT 1
                """)) {
            statement.setString(1, name); statement.setString(2, environment);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getLong(1) : null; }
        }
    }

    private static Long findProbe(Connection connection, long serviceId, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM external_probe WHERE service_id=? AND archived_at IS NULL
                    AND lower(name)=lower(?) ORDER BY id LIMIT 1
                """)) {
            statement.setLong(1, serviceId); statement.setString(2, name == null ? "" : name);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getLong(1) : null; }
        }
    }

    private static String normalizeRunStatus(String status) {
        String value = status == null ? "ERROR" : status.toUpperCase();
        return switch (value) { case "GREEN", "UP" -> "UP"; case "YELLOW", "WARNING" -> "WARNING"; case "RED", "DOWN" -> "DOWN"; default -> "ERROR"; };
    }

    private static String normalizeResultStatus(String status) {
        String value = status == null ? "DOWN" : status.toUpperCase();
        return switch (value) { case "GREEN", "UP" -> "UP"; case "YELLOW", "WARNING" -> "WARNING"; case "SKIPPED" -> "SKIPPED"; default -> "DOWN"; };
    }

    private static void applyScheduledState(Connection connection, long serviceId, long runId,
                                            String runStatus, Instant now) throws SQLException {
        int failures;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT consecutive_failures FROM external_service WHERE id=? FOR UPDATE")) {
            statement.setLong(1, serviceId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return;
                failures = rows.getInt(1);
            }
        }
        ExternalStatePolicy.Transition transition = ExternalStatePolicy.afterScheduledRun(failures, runStatus);
        boolean failed = transition.incidentAction() != ExternalStatePolicy.IncidentAction.RECOVER;
        if (transition.incidentAction() == ExternalStatePolicy.IncidentAction.RECOVER) {
            recoverIncident(connection, serviceId, runId, now);
        } else {
            upsertIncident(connection, serviceId, runId, transition.consecutiveFailures(), now);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE external_service SET status=?, consecutive_failures=?, last_scheduled_at=?,
                    last_success_at=CASE WHEN ? THEN ? ELSE last_success_at END, updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """)) {
            statement.setString(1, transition.serviceStatus()); statement.setInt(2, transition.consecutiveFailures());
            statement.setTimestamp(3, Timestamp.from(now)); statement.setBoolean(4, !failed);
            statement.setTimestamp(5, Timestamp.from(now)); statement.setLong(6, serviceId);
            statement.executeUpdate();
        }
    }

    private static void upsertIncident(Connection connection, long serviceId, long runId,
                                       int failures, Instant now) throws SQLException {
        if (failures == 1) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO external_incident(service_id, opened_at, status, first_failure_run_id)
                    SELECT ?, ?, 'PENDING', ? WHERE NOT EXISTS (
                      SELECT 1 FROM external_incident WHERE service_id=? AND status IN ('PENDING','OPEN'))
                    """)) {
                statement.setLong(1, serviceId); statement.setTimestamp(2, Timestamp.from(now));
                statement.setLong(3, runId); statement.setLong(4, serviceId); statement.executeUpdate();
            }
        } else {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE external_incident SET status='OPEN', confirmed_at=COALESCE(confirmed_at, ?),
                        confirmed_run_id=COALESCE(confirmed_run_id, ?)
                    WHERE service_id=? AND status='PENDING'
                    """)) {
                statement.setTimestamp(1, Timestamp.from(now)); statement.setLong(2, runId);
                statement.setLong(3, serviceId); statement.executeUpdate();
            }
        }
    }

    private static void recoverIncident(Connection connection, long serviceId, long runId, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE external_incident SET status='RECOVERED', recovered_at=?, recovery_run_id=?
                WHERE service_id=? AND status IN ('PENDING','OPEN')
                """)) {
            statement.setTimestamp(1, Timestamp.from(now)); statement.setLong(2, runId);
            statement.setLong(3, serviceId); statement.executeUpdate();
        }
    }

    private static void bindCredential(PreparedStatement statement, CredentialInput input,
                                       CredentialCipher.Encrypted encrypted, boolean update) throws SQLException {
        statement.setString(1, input.name().trim()); statement.setString(2, input.environment().trim());
        statement.setString(3, input.credentialType().trim()); statement.setString(4, input.systemName().trim());
        statement.setBytes(5, encrypted.payload()); statement.setBytes(6, encrypted.iv());
    }

    private static long generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) throw new SQLException("No se obtuvo el identificador generado");
            return keys.getLong(1);
        }
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT); else statement.setLong(index, value);
    }

    private static void setInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) statement.setNull(index, Types.INTEGER); else statement.setInt(index, value);
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column); return rows.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column); return rows.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column); return value == null ? null : value.toInstant();
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static String valueOrPrevious(String value, String previous) { return value == null || value.isBlank() ? previous : value; }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String trim(String value, int max) { return value == null || value.length() <= max ? value : value.substring(0, max); }
    private static RuntimeException unavailable(SQLException exception) { return new ExternalUnavailableException("El catálogo de servicios externos no está disponible", exception); }

    public record ProbeDefinition(long id, Long dependsOnProbeId, Long credentialId, String name, String probeType,
                                  boolean mandatory, int displayOrder, String host, Integer port, String url,
                                  String httpMethod, String requestHeaders, String requestBody, String expectedStatuses,
                                  String expectedBody, String tokenJsonField, String authType, String authHeader,
                                  String dbEngine, String dbName, String dbService, String validationQuery, int timeoutMs) {
    }

    public record ServiceDefinition(long id, Long groupId, String name, String environment, String systemName,
                                    String description, List<ProbeDefinition> probes) {
    }

    public record CredentialExport(long id, String name, String environment, String credentialType, String systemName,
                                   CredentialSecret secret) {
    }

    private static final class ServiceBuilder {
        private final long id; private final Long groupId; private final String name; private final String environment;
        private final String systemName; private final String description; private final String status;
        private final int consecutiveFailures; private final Instant lastScheduledAt; private final Instant lastSuccessAt;
        private final List<ProbeView> probes = new ArrayList<>(); private RunView lastRun;

        private ServiceBuilder(long id, Long groupId, String name, String environment, String systemName,
                               String description, String status, int consecutiveFailures,
                               Instant lastScheduledAt, Instant lastSuccessAt) {
            this.id=id; this.groupId=groupId; this.name=name; this.environment=environment;
            this.systemName=systemName; this.description=description; this.status=status;
            this.consecutiveFailures=consecutiveFailures; this.lastScheduledAt=lastScheduledAt;
            this.lastSuccessAt=lastSuccessAt;
        }

        private ServiceView build() {
            return new ServiceView(id, groupId, name, environment, systemName, description, status,
                    consecutiveFailures, lastScheduledAt, lastSuccessAt, List.copyOf(probes), lastRun);
        }
    }
}
