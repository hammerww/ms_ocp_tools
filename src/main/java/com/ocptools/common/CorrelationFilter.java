package com.ocptools.common;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.util.UUID;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class CorrelationFilter implements ContainerRequestFilter, ContainerResponseFilter {
    public static final String CORRELATION_HEADER = "X-Correlation-ID";

    @Inject
    RequestMetadata metadata;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String correlationId = clean(requestContext.getHeaderString(CORRELATION_HEADER));
        metadata.correlationId(correlationId == null ? UUID.randomUUID().toString() : correlationId);

        String user = clean(requestContext.getHeaderString("X-Forwarded-User"));
        if (user == null) {
            user = clean(requestContext.getHeaderString("X-User"));
        }
        metadata.user(user == null ? "anonymous" : user);
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        responseContext.getHeaders().putSingle(CORRELATION_HEADER, metadata.correlationId());
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value.trim();
        return cleaned.length() <= 128 ? cleaned : cleaned.substring(0, 128);
    }
}

