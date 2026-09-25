package com.ocptools.tcpcheck;

import com.ocptools.api.TcpCheckRequest;
import com.ocptools.api.TcpCheckResponse;
import com.ocptools.common.RequestMetadata;
import com.ocptools.config.ToolsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

@ApplicationScoped
public class TcpCheckService {
    private static final Logger LOG = Logger.getLogger(TcpCheckService.class);

    @Inject
    TcpConnector connector;

    @Inject
    RequestMetadata metadata;

    @Inject
    ToolsConfig config;

    public TcpCheckResponse check(TcpCheckRequest request) {
        long started = System.nanoTime();
        Instant checkedAt = Instant.now();
        Duration timeout = config.tcpCheck().timeout();
        String ip = request.ip() == null ? "" : request.ip().trim();
        int port = request.port();
        byte[] address = parseIpv4(ip);
        if (address == null || port < 1 || port > 65535) {
            return response(TcpCheckStatus.INVALID_REQUEST, false, ip, port, started, checkedAt, timeout,
                    "Ingrese una dirección IPv4 válida y un puerto entre 1 y 65535.");
        }

        String normalizedIp = normalizedIpv4(address);
        int timeoutMillis = timeoutMillis(timeout);
        InetSocketAddress destination = new InetSocketAddress(inetAddress(address), port);
        try {
            connector.connect(destination, timeoutMillis);
            return response(TcpCheckStatus.CONNECTED, true, normalizedIp, port, started, checkedAt, timeout,
                    "Conexión TCP exitosa. El destino aceptó la conexión y OCP Tools la cerró inmediatamente.");
        } catch (SocketTimeoutException exception) {
            return response(TcpCheckStatus.TIMEOUT, false, normalizedIp, port, started, checkedAt, timeout,
                    "El destino no aceptó la conexión dentro de " + timeout.toSeconds() + " segundos.");
        } catch (NoRouteToHostException exception) {
            return response(TcpCheckStatus.NO_ROUTE, false, normalizedIp, port, started, checkedAt, timeout,
                    "No existe una ruta de red disponible hacia el destino.");
        } catch (ConnectException exception) {
            return response(TcpCheckStatus.CONNECTION_REFUSED, false, normalizedIp, port, started, checkedAt, timeout,
                    "El host respondió, pero rechazó la conexión al puerto indicado.");
        } catch (IOException exception) {
            LOG.warnf("tool=tcp-check action=CONNECT correlationId=%s ip=%s port=%d result=ERROR errorType=%s",
                    metadata.correlationId(), normalizedIp, port, exception.getClass().getSimpleName());
            return response(TcpCheckStatus.ERROR, false, normalizedIp, port, started, checkedAt, timeout,
                    "No fue posible completar la validación TCP.");
        }
    }

    private TcpCheckResponse response(TcpCheckStatus status, boolean connected, String ip, int port,
                                      long started, Instant checkedAt, Duration timeout, String message) {
        long durationMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        LOG.infof("audit=true user=%s tool=tcp-check action=CONNECT ip=%s port=%d result=%s durationMs=%d timeoutMs=%d correlationId=%s",
                metadata.user(), ip, port, status, durationMs, timeoutMillis(timeout), metadata.correlationId());
        return new TcpCheckResponse(status, connected, ip, port, timeout.toSeconds(), durationMs,
                checkedAt, metadata.correlationId(), message);
    }

    private static int timeoutMillis(Duration timeout) {
        long millis = timeout.toMillis();
        if (millis <= 0 || millis > Integer.MAX_VALUE) {
            throw new IllegalStateException("TCP_CHECK_TIMEOUT debe ser positivo y menor a 24 días");
        }
        return (int) millis;
    }

    private static InetAddress inetAddress(byte[] address) {
        try {
            return InetAddress.getByAddress(address);
        } catch (IOException impossible) {
            throw new IllegalArgumentException("Dirección IPv4 inválida", impossible);
        }
    }

    private static byte[] parseIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] address = new byte[4];
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
                return null;
            }
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException exception) {
                return null;
            }
            if (octet > 255) {
                return null;
            }
            address[index] = (byte) octet;
        }
        return address;
    }

    private static String normalizedIpv4(byte[] address) {
        return Arrays.stream(new int[]{
                Byte.toUnsignedInt(address[0]), Byte.toUnsignedInt(address[1]),
                Byte.toUnsignedInt(address[2]), Byte.toUnsignedInt(address[3])
        }).mapToObj(Integer::toString).reduce((left, right) -> left + "." + right).orElse("");
    }
}
