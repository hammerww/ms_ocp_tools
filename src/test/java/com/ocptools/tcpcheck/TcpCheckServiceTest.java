package com.ocptools.tcpcheck;

import com.ocptools.api.TcpCheckRequest;
import com.ocptools.api.TcpCheckResponse;
import com.ocptools.common.RequestMetadata;
import com.ocptools.config.ToolsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpCheckServiceTest {
    private TcpCheckService service;
    private StubConnector connector;

    @BeforeEach
    void setUp() {
        RequestMetadata metadata = new RequestMetadata();
        metadata.correlationId("correlation-test");
        metadata.user("tester");

        connector = new StubConnector();
        service = new TcpCheckService();
        service.connector = connector;
        service.metadata = metadata;
        service.config = testConfig(Duration.ofSeconds(10));
    }

    @Test
    void connectsAndClosesImmediately() {
        TcpCheckResponse response = service.check(new TcpCheckRequest("10.010.71.174", 8003));

        assertEquals(TcpCheckStatus.CONNECTED, response.status());
        assertTrue(response.connected());
        assertEquals("10.10.71.174", response.ip());
        assertEquals(8003, connector.destination.getPort());
        assertEquals("10.10.71.174", connector.destination.getAddress().getHostAddress());
        assertEquals(10_000, connector.timeoutMillis);
        assertEquals(10, response.timeoutSeconds());
        assertEquals("correlation-test", response.correlationId());
    }

    @Test
    void reportsConnectionRefused() {
        connector.failure = new ConnectException("Connection refused");

        TcpCheckResponse response = service.check(new TcpCheckRequest("10.10.71.174", 8003));

        assertEquals(TcpCheckStatus.CONNECTION_REFUSED, response.status());
        assertFalse(response.connected());
    }

    @Test
    void reportsTimeout() {
        connector.failure = new SocketTimeoutException("connect timed out");

        TcpCheckResponse response = service.check(new TcpCheckRequest("10.10.71.174", 8003));

        assertEquals(TcpCheckStatus.TIMEOUT, response.status());
        assertTrue(response.message().contains("10 segundos"));
    }

    @Test
    void reportsMissingRoute() {
        connector.failure = new NoRouteToHostException("No route to host");

        TcpCheckResponse response = service.check(new TcpCheckRequest("10.10.71.174", 8003));

        assertEquals(TcpCheckStatus.NO_ROUTE, response.status());
    }

    @Test
    void reportsUnexpectedIoErrorWithoutExposingDetail() {
        connector.failure = new IOException("sensitive technical detail");

        TcpCheckResponse response = service.check(new TcpCheckRequest("10.10.71.174", 8003));

        assertEquals(TcpCheckStatus.ERROR, response.status());
        assertFalse(response.message().contains("sensitive"));
    }

    @Test
    void rejectsInvalidIpv4WithoutOpeningSocket() {
        TcpCheckResponse response = service.check(new TcpCheckRequest("10.10.71.999", 8003));

        assertEquals(TcpCheckStatus.INVALID_REQUEST, response.status());
        assertFalse(response.connected());
        assertEquals(0, connector.calls);
    }

    private static final class StubConnector extends TcpConnector {
        private IOException failure;
        private InetSocketAddress destination;
        private int timeoutMillis;
        private int calls;

        @Override
        public void connect(InetSocketAddress destination, int timeoutMillis) throws IOException {
            calls++;
            this.destination = destination;
            this.timeoutMillis = timeoutMillis;
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static ToolsConfig testConfig(Duration timeout) {
        return new ToolsConfig() {
            @Override public Soap soap() { return null; }
            @Override public Timeouts timeouts() { return null; }
            @Override public Cms cms() { return null; }
            @Override public TcpCheck tcpCheck() { return () -> timeout; }
            @Override public Environments environments() { return null; }
            @Override public OcpMap ocpMap() { return null; }
            @Override public ExternalMonitor externalMonitor() { return null; }
        };
    }
}
