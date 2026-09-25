package com.ocptools.common;

import com.ocptools.domain.ToolStatus;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AuditLogger {
    private static final Logger LOG = Logger.getLogger(AuditLogger.class);

    public void operation(String action, String accessId, String pid, String execId,
                          String environment, ToolStatus result, long durationMs,
                          RequestMetadata metadata) {
        LOG.infof(
                "audit=true user=%s tool=cms action=%s accessId=%s pid=%s execId=%s environment=%s result=%s durationMs=%d correlationId=%s",
                safe(metadata.user()), safe(action), safe(accessId), safe(pid), safe(execId),
                safe(environment), result, durationMs, safe(metadata.correlationId()));
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.replaceAll("[\\r\\n\\t]", "_");
    }
}

