package com.ocptools.ocpmap;

import com.ocptools.ocpmap.TestCaseFlowDefinition.Edge;
import com.ocptools.ocpmap.TestCaseFlowDefinition.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestCaseFlowValidatorTest {
    @Test
    void acceptsNestedQueriesAndNormalizesOperationOrder() {
        TestCaseFlowDefinition definition = new TestCaseFlowDefinition(2, List.of(
                new Node("user", 12L, "Usuario", "Inicia el caso", 20d, 30d, "any"),
                new Node("crm", null, "CRM externo", "Procesa la consulta", 280d, 30d, "all")
        ), List.of(
                new Edge("continue", "user", "crm", "CONTINUE", 8, 0, "Crear orden", null),
                new Edge("query", "user", "crm", "QUERY", 3, 0, "Consultar cliente", "Cliente válido")
        ));

        TestCaseFlowDefinition result = TestCaseFlowValidator.validate(definition);

        assertEquals(2, result.schemaVersion());
        assertEquals("ANY", result.nodes().get(0).waitMode());
        assertEquals("ALL", result.nodes().get(1).waitMode());
        assertEquals("QUERY", result.edges().get(0).trigger());
        assertEquals(1, result.edges().get(0).order());
        assertEquals("Cliente válido", result.edges().get(0).responseLabel());
        assertEquals("CONTINUE", result.edges().get(1).trigger());
        assertEquals(2, result.edges().get(1).order());
    }

    @Test
    void convertsLegacyConnectionsWithoutKeepingRetries() {
        TestCaseFlowDefinition definition = new TestCaseFlowDefinition(1, List.of(
                new Node("a", null, "A", 10d, 10d, "ANY"),
                new Node("b", null, "B", 200d, 10d, "ANY")
        ), List.of(new Edge("retry", "b", "a", "RETURN", 1, 11, null)));

        TestCaseFlowDefinition result = TestCaseFlowValidator.validate(definition);

        assertEquals(2, result.schemaVersion());
        assertEquals("CONTINUE", result.edges().get(0).trigger());
        assertEquals(0, result.edges().get(0).repetitions());
    }

    @Test
    void rejectsRetriesInVersionTwo() {
        TestCaseFlowDefinition definition = new TestCaseFlowDefinition(2, List.of(
                new Node("a", null, "A", null, 10d, 10d, "ANY"),
                new Node("b", null, "B", null, 200d, 10d, "ANY")
        ), List.of(new Edge("retry", "b", "a", "RETURN", 1, 0, null, null)));

        assertThrows(IllegalArgumentException.class, () -> TestCaseFlowValidator.validate(definition));
    }

    @Test
    void rejectsConnectionsToMissingNodes() {
        TestCaseFlowDefinition definition = new TestCaseFlowDefinition(1,
                List.of(new Node("a", null, "A", 10d, 10d, "ANY")),
                List.of(new Edge("bad", "a", "missing", "AUTO", 1, 0, null)));

        assertThrows(IllegalArgumentException.class, () -> TestCaseFlowValidator.validate(definition));
    }

    @Test
    void rejectsAnEmptyFlow() {
        assertThrows(IllegalArgumentException.class, () -> TestCaseFlowValidator.validate(
                new TestCaseFlowDefinition(1, List.of(), List.of())));
    }
}
