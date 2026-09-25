package com.ocptools.external;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExternalStatePolicyTest {
    @Test
    void firstScheduledFailureIsWarning() {
        var result = ExternalStatePolicy.afterScheduledRun(0, "DOWN");
        assertEquals("WARNING", result.serviceStatus());
        assertEquals(1, result.consecutiveFailures());
        assertEquals(ExternalStatePolicy.IncidentAction.OPEN_PENDING, result.incidentAction());
    }

    @Test
    void secondScheduledFailureConfirmsDown() {
        var result = ExternalStatePolicy.afterScheduledRun(1, "DOWN");
        assertEquals("DOWN", result.serviceStatus());
        assertEquals(2, result.consecutiveFailures());
        assertEquals(ExternalStatePolicy.IncidentAction.CONFIRM, result.incidentAction());
    }

    @Test
    void firstSuccessRecoversAndClearsStreak() {
        var result = ExternalStatePolicy.afterScheduledRun(4, "UP");
        assertEquals("UP", result.serviceStatus());
        assertEquals(0, result.consecutiveFailures());
        assertEquals(ExternalStatePolicy.IncidentAction.RECOVER, result.incidentAction());
    }

    @Test
    void informativeWarningDoesNotCreateFailureStreak() {
        var result = ExternalStatePolicy.afterScheduledRun(0, "WARNING");
        assertEquals("WARNING", result.serviceStatus());
        assertEquals(0, result.consecutiveFailures());
        assertEquals(ExternalStatePolicy.IncidentAction.RECOVER, result.incidentAction());
    }
}
