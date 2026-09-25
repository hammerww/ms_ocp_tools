package com.ocptools.api;

import com.ocptools.tcpcheck.TcpCheckStatus;

import java.time.Instant;

public record TcpCheckResponse(
        TcpCheckStatus status,
        boolean connected,
        String ip,
        int port,
        long timeoutSeconds,
        long durationMs,
        Instant checkedAt,
        String correlationId,
        String message
) {
}
