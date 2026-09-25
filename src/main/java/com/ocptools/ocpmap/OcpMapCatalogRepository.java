package com.ocptools.ocpmap;

import com.ocptools.ocpmap.OcpMapCatalogSnapshot.DeploymentDetail;
import com.ocptools.ocpmap.OcpMapCatalogSnapshot.ElementDetail;
import com.ocptools.ocpmap.OcpMapCatalogSnapshot.ElementReference;
import com.ocptools.ocpmap.OcpMapCatalogSnapshot.NamespaceDetail;
import com.ocptools.ocpmap.OcpMapCatalogSnapshot.TestCaseDetail;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class OcpMapCatalogRepository {
    @Inject
    @DataSource("inventory")
    javax.sql.DataSource dataSource;

    public OcpMapCatalogSnapshot load(URI consoleBaseUrl, List<String> scannedNamespaces,
                                      List<String> visibleNamespaces) {
        Set<String> scanned = new LinkedHashSet<>(scannedNamespaces);
        Set<String> visible = new LinkedHashSet<>(visibleNamespaces);
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                Map<Long, ElementDetail> elements = loadElements(connection);
                Map<Long, DeploymentDetail> deployments = loadDeployments(connection, consoleBaseUrl, scanned, visible);
                List<TestCaseDetail> cases = loadCases(connection, elements, deployments);
                List<NamespaceDetail> namespaceStatus = loadNamespaces(connection, scanned, visible);
                connection.commit();
                return new OcpMapCatalogSnapshot(Instant.now(), List.copyOf(scanned), List.copyOf(visible),
                        namespaceStatus, cases, List.copyOf(elements.values()), List.copyOf(deployments.values()));
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public long createTestCase(AdminTestCaseRequest request, Instant now) {
        validateTestCase(request);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long id = insertTestCase(connection, request, now);
                replaceTestCaseRelations(connection, id, request, now);
                saveAnnotation(connection, id, request.annotation(), now);
                connection.commit();
                return id;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw translate(exception);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public void updateTestCase(long id, AdminTestCaseRequest request, Instant now) {
        validateIdentifier(id);
        validateTestCase(request);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE test_case SET code = ?, name = ?, description = ?, display_order = ?, updated_at = ?
                        WHERE id = ?
                        """)) {
                    setTestCaseFields(statement, request, now);
                    statement.setLong(6, id);
                    requireUpdated(statement.executeUpdate(), "Caso de prueba no encontrado");
                }
                replaceTestCaseRelations(connection, id, request, now);
                saveAnnotation(connection, id, request.annotation(), now);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw translate(exception);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public long cloneTestCase(long sourceId, AdminTestCaseRequest request, Instant now) {
        validateIdentifier(sourceId);
        validateTestCase(request);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ensureTestCaseExists(connection, sourceId);
                long id = insertTestCase(connection, request, now);
                replaceTestCaseRelations(connection, id, request, now);
                saveAnnotation(connection, id, request.annotation(), now);
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO test_case_flow(test_case_id, schema_version, definition, created_at, updated_at)
                        SELECT ?, schema_version, definition, ?, ?
                        FROM test_case_flow WHERE test_case_id = ?
                        """)) {
                    statement.setLong(1, id);
                    statement.setTimestamp(2, Timestamp.from(now));
                    statement.setTimestamp(3, Timestamp.from(now));
                    statement.setLong(4, sourceId);
                    statement.executeUpdate();
                }
                connection.commit();
                return id;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw translate(exception);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public void setTestCaseArchived(long id, boolean archived, Instant now) {
        updateArchive("test_case", id, archived, now, "Caso de prueba no encontrado");
    }

    public long createElement(AdminElementRequest request, Instant now) {
        validateElement(request);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO catalog_element(name, description, display_order, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?) RETURNING id
                     """)) {
            setElementFields(statement, request);
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setTimestamp(5, Timestamp.from(now));
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        } catch (SQLException exception) {
            throw translate(exception);
        }
    }

    public void updateElement(long id, AdminElementRequest request, Instant now) {
        validateIdentifier(id);
        validateElement(request);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE catalog_element SET name = ?, description = ?, display_order = ?, updated_at = ?
                     WHERE id = ?
                     """)) {
            setElementFields(statement, request);
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setLong(5, id);
            requireUpdated(statement.executeUpdate(), "Elemento no encontrado");
        } catch (SQLException exception) {
            throw translate(exception);
        }
    }

    public void setElementArchived(long id, boolean archived, Instant now) {
        updateArchive("catalog_element", id, archived, now, "Elemento no encontrado");
    }

    private void updateArchive(String table, long id, boolean archived, Instant now, String missingMessage) {
        validateIdentifier(id);
        String sql = "UPDATE " + table + " SET archived_at = ?, updated_at = ? WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (archived) {
                statement.setTimestamp(1, Timestamp.from(now));
            } else {
                statement.setTimestamp(1, null);
            }
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setLong(3, id);
            requireUpdated(statement.executeUpdate(), missingMessage);
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    private static Map<Long, ElementDetail> loadElements(Connection connection) throws SQLException {
        Map<Long, ElementDetail> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT element.id, element.name, element.description, element.display_order,
                       element.created_at, element.updated_at, element.archived_at,
                       COUNT(DISTINCT relation.test_case_id) FILTER (WHERE tc.archived_at IS NULL) AS active_cases
                FROM catalog_element element
                LEFT JOIN test_case_element relation ON relation.catalog_element_id = element.id
                LEFT JOIN test_case tc ON tc.id = relation.test_case_id
                GROUP BY element.id
                ORDER BY element.archived_at NULLS FIRST, element.display_order, element.name
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                long id = rows.getLong("id");
                result.put(id, new ElementDetail(id, rows.getString("name"), rows.getString("description"),
                        rows.getInt("display_order"), rows.getInt("active_cases"),
                        instant(rows, "created_at"), instant(rows, "updated_at"), instant(rows, "archived_at")));
            }
        }
        return result;
    }

    private static List<NamespaceDetail> loadNamespaces(Connection connection, Set<String> scanned,
                                                          Set<String> visible) throws SQLException {
        List<NamespaceDetail> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT name, last_attempt_at, last_success_at, last_error
                FROM ocp_namespace ORDER BY name
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String name = rows.getString("name");
                if (scanned.contains(name)) {
                    result.add(new NamespaceDetail(name, visible.contains(name),
                            instant(rows, "last_attempt_at"), instant(rows, "last_success_at"),
                            rows.getString("last_error")));
                }
            }
        }
        return result;
    }

    private static Map<Long, DeploymentDetail> loadDeployments(Connection connection, URI consoleBaseUrl,
                                                                Set<String> scanned, Set<String> visible)
            throws SQLException {
        Map<Long, DeploymentDetail> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT deployment.id, deployment.namespace, deployment.name, deployment.desired_replicas,
                       deployment.ready_replicas, deployment.active, deployment.last_seen_at,
                       STRING_AGG(tc.code, ',' ORDER BY tc.display_order)
                           FILTER (WHERE tc.archived_at IS NULL) AS active_test_cases
                FROM ocp_deployment deployment
                LEFT JOIN test_case_deployment relation ON relation.deployment_id = deployment.id
                LEFT JOIN test_case tc ON tc.id = relation.test_case_id
                GROUP BY deployment.id
                ORDER BY deployment.namespace, deployment.name
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String namespace = rows.getString("namespace");
                if (!scanned.contains(namespace)) {
                    continue;
                }
                long id = rows.getLong("id");
                Integer desired = integer(rows, "desired_replicas");
                String name = rows.getString("name");
                result.put(id, new DeploymentDetail(id, namespace, name, desired,
                        integer(rows, "ready_replicas"), OcpStatePolicy.state(desired), rows.getBoolean("active"),
                        visible.contains(namespace), instant(rows, "last_seen_at"),
                        split(rows.getString("active_test_cases")), consoleUrl(consoleBaseUrl, namespace, name)));
            }
        }
        return result;
    }

    private static List<TestCaseDetail> loadCases(Connection connection, Map<Long, ElementDetail> elements,
                                                   Map<Long, DeploymentDetail> deployments) throws SQLException {
        Map<Long, TestCaseBuilder> cases = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT tc.id, tc.code, tc.name, tc.description, annotation.content AS annotation,
                       (flow.test_case_id IS NOT NULL) AS has_flow,
                       tc.display_order, tc.created_at, tc.updated_at, tc.archived_at
                FROM test_case tc
                LEFT JOIN test_case_annotation annotation ON annotation.test_case_id = tc.id
                LEFT JOIN test_case_flow flow ON flow.test_case_id = tc.id
                ORDER BY tc.archived_at NULLS FIRST, tc.display_order, tc.code
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                long id = rows.getLong("id");
                cases.put(id, new TestCaseBuilder(id, rows.getString("code"), rows.getString("name"),
                        rows.getString("description"), rows.getString("annotation"), rows.getBoolean("has_flow"),
                        rows.getInt("display_order"),
                        instant(rows, "created_at"), instant(rows, "updated_at"), instant(rows, "archived_at")));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT test_case_id, value FROM test_case_metadata ORDER BY test_case_id, display_order
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                builder(cases, rows.getLong("test_case_id")).metadata.add(rows.getString("value"));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT test_case_id, catalog_element_id FROM test_case_element
                ORDER BY test_case_id, display_order
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                ElementDetail element = elements.get(rows.getLong("catalog_element_id"));
                if (element != null) {
                    builder(cases, rows.getLong("test_case_id")).elements.add(new ElementReference(
                            element.id(), element.name(), element.archivedAt() != null));
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT test_case_id, deployment_id FROM test_case_deployment
                ORDER BY test_case_id, display_order
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                DeploymentDetail deployment = deployments.get(rows.getLong("deployment_id"));
                if (deployment != null) {
                    builder(cases, rows.getLong("test_case_id")).deployments.add(deployment);
                }
            }
        }
        return cases.values().stream().map(TestCaseBuilder::build).toList();
    }

    private static void replaceTestCaseRelations(Connection connection, long testCaseId,
                                                 AdminTestCaseRequest request, Instant now) throws SQLException {
        List<String> metadata = normalizeStrings(request.metadata());
        List<Long> elementIds = normalizeIds(request.elementIds());
        List<Long> deploymentIds = normalizeIds(request.deploymentIds());
        ensureAllowedIds(connection, "SELECT id FROM catalog_element", elementIds,
                "Uno o más elementos no existen");
        ensureAllowedIds(connection, "SELECT id FROM ocp_deployment", deploymentIds,
                "Uno o más deployments no fueron descubiertos por OCP");
        deleteRelations(connection, testCaseId);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case_metadata(test_case_id, value, display_order, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            int order = 1;
            for (String value : metadata) {
                statement.setLong(1, testCaseId);
                statement.setString(2, value);
                statement.setInt(3, order++);
                statement.setTimestamp(4, Timestamp.from(now));
                statement.setTimestamp(5, Timestamp.from(now));
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case_element(test_case_id, catalog_element_id, display_order, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            int order = 1;
            for (Long elementId : elementIds) {
                statement.setLong(1, testCaseId);
                statement.setLong(2, elementId);
                statement.setInt(3, order++);
                statement.setTimestamp(4, Timestamp.from(now));
                statement.setTimestamp(5, Timestamp.from(now));
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case_deployment(test_case_id, deployment_id, display_order, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            int order = 1;
            for (Long deploymentId : deploymentIds) {
                statement.setLong(1, testCaseId);
                statement.setLong(2, deploymentId);
                statement.setInt(3, order++);
                statement.setTimestamp(4, Timestamp.from(now));
                statement.setTimestamp(5, Timestamp.from(now));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static long insertTestCase(Connection connection, AdminTestCaseRequest request, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case(code, name, description, display_order, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """)) {
            setTestCaseFields(statement, request, now);
            statement.setTimestamp(6, Timestamp.from(now));
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static void saveAnnotation(Connection connection, long testCaseId, String annotation, Instant now)
            throws SQLException {
        if (blank(annotation)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM test_case_annotation WHERE test_case_id = ?")) {
                statement.setLong(1, testCaseId);
                statement.executeUpdate();
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case_annotation(test_case_id, content, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (test_case_id) DO UPDATE SET
                    content = EXCLUDED.content,
                    updated_at = EXCLUDED.updated_at
                """)) {
            statement.setLong(1, testCaseId);
            statement.setString(2, annotation.trim());
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setTimestamp(4, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private static void ensureTestCaseExists(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM test_case WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalArgumentException("Caso de prueba no encontrado");
                }
            }
        }
    }

    private static void deleteRelations(Connection connection, long testCaseId) throws SQLException {
        for (String table : List.of("test_case_metadata", "test_case_element", "test_case_deployment")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE test_case_id = ?")) {
                statement.setLong(1, testCaseId);
                statement.executeUpdate();
            }
        }
    }

    private static void ensureAllowedIds(Connection connection, String sql, List<Long> requested, String message)
            throws SQLException {
        if (requested.isEmpty()) {
            return;
        }
        Set<Long> allowed = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                allowed.add(rows.getLong(1));
            }
        }
        if (!allowed.containsAll(requested)) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void setTestCaseFields(PreparedStatement statement, AdminTestCaseRequest request,
                                          Instant now) throws SQLException {
        statement.setString(1, request.code().trim());
        statement.setString(2, request.name().trim());
        statement.setString(3, blankToNull(request.description()));
        statement.setInt(4, request.displayOrder() == null ? 0 : request.displayOrder());
        statement.setTimestamp(5, Timestamp.from(now));
    }

    private static void setElementFields(PreparedStatement statement, AdminElementRequest request)
            throws SQLException {
        statement.setString(1, request.name().trim());
        statement.setString(2, blankToNull(request.description()));
        statement.setInt(3, request.displayOrder() == null ? 0 : request.displayOrder());
    }

    private static void validateTestCase(AdminTestCaseRequest request) {
        if (request == null || blank(request.code()) || blank(request.name())) {
            throw new IllegalArgumentException("Código y nombre son obligatorios");
        }
        if (request.code().trim().length() > 20 || request.name().trim().length() > 160) {
            throw new IllegalArgumentException("Código o nombre excede la longitud permitida");
        }
        normalizeStrings(request.metadata()).forEach(value -> {
            if (value.length() > 200) {
                throw new IllegalArgumentException("Un metadato excede 200 caracteres");
            }
        });
    }

    private static void validateElement(AdminElementRequest request) {
        if (request == null || blank(request.name())) {
            throw new IllegalArgumentException("El nombre del elemento es obligatorio");
        }
        if (request.name().trim().length() > 200) {
            throw new IllegalArgumentException("El nombre del elemento excede 200 caracteres");
        }
    }

    private static void validateIdentifier(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("Identificador inválido");
        }
    }

    private static void requireUpdated(int count, String message) {
        if (count == 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private static TestCaseBuilder builder(Map<Long, TestCaseBuilder> cases, long id) {
        TestCaseBuilder builder = cases.get(id);
        if (builder == null) {
            throw new IllegalStateException("Relación huérfana para el caso " + id);
        }
        return builder;
    }

    private static List<String> normalizeStrings(Collection<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
    }

    private static List<Long> normalizeIds(Collection<Long> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(value -> value != null && value > 0).distinct().toList();
    }

    private static List<String> split(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(","));
    }

    private static Integer integer(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String consoleUrl(URI baseUrl, String namespace, String name) {
        return baseUrl.toString().replaceAll("/+$", "") + "/k8s/ns/" + namespace + "/deployments/" + name;
    }

    private static String blankToNull(String value) {
        return blank(value) ? null : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static RuntimeException translate(Exception exception) {
        if (exception instanceof IllegalArgumentException illegalArgumentException) {
            return illegalArgumentException;
        }
        if (exception instanceof SQLException sqlException) {
            if ("23505".equals(sqlException.getSQLState())) {
                return new IllegalArgumentException("El código o nombre ya existe");
            }
            return unavailable(sqlException);
        }
        return new IllegalStateException(exception);
    }

    private static OcpMapUnavailableException unavailable(SQLException exception) {
        return new OcpMapUnavailableException("El catálogo PostgreSQL no está disponible", exception);
    }

    private static final class TestCaseBuilder {
        private final long id;
        private final String code;
        private final String name;
        private final String description;
        private final String annotation;
        private final boolean hasFlow;
        private final int displayOrder;
        private final Instant createdAt;
        private final Instant updatedAt;
        private final Instant archivedAt;
        private final List<String> metadata = new ArrayList<>();
        private final List<ElementReference> elements = new ArrayList<>();
        private final List<DeploymentDetail> deployments = new ArrayList<>();

        private TestCaseBuilder(long id, String code, String name, String description, String annotation,
                                boolean hasFlow, int displayOrder,
                                Instant createdAt, Instant updatedAt, Instant archivedAt) {
            this.id = id;
            this.code = code;
            this.name = name;
            this.description = description;
            this.annotation = annotation;
            this.hasFlow = hasFlow;
            this.displayOrder = displayOrder;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.archivedAt = archivedAt;
        }

        private TestCaseDetail build() {
            return new TestCaseDetail(id, code, name, description, annotation, hasFlow,
                    displayOrder, List.copyOf(metadata),
                    List.copyOf(elements), List.copyOf(deployments), createdAt, updatedAt, archivedAt);
        }
    }
}
