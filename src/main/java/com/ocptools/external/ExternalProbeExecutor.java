package com.ocptools.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocptools.config.ToolsConfig;
import com.ocptools.external.ExternalModels.CredentialSecret;
import com.ocptools.external.ExternalModels.ProbeResultView;
import com.ocptools.external.ExternalRepository.ProbeDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.annotation.PostConstruct;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class ExternalProbeExecutor {
    private static final int MAX_HTTP_BODY = 1_048_576;

    @Inject
    ExternalRepository repository;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ToolsConfig config;

    private HttpClient httpClient;

    @PostConstruct
    void initializeHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15));
        if (!config.externalMonitor().tlsVerify()) {
            try {
                TrustManager[] trustAll = {new X509TrustManager() {
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                }};
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, trustAll, new SecureRandom());
                SSLParameters parameters = new SSLParameters();
                // La cadena vacía impide que HttpClient añada "HTTPS" como verificador
                // de nombre. Se aplica sólo a este cliente; SOAP CMS conserva su TLS normal.
                parameters.setEndpointIdentificationAlgorithm("");
                builder.sslContext(context).sslParameters(parameters);
            } catch (Exception exception) {
                throw new IllegalStateException("No se pudo configurar el cliente HTTPS del monitor", exception);
            }
        }
        httpClient = builder.build();
    }

    public Execution execute(ExternalRepository.ServiceDefinition service) {
        List<ProbeResultView> results = new ArrayList<>();
        Map<Long, ProbeResultView> completed = new HashMap<>();
        Map<Long, String> tokens = new HashMap<>();
        for (ProbeDefinition probe : service.probes()) {
            if (probe.dependsOnProbeId() != null) {
                ProbeResultView dependency = completed.get(probe.dependsOnProbeId());
                if (dependency == null || !"UP".equals(dependency.status())) {
                    ProbeResultView skipped = result(probe, "SKIPPED", "DEPENDENCY",
                            "No se ejecutó porque la prueba previa no terminó correctamente", 0, null);
                    results.add(skipped);
                    completed.put(probe.id(), skipped);
                    continue;
                }
            }
            ProbeOutcome outcome = switch (probe.probeType()) {
                case "TCP" -> tcp(probe);
                case "HTTP", "TOKEN_HTTP" -> http(probe, tokens);
                case "DATABASE" -> database(probe);
                default -> new ProbeOutcome("DOWN", "CONFIG", "Tipo de prueba no soportado", 0, null, null);
            };
            ProbeResultView view = result(probe, outcome.status, outcome.phase, outcome.message,
                    outcome.durationMs, outcome.responseCode);
            results.add(view);
            completed.put(probe.id(), view);
            if (outcome.token != null) tokens.put(probe.id(), outcome.token);
        }
        boolean mandatoryFailed = results.stream().anyMatch(item -> item.mandatory()
                && ("DOWN".equals(item.status()) || "SKIPPED".equals(item.status())));
        boolean warning = results.stream().anyMatch(item -> !"UP".equals(item.status()));
        return new Execution(mandatoryFailed ? "DOWN" : warning ? "WARNING" : "UP", List.copyOf(results));
    }

    private ProbeOutcome tcp(ProbeDefinition probe) {
        long started = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(probe.host(), probe.port()), probe.timeoutMs());
            return outcome("UP", "TCP", "Puerto accesible", started, null, null);
        } catch (Exception exception) {
            return outcome("DOWN", "TCP", safe(exception), started, null, null);
        }
    }

    private ProbeOutcome http(ProbeDefinition probe, Map<Long, String> tokens) {
        long started = System.nanoTime();
        try {
            CredentialSecret credential = probe.credentialId() == null ? null : repository.credentialSecret(probe.credentialId());
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(probe.url()))
                    .timeout(Duration.ofMillis(probe.timeoutMs()))
                    .header("User-Agent", "OCP-Tools/0.7");
            Map<String, String> headers = parseHeaders(expand(probe.requestHeaders(), credential));
            headers.forEach(builder::header);
            addAuthentication(builder, probe, credential, tokens);
            String method = probe.httpMethod() == null ? "GET" : probe.httpMethod().toUpperCase(Locale.ROOT);
            String expandedBody = expand(probe.requestBody(), credential);
            HttpRequest.BodyPublisher body = expandedBody == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(expandedBody, StandardCharsets.UTF_8);
            builder.method(method, body);
            HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream stream = response.body()) {
                bytes = stream.readNBytes(MAX_HTTP_BODY + 1);
            }
            if (bytes.length > MAX_HTTP_BODY) {
                return outcome("DOWN", "HTTP_BODY", "La respuesta supera 1 MiB", started, response.statusCode(), null);
            }
            String responseBody = new String(bytes, StandardCharsets.UTF_8);
            Set<Integer> expected = expectedStatuses(probe.expectedStatuses());
            if (!expected.contains(response.statusCode())) {
                return outcome("DOWN", "HTTP_STATUS", "HTTP " + response.statusCode() + " fuera de lo esperado",
                        started, response.statusCode(), null);
            }
            if (probe.expectedBody() != null && !responseBody.contains(probe.expectedBody())) {
                return outcome("DOWN", "HTTP_CONTENT", "La respuesta no contiene el texto esperado",
                        started, response.statusCode(), null);
            }
            String token = null;
            if ("TOKEN_HTTP".equals(probe.probeType())) {
                token = jsonField(responseBody, probe.tokenJsonField());
                if (token == null || token.isBlank()) {
                    return outcome("DOWN", "TOKEN", "No se encontró el token en la respuesta",
                            started, response.statusCode(), null);
                }
            }
            return outcome("UP", "HTTP", "Respuesta HTTP esperada", started, response.statusCode(), token);
        } catch (Exception exception) {
            return outcome("DOWN", "HTTP", safe(exception), started, null, null);
        }
    }

    private ProbeOutcome database(ProbeDefinition probe) {
        long started = System.nanoTime();
        try {
            CredentialSecret credential = repository.credentialSecret(probe.credentialId());
            String url = jdbcUrl(probe);
            java.util.Properties properties = new java.util.Properties();
            if (credential != null && credential.username() != null) properties.setProperty("user", credential.username());
            if (credential != null && credential.password() != null) properties.setProperty("password", credential.password());
            if (credential != null && credential.extra() != null) properties.putAll(credential.extra());
            configureJdbcTimeouts(properties, probe);
            try (Connection connection = DriverManager.getConnection(url, properties);
                 Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(Math.max(1, (int) Math.ceil(probe.timeoutMs() / 1000.0)));
                statement.execute(query(probe));
            }
            return outcome("UP", "QUERY", "Conexión y consulta operativas", started, null, null);
        } catch (Exception exception) {
            return outcome("DOWN", "DATABASE", safe(exception), started, null, null);
        }
    }

    private void addAuthentication(HttpRequest.Builder builder, ProbeDefinition probe, CredentialSecret credential,
                                   Map<Long, String> tokens) {
        String type = probe.authType() == null ? "NONE" : probe.authType();
        if ("NONE".equals(type)) return;
        if ("BASIC".equals(type) || "OAUTH_CLIENT".equals(type)) {
            String user = "OAUTH_CLIENT".equals(type) ? credential.clientId() : credential.username();
            String password = "OAUTH_CLIENT".equals(type) ? credential.clientSecret() : credential.password();
            String encoded = Base64.getEncoder().encodeToString((nullToEmpty(user) + ":" + nullToEmpty(password))
                    .getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + encoded);
        } else if ("BEARER".equals(type)) {
            String token = probe.dependsOnProbeId() == null ? null : tokens.get(probe.dependsOnProbeId());
            if (token == null && credential != null) token = firstPresent(credential.token(), credential.password());
            builder.header("Authorization", "Bearer " + nullToEmpty(token));
        } else if ("API_KEY".equals(type)) {
            String header = firstPresent(probe.authHeader(), "X-API-Key");
            String value = credential == null ? "" : firstPresent(credential.token(), credential.password());
            builder.header(header, nullToEmpty(value));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseHeaders(String value) throws Exception {
        if (value == null || value.isBlank()) return Map.of();
        Map<?, ?> raw = objectMapper.readValue(value, LinkedHashMap.class);
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item)));
        return result;
    }

    private String jsonField(String body, String dottedField) throws Exception {
        JsonNode node = objectMapper.readTree(body);
        for (String part : dottedField.split("\\.")) node = node == null ? null : node.get(part);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String expand(String value, CredentialSecret credential) {
        if (value == null || credential == null) return value;
        return value.replace("${username}", nullToEmpty(credential.username()))
                .replace("${password}", nullToEmpty(credential.password()))
                .replace("${token}", nullToEmpty(credential.token()))
                .replace("${clientId}", nullToEmpty(credential.clientId()))
                .replace("${clientSecret}", nullToEmpty(credential.clientSecret()));
    }

    private static Set<Integer> expectedStatuses(String value) {
        if (value == null || value.isBlank()) return java.util.stream.IntStream.rangeClosed(200, 399).boxed().collect(Collectors.toSet());
        try {
            return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isEmpty())
                    .map(Integer::parseInt).collect(Collectors.toSet());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Códigos HTTP esperados inválidos");
        }
    }

    private static String jdbcUrl(ProbeDefinition probe) {
        return switch (probe.dbEngine()) {
            case "ORACLE" -> "jdbc:oracle:thin:@//" + probe.host() + ":" + probe.port() + "/" + probe.dbService();
            case "POSTGRESQL" -> "jdbc:postgresql://" + probe.host() + ":" + probe.port() + "/" + probe.dbName();
            case "SQLSERVER" -> "jdbc:sqlserver://" + probe.host() + ":" + probe.port() + ";databaseName=" + probe.dbName()
                    + ";encrypt=true;trustServerCertificate=false";
            case "MYSQL" -> "jdbc:mysql://" + probe.host() + ":" + probe.port() + "/" + probe.dbName();
            default -> throw new IllegalArgumentException("Motor JDBC no soportado");
        };
    }

    private static String query(ProbeDefinition probe) {
        if (probe.validationQuery() != null && !probe.validationQuery().isBlank()) return probe.validationQuery();
        return "ORACLE".equals(probe.dbEngine()) ? "SELECT 1 FROM DUAL" : "SELECT 1";
    }

    private static void configureJdbcTimeouts(java.util.Properties properties, ProbeDefinition probe) {
        int timeoutMs = probe.timeoutMs();
        int timeoutSeconds = Math.max(1, (int) Math.ceil(timeoutMs / 1000.0));
        switch (probe.dbEngine()) {
            case "ORACLE" -> {
                properties.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(timeoutMs));
                properties.setProperty("oracle.jdbc.ReadTimeout", String.valueOf(timeoutMs));
            }
            case "POSTGRESQL" -> {
                properties.setProperty("connectTimeout", String.valueOf(timeoutSeconds));
                properties.setProperty("socketTimeout", String.valueOf(timeoutSeconds));
            }
            case "SQLSERVER" -> {
                properties.setProperty("loginTimeout", String.valueOf(timeoutSeconds));
                properties.setProperty("socketTimeout", String.valueOf(timeoutMs));
            }
            case "MYSQL" -> {
                properties.setProperty("connectTimeout", String.valueOf(timeoutMs));
                properties.setProperty("socketTimeout", String.valueOf(timeoutMs));
            }
            default -> { }
        }
    }

    private static ProbeResultView result(ProbeDefinition probe, String status, String phase, String message,
                                          long duration, Integer responseCode) {
        return new ProbeResultView(probe.id(), probe.name(), probe.probeType(), probe.mandatory(), status, phase,
                message, duration, responseCode, Instant.now());
    }

    private static ProbeOutcome outcome(String status, String phase, String message, long started,
                                        Integer responseCode, String token) {
        return new ProbeOutcome(status, phase, message,
                Math.max(0, (System.nanoTime() - started) / 1_000_000), responseCode, token);
    }

    private static String safe(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        message = message.replaceAll("[\\r\\n\\t]", " ");
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private static String firstPresent(String first, String second) { return first != null && !first.isBlank() ? first : second; }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    public record Execution(String status, List<ProbeResultView> results) {
    }

    private record ProbeOutcome(String status, String phase, String message, long durationMs,
                                Integer responseCode, String token) {
    }
}
