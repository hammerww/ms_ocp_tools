package com.ocptools.external;

import com.ocptools.config.ToolsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.linguafranca.pwdb.Database;
import org.linguafranca.pwdb.Entry;
import org.linguafranca.pwdb.Group;
import org.linguafranca.pwdb.kdbx.KdbxCreds;
import org.linguafranca.pwdb.kdbx.jackson.JacksonDatabase;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class KdbxExportService {
    @Inject
    ExternalRepository repository;

    @Inject
    ToolsConfig config;

    public byte[] export() {
        String password = config.externalMonitor().kdbxMasterPassword().orElse("");
        if (password.isBlank()) throw new IllegalStateException("Falta EXTERNAL_KDBX_MASTER_PASSWORD");
        try {
            Database database = new JacksonDatabase();
            database.setName("OCP Tools - Servicios externos");
            database.setShouldProtect(Entry.STANDARD_PROPERTY_NAME_PASSWORD, true);
            database.setShouldProtect("Token", true);
            database.setShouldProtect("ClientSecret", true);
            Group root = database.getRootGroup();
            Map<String, Group> groups = new HashMap<>();
            for (ExternalRepository.CredentialExport credential : repository.credentialExports()) {
                Group environment = group(database, root, groups, credential.environment(), credential.environment());
                String typeKey = credential.environment() + "/" + credential.credentialType();
                Group type = group(database, environment, groups, typeKey, credential.credentialType());
                String systemKey = typeKey + "/" + credential.systemName();
                Group system = group(database, type, groups, systemKey, credential.systemName());
                Entry entry = database.newEntry();
                entry.setTitle(credential.name());
                entry.setUsername(value(credential.secret().username()));
                entry.setPassword(value(first(credential.secret().password(), credential.secret().token(),
                        credential.secret().clientSecret())));
                if (present(credential.secret().token())) entry.setProperty("Token", credential.secret().token());
                if (present(credential.secret().clientId())) entry.setProperty("ClientId", credential.secret().clientId());
                if (present(credential.secret().clientSecret())) entry.setProperty("ClientSecret", credential.secret().clientSecret());
                String url = credential.secret().extra() == null ? null : credential.secret().extra().get("url");
                entry.setUrl(value(url));
                entry.setNotes(notes(credential));
                system.addEntry(entry);
            }
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                database.save(new KdbxCreds(password.getBytes(StandardCharsets.UTF_8)), output);
                return output.toByteArray();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("No fue posible crear el archivo KDBX", exception);
        }
    }

    private static Group group(Database database, Group parent, Map<String, Group> cache,
                               String key, String name) {
        return cache.computeIfAbsent(key, ignored -> {
            Group created = database.newGroup(name);
            parent.addGroup(created);
            return created;
        });
    }

    private static String notes(ExternalRepository.CredentialExport credential) {
        StringBuilder notes = new StringBuilder("Exportado por OCP Tools. ID interno: ").append(credential.id());
        ExternalModels.CredentialSecret secret = credential.secret();
        if (secret.clientId() != null && !secret.clientId().isBlank()) notes.append("\nClient ID: ").append(secret.clientId());
        if (secret.extra() != null) secret.extra().forEach((key, value) -> {
            if (!"url".equalsIgnoreCase(key)) notes.append("\n").append(key).append(": ").append(value);
        });
        return notes.toString();
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
