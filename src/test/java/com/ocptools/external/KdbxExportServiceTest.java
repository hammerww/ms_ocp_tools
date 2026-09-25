package com.ocptools.external;

import org.junit.jupiter.api.Test;
import org.linguafranca.pwdb.Entry;
import org.linguafranca.pwdb.kdbx.KdbxCreds;
import org.linguafranca.pwdb.kdbx.jackson.JacksonDatabase;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class KdbxExportServiceTest {
    @Test
    void exportsAReadableDatabaseWithCredentialHierarchy() throws Exception {
        ExternalRepository repository = new ExternalRepository() {
            @Override
            public List<CredentialExport> credentialExports() {
                return List.of(new CredentialExport(42, "Oracle Backend", "Testing", "DATABASE", "Facturación",
                        new ExternalModels.CredentialSecret("backend-user", "backend-password", null,
                                null, null, Map.of("url", "jdbc:oracle:thin:@//db:1521/SVC"))));
            }
        };
        KdbxExportService service = new KdbxExportService();
        service.repository = repository;
        service.config = ExternalTestConfig.withSecrets(null, "master-password");

        byte[] content = service.export();
        assertFalse(new String(content, StandardCharsets.ISO_8859_1).contains("backend-password"));

        JacksonDatabase database = JacksonDatabase.load(
                new KdbxCreds("master-password".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream(content));
        List<? extends Entry> entries = database.getRootGroup().findEntries("Oracle Backend", true);
        assertEquals(1, entries.size());
        assertEquals("backend-user", entries.get(0).getUsername());
        assertEquals("backend-password", entries.get(0).getPassword());
        assertEquals("jdbc:oracle:thin:@//db:1521/SVC", entries.get(0).getUrl());
    }
}
