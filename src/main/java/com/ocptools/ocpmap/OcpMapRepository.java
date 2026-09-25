package com.ocptools.ocpmap;

import com.ocptools.ocpmap.OcpMapSnapshot.DeploymentView;
import com.ocptools.ocpmap.OcpMapSnapshot.NamespaceView;
import com.ocptools.ocpmap.OcpMapSnapshot.TestCaseView;
import com.ocptools.ocpmap.OcpMapSnapshot.TestingMarkView;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class OcpMapRepository {
    @Inject
    @DataSource("inventory")
    javax.sql.DataSource dataSource;

    public void registerNamespaces(List<String> namespaces) {
        String sql = "INSERT INTO ocp_namespace(name) VALUES (?) ON CONFLICT DO NOTHING";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String namespace : namespaces) {
                statement.setString(1, namespace);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public void applySuccessfulScan(String namespace, List<ObservedDeployment> deployments, Instant observedAt) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ensureNamespace(connection, namespace);
                updateNamespaceSuccess(connection, namespace, observedAt);
                markNamespaceMissing(connection, namespace, observedAt);
                for (ObservedDeployment deployment : deployments) {
                    upsertDeployment(connection, namespace, deployment, observedAt);
                }
                connection.commit();
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

    public void recordScanFailure(String namespace, Instant attemptedAt, String error) {
        String sql = """
                INSERT INTO ocp_namespace(name, last_attempt_at, last_error)
                VALUES (?, ?, ?)
                ON CONFLICT (name) DO UPDATE SET
                    last_attempt_at = EXCLUDED.last_attempt_at,
                    last_error = EXCLUDED.last_error
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            statement.setTimestamp(2, Timestamp.from(attemptedAt));
            statement.setString(3, trim(error, 2000));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public OcpMapSnapshot loadSnapshot(URI consoleBaseUrl, List<String> configuredNamespaces,
                                       List<String> visibleNamespaces) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                Set<String> configured = Set.copyOf(configuredNamespaces);
                Set<String> visible = Set.copyOf(visibleNamespaces);
                List<NamespaceView> namespaces = loadNamespaces(connection).stream()
                        .filter(namespace -> configured.contains(namespace.name()))
                        .filter(namespace -> visible.contains(namespace.name()))
                        .toList();
                Map<Long, DeploymentView> deployments = new LinkedHashMap<>();
                loadDeployments(connection, consoleBaseUrl).forEach((id, deployment) -> {
                    if (configured.contains(deployment.namespace()) && visible.contains(deployment.namespace())) {
                        deployments.put(id, deployment);
                    }
                });
                List<TestCaseView> cases = loadCases(connection, deployments);
                List<DeploymentView> unmapped = deployments.values().stream()
                        .filter(DeploymentView::active)
                        .filter(deployment -> deployment.testCases().isEmpty())
                        .toList();
                OcpMapSnapshot snapshot = new OcpMapSnapshot(Instant.now(), consoleBaseUrl.toString(),
                        namespaces, cases, List.copyOf(deployments.values()), unmapped);
                connection.commit();
                return snapshot;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public void saveTestingMark(String namespace, String name, TestingMarkRequest request, Instant now) {
        if (request.expiresAt() != null && !request.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("La expiración debe ser posterior al momento actual");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long deploymentId = deploymentId(connection, namespace, name);
                String markSql = """
                        INSERT INTO testing_mark(deployment_id, responsible, note, marked_at, expires_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (deployment_id) DO UPDATE SET
                            responsible = EXCLUDED.responsible,
                            note = EXCLUDED.note,
                            marked_at = EXCLUDED.marked_at,
                            expires_at = EXCLUDED.expires_at
                        """;
                try (PreparedStatement statement = connection.prepareStatement(markSql)) {
                    statement.setLong(1, deploymentId);
                    statement.setString(2, request.responsible().trim());
                    statement.setString(3, blankToNull(request.note()));
                    statement.setTimestamp(4, Timestamp.from(now));
                    setInstant(statement, 5, request.expiresAt());
                    statement.executeUpdate();
                }
                insertHistory(connection, deploymentId, "MARKED", request.responsible(), request.note(), now,
                        request.expiresAt());
                connection.commit();
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

    public void removeTestingMark(String namespace, String name, TestingMarkRemovalRequest request, Instant now) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long deploymentId = deploymentId(connection, namespace, name);
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM testing_mark WHERE deployment_id = ?")) {
                    statement.setLong(1, deploymentId);
                    statement.executeUpdate();
                }
                insertHistory(connection, deploymentId, "REMOVED", request.responsible(), request.note(), now, null);
                connection.commit();
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

    private static List<NamespaceView> loadNamespaces(Connection connection) throws SQLException {
        List<NamespaceView> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT name, last_attempt_at, last_success_at, last_error
                FROM ocp_namespace ORDER BY name
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(new NamespaceView(rows.getString("name"),
                        instant(rows, "last_attempt_at"), instant(rows, "last_success_at"),
                        rows.getString("last_error")));
            }
        }
        return result;
    }

    private static Map<Long, DeploymentView> loadDeployments(Connection connection, URI consoleBaseUrl)
            throws SQLException {
        Map<Long, DeploymentView> result = new LinkedHashMap<>();
        String sql = """
                SELECT d.id, d.namespace, d.name, d.desired_replicas, d.ready_replicas,
                       d.available_replicas, d.active, d.last_seen_at,
                       STRING_AGG(tc.code, ',' ORDER BY tc.display_order) AS test_cases,
                       tm.responsible, tm.note, tm.marked_at, tm.expires_at
                FROM ocp_deployment d
                LEFT JOIN test_case_deployment tcd ON tcd.deployment_id = d.id
                LEFT JOIN test_case tc ON tc.id = tcd.test_case_id AND tc.archived_at IS NULL
                LEFT JOIN testing_mark tm ON tm.deployment_id = d.id
                GROUP BY d.id, tm.responsible, tm.note, tm.marked_at, tm.expires_at
                ORDER BY d.namespace, d.name
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                long id = rows.getLong("id");
                boolean active = rows.getBoolean("active");
                Integer desired = integer(rows, "desired_replicas");
                String namespace = rows.getString("namespace");
                String name = rows.getString("name");
                Instant expiresAt = instant(rows, "expires_at");
                TestingMarkView mark = rows.getString("responsible") == null ? null : new TestingMarkView(
                        rows.getString("responsible"), rows.getString("note"),
                        instant(rows, "marked_at"), expiresAt,
                        expiresAt == null || expiresAt.isAfter(Instant.now()));
                result.put(id, new DeploymentView(id, namespace, name, desired,
                        integer(rows, "ready_replicas"), integer(rows, "available_replicas"),
                        OcpStatePolicy.state(desired), active, instant(rows, "last_seen_at"),
                        split(rows.getString("test_cases")), mark, consoleUrl(consoleBaseUrl, namespace, name)));
            }
        }
        return result;
    }

    private static List<TestCaseView> loadCases(Connection connection, Map<Long, DeploymentView> deployments)
            throws SQLException {
        Map<Long, CaseBuilder> cases = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT tc.id, tc.code, tc.name, tc.description, annotation.content AS annotation,
                       (flow.test_case_id IS NOT NULL) AS has_flow
                FROM test_case tc
                LEFT JOIN test_case_annotation annotation ON annotation.test_case_id = tc.id
                LEFT JOIN test_case_flow flow ON flow.test_case_id = tc.id
                WHERE tc.archived_at IS NULL ORDER BY tc.display_order
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                long id = rows.getLong("id");
                cases.put(id, new CaseBuilder(id, rows.getString("code"), rows.getString("name"),
                        rows.getString("description"), rows.getString("annotation"), rows.getBoolean("has_flow")));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT relation.test_case_id, element.name
                FROM test_case_element relation
                JOIN catalog_element element ON element.id = relation.catalog_element_id
                ORDER BY relation.test_case_id, relation.display_order
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                CaseBuilder builder = cases.get(rows.getLong("test_case_id"));
                if (builder != null) {
                    builder.elements.add(rows.getString("name"));
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT test_case_id, value FROM test_case_metadata
                ORDER BY test_case_id, display_order
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                CaseBuilder builder = cases.get(rows.getLong("test_case_id"));
                if (builder != null) {
                    builder.metadata.add(rows.getString("value"));
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT test_case_id, deployment_id FROM test_case_deployment
                ORDER BY test_case_id, display_order
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                CaseBuilder builder = cases.get(rows.getLong("test_case_id"));
                DeploymentView deployment = deployments.get(rows.getLong("deployment_id"));
                if (builder != null && deployment != null) {
                    builder.deployments.add(deployment);
                }
            }
        }
        return cases.values().stream().map(CaseBuilder::build).toList();
    }

    private static void ensureNamespace(Connection connection, String namespace) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ocp_namespace(name) VALUES (?) ON CONFLICT DO NOTHING")) {
            statement.setString(1, namespace);
            statement.executeUpdate();
        }
    }

    private static void updateNamespaceSuccess(Connection connection, String namespace, Instant observedAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE ocp_namespace SET last_attempt_at = ?, last_success_at = ?, last_error = NULL WHERE name = ?
                """)) {
            statement.setTimestamp(1, Timestamp.from(observedAt));
            statement.setTimestamp(2, Timestamp.from(observedAt));
            statement.setString(3, namespace);
            statement.executeUpdate();
        }
    }

    private static void markNamespaceMissing(Connection connection, String namespace, Instant observedAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE ocp_deployment
                SET active = FALSE, missing_since = COALESCE(missing_since, ?)
                WHERE namespace = ? AND active = TRUE
                """)) {
            statement.setTimestamp(1, Timestamp.from(observedAt));
            statement.setString(2, namespace);
            statement.executeUpdate();
        }
    }

    private static void upsertDeployment(Connection connection, String namespace, ObservedDeployment deployment,
                                         Instant observedAt) throws SQLException {
        String sql = """
                INSERT INTO ocp_deployment(namespace, name, desired_replicas, ready_replicas,
                                           available_replicas, active, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, TRUE, ?, ?)
                ON CONFLICT (namespace, name) DO UPDATE SET
                    desired_replicas = EXCLUDED.desired_replicas,
                    ready_replicas = EXCLUDED.ready_replicas,
                    available_replicas = EXCLUDED.available_replicas,
                    active = TRUE,
                    first_seen_at = COALESCE(ocp_deployment.first_seen_at, EXCLUDED.first_seen_at),
                    last_seen_at = EXCLUDED.last_seen_at,
                    missing_since = NULL
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            statement.setString(2, deployment.name());
            statement.setInt(3, deployment.desiredReplicas());
            statement.setInt(4, deployment.readyReplicas());
            statement.setInt(5, deployment.availableReplicas());
            statement.setTimestamp(6, Timestamp.from(observedAt));
            statement.setTimestamp(7, Timestamp.from(observedAt));
            statement.executeUpdate();
        }
    }

    private static long deploymentId(Connection connection, String namespace, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM ocp_deployment WHERE namespace = ? AND name = ?")) {
            statement.setString(1, namespace);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalArgumentException("Deployment no encontrado en el inventario");
                }
                return rows.getLong(1);
            }
        }
    }

    private static void insertHistory(Connection connection, long deploymentId, String action,
                                      String responsible, String note, Instant now, Instant expiresAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO testing_mark_history(deployment_id, action, responsible, note, occurred_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, deploymentId);
            statement.setString(2, action);
            statement.setString(3, responsible.trim());
            statement.setString(4, blankToNull(note));
            statement.setTimestamp(5, Timestamp.from(now));
            setInstant(statement, 6, expiresAt);
            statement.executeUpdate();
        }
    }

    private static void setInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setTimestamp(index, null);
        } else {
            statement.setTimestamp(index, Timestamp.from(value));
        }
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Integer integer(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }

    private static List<String> split(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(","));
    }

    private static String consoleUrl(URI baseUrl, String namespace, String name) {
        String base = baseUrl.toString().replaceAll("/+$", "");
        return base + "/k8s/ns/" + namespace + "/deployments/" + name;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static OcpMapUnavailableException unavailable(SQLException exception) {
        return new OcpMapUnavailableException("El inventario PostgreSQL no está disponible", exception);
    }

    private static final class CaseBuilder {
        private final long id;
        private final String code;
        private final String name;
        private final String description;
        private final String annotation;
        private final boolean hasFlow;
        private final List<String> metadata = new ArrayList<>();
        private final List<String> elements = new ArrayList<>();
        private final List<DeploymentView> deployments = new ArrayList<>();

        private CaseBuilder(long id, String code, String name, String description, String annotation,
                            boolean hasFlow) {
            this.id = id;
            this.code = code;
            this.name = name;
            this.description = description;
            this.annotation = annotation;
            this.hasFlow = hasFlow;
        }

        private TestCaseView build() {
            return new TestCaseView(id, code, name, description, annotation, hasFlow, List.copyOf(metadata),
                    List.copyOf(elements), List.copyOf(deployments));
        }
    }
}
