package com.ocptools.external;

public final class ExternalStatePolicy {
    private ExternalStatePolicy() {
    }

    public static Transition afterScheduledRun(int previousFailures, String runStatus) {
        boolean failed = "DOWN".equals(runStatus) || "ERROR".equals(runStatus);
        if (failed) {
            int failures = Math.max(0, previousFailures) + 1;
            return new Transition(failures == 1 ? "WARNING" : "DOWN", failures,
                    failures == 1 ? IncidentAction.OPEN_PENDING : IncidentAction.CONFIRM);
        }
        return new Transition("WARNING".equals(runStatus) ? "WARNING" : "UP", 0,
                IncidentAction.RECOVER);
    }

    public record Transition(String serviceStatus, int consecutiveFailures, IncidentAction incidentAction) {
    }

    public enum IncidentAction { OPEN_PENDING, CONFIRM, RECOVER }
}
