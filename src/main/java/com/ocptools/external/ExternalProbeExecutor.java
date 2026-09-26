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

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
import org.jboss.logging.Logger;

@ApplicationScoped
public class ExternalProbeExecutor {
    private static final Logger LOG = Logger.getLogger(ExternalProbeExecutor.class);
    private static final int MAX_HTTP_BODY = 1_048_576;
    private static final int HTTP_ATTEMPTS = 2;

    @Inject
    ExternalRepository repository;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ToolsConfig config;

    private SSLContext insecureSslContext;

    @PostConstruct
    void initializeHttpTransport() {
        if (!config.externalMonitor().tlsVerify()) {
            try {
                TrustManager[] trustAll = {new X509TrustManager() {
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                }};
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, trustAll, new SecureRandom());
                insecureSslContext = context;
            } catch (Exception exception) {
                throw new IllegalStateException("No se pudo configurar el transporte HTTPS del monitor", exception);
            }
        }
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
            Map<String, String> headers = new LinkedHashMap<>(parseHeaders(expand(probe.requestHeaders(), credential)));
            headers.putIfAbsent("User-Agent", "OCP-Tools/0.7");
            addAuthentication(headers, probe, credential, tokens);
            String method = probe.httpMethod() == null ? "GET" : probe.httpMethod().toUpperCase(Locale.ROOT);
            String expandedBody = expand(probe.requestBody(), credential);
            for (int attempt = 1; attempt <= HTTP_ATTEMPTS; attempt++) {
                try {
                    HttpAttemptResponse response = sendHttp(probe, method, headers, expandedBody);
                    return evaluateHttp(probe, response, started, attempt);
                } catch (HttpAttemptException exception) {
                    if (isTimeout(exception.getCause()) && attempt < HTTP_ATTEMPTS) {
                        LOG.warnf("tool=external-monitor action=http-retry probeId=%d attempt=%d timeoutMs=%d phase=%s",
                                probe.id(), attempt + 1, probe.timeoutMs(), exception.phase);
                        continue;
                    }
                    return httpFailure(probe, exception, started, attempt);
                }
            }
            return outcome("DOWN", "HTTP", "No se pudo completar la solicitud HTTP", started, null, null);
        } catch (Exception exception) {
            return outcome("DOWN", "HTTP_CONFIG", "Configuración HTTP inválida: " + safe(exception),
                    started, null, null);
        }
    }

    private HttpAttemptResponse sendHttp(ProbeDefinition probe, String method, Map<String, String> headers,
                                         String body) throws HttpAttemptException {
        HttpURLConnection connection = null;
        String phase = "HTTP_CONNECT";
        Integer responseCode = null;
        try {
            connection = (HttpURLConnection) URI.create(probe.url()).toURL().openConnection();
            connection.setConnectTimeout(probe.timeoutMs());
            connection.setReadTimeout(probe.timeoutMs());
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            headers.forEach(connection::setRequestProperty);
            // Cada intento cierra su conexión para no heredar sockets persistentes degradados.
            connection.setRequestProperty("Connection", "close");
            if (connection instanceof HttpsURLConnection https && !config.externalMonitor().tlsVerify()) {
                https.setSSLSocketFactory(insecureSslContext.getSocketFactory());
                https.setHostnameVerifier((hostname, session) -> true);
            }
            byte[] payload = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
            if (payload != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
            }
            connection.connect();
            if (payload != null) {
                phase = "HTTP_WRITE";
                try (OutputStream stream = connection.getOutputStream()) {
                    stream.write(payload);
                }
            }
            phase = "HTTP_RESPONSE";
            responseCode = connection.getResponseCode();
            InputStream raw = responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] bytes = raw == null ? new byte[0] : readLimited(raw);
            return new HttpAttemptResponse(responseCode, new String(bytes, StandardCharsets.UTF_8),
                    bytes.length > MAX_HTTP_BODY);
        } catch (Exception exception) {
            throw new HttpAttemptException(phase, responseCode, exception);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readLimited(InputStream raw) throws IOException {
        try (InputStream stream = raw) {
            return stream.readNBytes(MAX_HTTP_BODY + 1);
        }
    }

    private ProbeOutcome evaluateHttp(ProbeDefinition probe, HttpAttemptResponse response, long started, int attempt) {
        String retry = attempt > 1 ? " · respuesta obtenida tras 1 reintento" : "";
        if (response.bodyTooLarge) {
            return outcome("DOWN", "HTTP_BODY", "La respuesta supera 1 MiB" + retry,
                    started, response.statusCode, null);
        }
        Set<Integer> expected = expectedStatuses(probe.expectedStatuses());
        if (!expected.contains(response.statusCode)) {
            return outcome("DOWN", "HTTP_STATUS", "HTTP " + response.statusCode + " fuera de lo esperado" + retry,
                    started, response.statusCode, null);
        }
        if (probe.expectedBody() != null && !response.body.contains(probe.expectedBody())) {
            return outcome("DOWN", "HTTP_CONTENT", "La respuesta no contiene el texto esperado" + retry,
                    started, response.statusCode, null);
        }
        String token = null;
        if ("TOKEN_HTTP".equals(probe.probeType())) {
            try {
                token = jsonField(response.body, probe.tokenJsonField());
            } catch (Exception exception) {
                return outcome("DOWN", "TOKEN", "La respuesta del token no contiene JSON válido" + retry,
                        started, response.statusCode, null);
            }
            if (token == null || token.isBlank()) {
                return outcome("DOWN", "TOKEN", "No se encontró el token en la respuesta" + retry,
                        started, response.statusCode, null);
            }
        }
        return outcome("UP", "HTTP", "Respuesta HTTP esperada" + retry,
                started, response.statusCode, token);
    }

    private static ProbeOutcome httpFailure(ProbeDefinition probe, HttpAttemptException failure,
                                            long started, int attempts) {
        Throwable cause = failure.getCause();
        String phase = classifyHttpFailure(cause, failure.phase);
        return outcome("DOWN", phase, httpFailureMessage(cause, phase, attempts, probe.timeoutMs()),
                started, failure.responseCode, null);
    }

    private static String classifyHttpFailure(Throwable failure, String phase) {
        if (hasCause(failure, SSLException.class)) return "HTTP_TLS";
        if (hasCause(failure, UnknownHostException.class)) return "HTTP_DNS";
        if (isTimeout(failure)) {
            if ("HTTP_CONNECT".equals(phase)) return "HTTP_CONNECT_TIMEOUT";
            if ("HTTP_WRITE".equals(phase)) return "HTTP_WRITE_TIMEOUT";
            return "HTTP_RESPONSE_TIMEOUT";
        }
        if (hasCause(failure, ConnectException.class) || hasCause(failure, NoRouteToHostException.class)) {
            return "HTTP_CONNECT";
        }
        if (hasCause(failure, IOException.class)) return "HTTP_IO";
        return "HTTP";
    }

    private static String httpFailureMessage(Throwable failure, String phase, int attempts, int timeoutMs) {
        if (phase.endsWith("_TIMEOUT")) {
            String target = switch (phase) {
                case "HTTP_CONNECT_TIMEOUT" -> "conexión";
                case "HTTP_WRITE_TIMEOUT" -> "envío";
                default -> "respuesta";
            };
            return "Tiempo de " + target + " agotado tras " + attempts + (attempts == 1 ? " intento" : " intentos")
                    + " (" + timeoutMs + " ms por intento)";
        }
        if ("HTTP_TLS".equals(phase)) return "Error TLS: " + safe(failure);
        if ("HTTP_DNS".equals(phase)) return "No se pudo resolver el host: " + safe(failure);
        if ("HTTP_CONNECT".equals(phase)) return "No se pudo establecer la conexión: " + safe(failure);
        if ("HTTP_IO".equals(phase)) return "Error de entrada/salida HTTP: " + safe(failure);
        return safe(failure);
    }

    private static boolean isTimeout(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException) return true;
            String message = current.getMessage();
            if (message != null && (message.toLowerCase(Locale.ROOT).contains("timed out")
                    || message.toLowerCase(Locale.ROOT).contains("timeout"))) return true;
        }
        return false;
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
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

    private void addAuthentication(Map<String, String> headers, ProbeDefinition probe, CredentialSecret credential,
                                   Map<Long, String> tokens) {
        String type = probe.authType() == null ? "NONE" : probe.authType();
        if ("NONE".equals(type)) return;
        if ("BASIC".equals(type) || "OAUTH_CLIENT".equals(type)) {
            String user = "OAUTH_CLIENT".equals(type) ? credential.clientId() : credential.username();
            String password = "OAUTH_CLIENT".equals(type) ? credential.clientSecret() : credential.password();
            String encoded = Base64.getEncoder().encodeToString((nullToEmpty(user) + ":" + nullToEmpty(password))
                    .getBytes(StandardCharsets.UTF_8));
            headers.put("Authorization", "Basic " + encoded);
        } else if ("BEARER".equals(type)) {
            String token = probe.dependsOnProbeId() == null ? null : tokens.get(probe.dependsOnProbeId());
            if (token == null && credential != null) token = firstPresent(credential.token(), credential.password());
            headers.put("Authorization", "Bearer " + nullToEmpty(token));
        } else if ("API_KEY".equals(type)) {
            String header = firstPresent(probe.authHeader(), "X-API-Key");
            String value = credential == null ? "" : firstPresent(credential.token(), credential.password());
            headers.put(header, nullToEmpty(value));
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

    private static String safe(Throwable exception) {
        String message = exception == null ? null : exception.getMessage();
        if (message == null || message.isBlank()) message = exception == null ? "Error desconocido" : exception.getClass().getSimpleName();
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

    private record HttpAttemptResponse(int statusCode, String body, boolean bodyTooLarge) {
    }

    private static final class HttpAttemptException extends Exception {
        private final String phase;
        private final Integer responseCode;

        private HttpAttemptException(String phase, Integer responseCode, Throwable cause) {
            super(cause);
            this.phase = phase;
            this.responseCode = responseCode;
        }
    }
}
