package com.ocptools.ocpmap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class OcpMapFlowRepository {
    @Inject
    @DataSource("inventory")
    javax.sql.DataSource dataSource;

    @Inject
    ObjectMapper objectMapper;

    public Optional<TestCaseFlowDetail> findByTestCaseId(long testCaseId) {
        validateIdentifier(testCaseId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT tc.id, tc.code, tc.name, flow.definition, flow.updated_at
                     FROM test_case tc
                     JOIN test_case_flow flow ON flow.test_case_id = tc.id
                     WHERE tc.id = ? AND tc.archived_at IS NULL
                     """)) {
            statement.setLong(1, testCaseId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                TestCaseFlowDefinition definition = objectMapper.readValue(
                        row.getString("definition"), TestCaseFlowDefinition.class);
                return Optional.of(new TestCaseFlowDetail(row.getLong("id"), row.getString("code"),
                        row.getString("name"), TestCaseFlowValidator.validate(definition),
                        row.getTimestamp("updated_at").toInstant()));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("El flujo guardado no contiene JSON válido", exception);
            }
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public void save(long testCaseId, TestCaseFlowDefinition requested, Instant now) {
        validateIdentifier(testCaseId);
        TestCaseFlowDefinition definition = TestCaseFlowValidator.validate(requested);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ensureTestCaseExists(connection, testCaseId);
                ensureCatalogElementsExist(connection, definition);
                String json = objectMapper.writeValueAsString(definition);
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO test_case_flow(test_case_id, schema_version, definition, created_at, updated_at)
                        VALUES (?, ?, CAST(? AS jsonb), ?, ?)
                        ON CONFLICT (test_case_id) DO UPDATE SET
                            schema_version = EXCLUDED.schema_version,
                            definition = EXCLUDED.definition,
                            updated_at = EXCLUDED.updated_at
                        """)) {
                    statement.setLong(1, testCaseId);
                    statement.setInt(2, definition.schemaVersion());
                    statement.setString(3, json);
                    statement.setTimestamp(4, Timestamp.from(now));
                    statement.setTimestamp(5, Timestamp.from(now));
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE test_case SET updated_at = ? WHERE id = ?")) {
                    statement.setTimestamp(1, Timestamp.from(now));
                    statement.setLong(2, testCaseId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | JsonProcessingException | RuntimeException exception) {
                connection.rollback();
                throw translate(exception);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    private static void ensureTestCaseExists(Connection connection, long testCaseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM test_case WHERE id = ? AND archived_at IS NULL")) {
            statement.setLong(1, testCaseId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalArgumentException("Caso de prueba no encontrado");
                }
            }
        }
    }

    private static void ensureCatalogElementsExist(Connection connection, TestCaseFlowDefinition definition)
            throws SQLException {
        Set<Long> requested = new LinkedHashSet<>();
        definition.nodes().stream().map(TestCaseFlowDefinition.Node::catalogElementId)
                .filter(id -> id != null).forEach(requested::add);
        if (requested.isEmpty()) {
            return;
        }
        Set<Long> allowed = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT id FROM catalog_element")) {
            while (rows.next()) {
                allowed.add(rows.getLong(1));
            }
        }
        if (!allowed.containsAll(requested)) {
            throw new IllegalArgumentException("El flujo referencia un elemento de catálogo inexistente");
        }
    }

    private static void validateIdentifier(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("Identificador inválido");
        }
    }

    private static RuntimeException translate(Exception exception) {
        if (exception instanceof IllegalArgumentException illegalArgumentException) {
            return illegalArgumentException;
        }
        if (exception instanceof SQLException sqlException) {
            return unavailable(sqlException);
        }
        if (exception instanceof JsonProcessingException jsonProcessingException) {
            return new IllegalArgumentException("No se pudo guardar la definición del flujo", jsonProcessingException);
        }
        return new IllegalStateException(exception);
    }

    private static OcpMapUnavailableException unavailable(SQLException exception) {
        return new OcpMapUnavailableException("El catálogo PostgreSQL no está disponible", exception);
    }
}
