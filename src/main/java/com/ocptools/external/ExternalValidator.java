package com.ocptools.external;

import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@ApplicationScoped
public class ExternalValidator {
    private static final Set<String> TYPES = Set.of("TCP", "HTTP", "TOKEN_HTTP", "DATABASE");
    private static final Set<String> AUTH_TYPES = Set.of("NONE", "BASIC", "BEARER", "API_KEY", "OAUTH_CLIENT");
    private static final Set<String> DB_ENGINES = Set.of("ORACLE", "POSTGRESQL", "SQLSERVER", "MYSQL");
    private static final Pattern READ_SQL = Pattern.compile("^(SELECT|WITH)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITE_SQL = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|MERGE|DROP|ALTER|CREATE|TRUNCATE|GRANT|REVOKE|CALL|EXEC|BEGIN|COMMIT|ROLLBACK)\\b",
            Pattern.CASE_INSENSITIVE);

    public void service(ExternalModels.ServiceInput input) {
        if (input == null) throw invalid("Servicio requerido");
        required(input.name(), "Nombre", 200);
        required(input.environment(), "Ambiente", 80);
        required(input.systemName(), "Sistema", 160);
        List<ExternalModels.ProbeInput> probes = input.probes() == null ? List.of() : input.probes();
        if (probes.isEmpty()) throw invalid("El servicio debe tener al menos una prueba");
        Set<Long> clientIds = new HashSet<>();
        Map<Long, Integer> clientOrder = new HashMap<>();
        for (int index = 0; index < probes.size(); index++) {
            ExternalModels.ProbeInput probe = probes.get(index);
            required(probe.name(), "Nombre de prueba", 180);
            String type = upper(probe.probeType());
            if (!TYPES.contains(type)) throw invalid("Tipo de prueba no soportado: " + probe.probeType());
            if (probe.clientId() != null && !clientIds.add(probe.clientId())) {
                throw invalid("Los identificadores temporales de pruebas deben ser únicos");
            }
            if (probe.clientId() != null) clientOrder.put(probe.clientId(), index);
            int timeout = probe.timeoutMs() == null ? 10000 : probe.timeoutMs();
            if (timeout < 250 || timeout > 120000) throw invalid("Timeout fuera del rango 250–120000 ms");
            if (type.equals("TCP")) hostPort(probe);
            if (type.equals("HTTP") || type.equals("TOKEN_HTTP")) {
                uri(probe.url());
                String method = upper(defaultValue(probe.httpMethod(), "GET"));
                if (!Set.of("GET", "HEAD", "POST").contains(method)) {
                    throw invalid("Método HTTP no permitido");
                }
                String headers = probe.requestHeaders() == null ? "" : probe.requestHeaders().toLowerCase(Locale.ROOT);
                if (headers.contains("authorization") || headers.contains("cookie") || headers.contains("api-key")
                        || headers.contains("apikey") || headers.contains("token") || headers.contains("secret")) {
                    throw invalid("Los headers sensibles deben configurarse mediante una credencial, no como texto");
                }
            }
            if (type.equals("DATABASE")) {
                hostPort(probe);
                String engine = upper(probe.dbEngine());
                if (!DB_ENGINES.contains(engine)) throw invalid("Motor de base de datos no soportado");
                if (probe.credentialId() == null) throw invalid("La prueba de base de datos requiere una credencial");
                if ("ORACLE".equals(engine) && blank(probe.dbService())) throw invalid("Oracle requiere nombre de servicio");
                if (!"ORACLE".equals(engine) && blank(probe.dbName())) throw invalid("El motor seleccionado requiere nombre de base de datos");
                readOnlyQuery(probe.validationQuery());
            }
            String authType = upper(defaultValue(probe.authType(), "NONE"));
            if (!AUTH_TYPES.contains(authType)) {
                throw invalid("Tipo de autenticación no soportado");
            }
            if (Set.of("BASIC", "API_KEY", "OAUTH_CLIENT").contains(authType) && probe.credentialId() == null) {
                throw invalid("La autenticación seleccionada requiere una credencial");
            }
            if ("BEARER".equals(authType) && probe.credentialId() == null && probe.dependsOnClientId() == null) {
                throw invalid("Bearer requiere credencial o una prueba previa de token");
            }
            if (containsCredentialTemplate(probe.requestBody()) && probe.credentialId() == null) {
                throw invalid("El body usa marcadores sensibles y requiere una credencial");
            }
            if (type.equals("TOKEN_HTTP") && blank(probe.tokenJsonField())) {
                throw invalid("La obtención de token requiere el campo JSON de respuesta");
            }
        }
        for (ExternalModels.ProbeInput probe : probes) {
            if (probe.dependsOnClientId() != null && !clientIds.contains(probe.dependsOnClientId())) {
                throw invalid("Una dependencia de prueba no pertenece al servicio");
            }
            if (probe.clientId() != null && probe.clientId().equals(probe.dependsOnClientId())) {
                throw invalid("Una prueba no puede depender de sí misma");
            }
            if (probe.dependsOnClientId() != null && probe.clientId() != null
                    && clientOrder.get(probe.dependsOnClientId()) >= clientOrder.get(probe.clientId())) {
                throw invalid("Una prueba solo puede depender de otra ubicada antes en el orden");
            }
        }
    }

    public void credential(ExternalModels.CredentialInput input) {
        if (input == null) throw invalid("Credencial requerida");
        required(input.name(), "Nombre", 160);
        required(input.environment(), "Ambiente", 80);
        required(input.credentialType(), "Tipo de credencial", 80);
        required(input.systemName(), "Sistema", 160);
    }

    private static void hostPort(ExternalModels.ProbeInput probe) {
        required(probe.host(), "Host", 253);
        if (probe.port() == null || probe.port() < 1 || probe.port() > 65535) {
            throw invalid("Puerto fuera del rango 1–65535");
        }
    }

    private static void uri(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) throw invalid("URL HTTP/HTTPS inválida");
        } catch (IllegalArgumentException exception) {
            throw invalid("URL HTTP/HTTPS inválida");
        }
    }

    private static void readOnlyQuery(String value) {
        if (blank(value)) return;
        String query = value.trim();
        if (query.endsWith(";")) query = query.substring(0, query.length() - 1).trim();
        if (query.contains(";") || !READ_SQL.matcher(query).find() || WRITE_SQL.matcher(query).find()) {
            throw invalid("La consulta de validación debe ser una única sentencia de solo lectura SELECT/WITH");
        }
    }

    private static void required(String value, String field, int max) {
        if (blank(value)) throw invalid(field + " es requerido");
        if (value.trim().length() > max) throw invalid(field + " supera " + max + " caracteres");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean containsCredentialTemplate(String value) {
        return value != null && (value.contains("${username}") || value.contains("${password}")
                || value.contains("${token}") || value.contains("${clientId}")
                || value.contains("${clientSecret}"));
    }

    static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    static String defaultValue(String value, String fallback) {
        return blank(value) ? fallback : value.trim();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
