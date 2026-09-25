package com.ocptools.api;

import com.ocptools.domain.ToolStatus;

public record ToolResponse(
        String accessId,
        ToolStatus status,
        String message,
        boolean cmsFound,
        String interactionDate,
        String externalId,
        String pid,
        String execId,
        String requestedEnvironment,
        String foundEnvironment,
        String foundSubsystem,
        boolean canRegister,
        boolean registrationConfirmed,
        String correlationId
) {
    public ToolResponse withMessage(String newMessage) {
        return new ToolResponse(accessId, status, newMessage, cmsFound, interactionDate,
                externalId, pid, execId, requestedEnvironment, foundEnvironment,
                foundSubsystem, canRegister, registrationConfirmed, correlationId);
    }
}
