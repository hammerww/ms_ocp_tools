package com.ocptools.external;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalValidatorTest {
    private final ExternalValidator validator = new ExternalValidator();

    @Test
    void acceptsOrderedDependency() {
        var tcp = probe(1L, null, "TCP", "host.internal", 443, null);
        var http = probe(2L, 1L, "HTTP", null, null, "https://host.internal/health");
        assertDoesNotThrow(() -> validator.service(service(List.of(tcp, http))));
    }

    @Test
    void rejectsForwardDependency() {
        var first = probe(1L, 2L, "TCP", "host.internal", 443, null);
        var second = probe(2L, null, "TCP", "host.internal", 443, null);
        assertThrows(IllegalArgumentException.class, () -> validator.service(service(List.of(first, second))));
    }

    @Test
    void rejectsSensitiveHeaderInPlainText() {
        var input = new ExternalModels.ProbeInput(1L, null, 3L, "HTTP", "HTTP", true, 0,
                null, null, "https://host.internal/health", "GET", "{\"Authorization\":\"secret\"}",
                null, "200", null, null, "BEARER", null, null, null, null, null, 1000);
        assertThrows(IllegalArgumentException.class, () -> validator.service(service(List.of(input))));
    }

    @Test
    void rejectsMutatingDatabaseQueries() {
        var database = databaseProbe("DELETE FROM orders");
        assertThrows(IllegalArgumentException.class, () -> validator.service(service(List.of(database))));
    }

    @Test
    void acceptsASingleReadOnlyDatabaseQuery() {
        var database = databaseProbe("SELECT 1 FROM DUAL;");
        assertDoesNotThrow(() -> validator.service(service(List.of(database))));
    }

    private static ExternalModels.ServiceInput service(List<ExternalModels.ProbeInput> probes) {
        return new ExternalModels.ServiceInput("Servicio", "Testing", "Sistema", null, null, probes);
    }

    private static ExternalModels.ProbeInput probe(Long id, Long dependency, String type,
                                                   String host, Integer port, String url) {
        return new ExternalModels.ProbeInput(id, dependency, null, type, type, true, id.intValue(), host, port,
                url, "GET", null, null, "200", null, null, "NONE", null,
                null, null, null, null, 1000);
    }

    private static ExternalModels.ProbeInput databaseProbe(String query) {
        return new ExternalModels.ProbeInput(1L, null, 10L, "Validar Oracle", "DATABASE", true, 0,
                "db.internal", 1521, null, null, null, null, null, null, null,
                "NONE", null, "ORACLE", null, "ORCL", query, 10000);
    }
}
