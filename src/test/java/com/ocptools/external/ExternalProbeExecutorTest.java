package com.ocptools.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocptools.external.ExternalRepository.ProbeDefinition;
import com.ocptools.external.ExternalRepository.ServiceDefinition;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalProbeExecutorTest {
    private HttpServer server;
    private ExecutorService serverExecutor;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
        if (serverExecutor != null) serverExecutor.shutdownNow();
    }

    @Test
    void exposesExpectedHttpResponseCode() throws Exception {
        startServer(exchange -> respond(exchange, 418, "ready"));
        ExternalProbeExecutor.Execution execution = executor().execute(service(probe("418", "ready", 1000)));

        var result = execution.results().get(0);
        assertEquals("UP", result.status());
        assertEquals(418, result.responseCode());
        assertEquals("HTTP", result.phase());
    }

    @Test
    void doesNotRetryAnUnexpectedHttpStatus() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 500, "error");
        });

        ExternalProbeExecutor.Execution execution = executor().execute(service(probe("200", null, 1000)));

        var result = execution.results().get(0);
        assertEquals("DOWN", result.status());
        assertEquals("HTTP_STATUS", result.phase());
        assertEquals(500, result.responseCode());
        assertEquals(1, requests.get());
    }

    @Test
    void retriesOneTimeoutWithAFreshConnectionAndRecovers() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger closeHeaders = new AtomicInteger();
        startServer(exchange -> {
            if ("close".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Connection"))) {
                closeHeaders.incrementAndGet();
            }
            if (requests.incrementAndGet() == 1) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            respond(exchange, 200, "ok");
        });

        ExternalProbeExecutor.Execution execution = executor().execute(service(probe("200", null, 100)));

        var result = execution.results().get(0);
        assertEquals("UP", result.status());
        assertEquals(200, result.responseCode());
        assertEquals(2, requests.get());
        assertEquals(2, closeHeaders.get());
        assertTrue(result.message().contains("1 reintento"));
    }

    @Test
    void classifiesFinalResponseTimeoutAfterTwoAttempts() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            requests.incrementAndGet();
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "late");
        });

        ExternalProbeExecutor.Execution execution = executor().execute(service(probe("200", null, 75)));

        var result = execution.results().get(0);
        assertEquals("DOWN", result.status());
        assertEquals("HTTP_RESPONSE_TIMEOUT", result.phase());
        assertEquals(2, requests.get());
        assertTrue(result.message().contains("2 intentos"));
    }

    private ExternalProbeExecutor executor() {
        ExternalProbeExecutor executor = new ExternalProbeExecutor();
        executor.objectMapper = new ObjectMapper();
        executor.config = ExternalTestConfig.withSecrets(null, null);
        executor.initializeHttpTransport();
        return executor;
    }

    private void startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/probe", handler);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
    }

    private ProbeDefinition probe(String statuses, String expectedBody, int timeoutMs) {
        return new ProbeDefinition(11L, null, null, "HTTP", "HTTP", true, 0,
                null, null, "http://127.0.0.1:" + server.getAddress().getPort() + "/probe",
                "GET", null, null, statuses, expectedBody, null, "NONE", null,
                null, null, null, null, timeoutMs);
    }

    private static ServiceDefinition service(ProbeDefinition probe) {
        return new ServiceDefinition(7L, null, "Servicio", "Testing", "Sistema", null, List.of(probe));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally {
            exchange.close();
        }
    }
}
