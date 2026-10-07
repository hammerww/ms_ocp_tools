package com.ocptools.external;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

public final class ExternalModels {
    private ExternalModels() {
    }

    public record Snapshot(
            Instant generatedAt,
            Summary summary,
            List<ServiceView> services,
            List<GroupView> groups,
            MonitorSettings monitor
    ) {
    }

    public record MonitorSettings(
            boolean enabled,
            boolean tlsVerify,
            long intervalSeconds,
            long initialDelaySeconds,
            int parallelism
    ) {
    }

    public record Summary(
            long total,
            long up,
            long warning,
            long down,
            long unknown,
            Double availability24h,
            Double availability7d,
            Long p95DurationMs,
            long openIncidents
    ) {
    }

    public record ServiceView(
            long id,
            Long groupId,
            String name,
            String environment,
            String systemName,
            String description,
            String status,
            int consecutiveFailures,
            Instant lastScheduledAt,
            Instant lastSuccessAt,
            List<ProbeView> probes,
            RunView lastRun
    ) {
    }

    public record ProbeView(
            long id,
            String name,
            String probeType,
            boolean mandatory,
            int displayOrder,
            Long dependsOnProbeId,
            Long credentialId,
            String credentialName,
            String host,
            Integer port,
            String url,
            String httpMethod,
            String requestHeaders,
            String requestBody,
            String expectedStatuses,
            String expectedBody,
            String tokenJsonField,
            String authType,
            String authHeader,
            String dbEngine,
            String dbName,
            String dbService,
            String validationQuery,
            int timeoutMs,
            ProbeResultView lastResult
    ) {
    }

    public record RunView(
            long id,
            boolean manual,
            Instant startedAt,
            Instant finishedAt,
            String status,
            Long durationMs,
            List<ProbeResultView> results
    ) {
    }

    public record ProbeResultView(
            Long probeId,
            String probeName,
            String probeType,
            boolean mandatory,
            String status,
            String phase,
            String message,
            long durationMs,
            Integer responseCode,
            Instant checkedAt
    ) {
    }

    public record HistoryView(
            Instant generatedAt,
            Integer hours,
            Instant from,
            Instant to,
            long bucketSeconds,
            Long serviceId,
            HistorySummary summary,
            List<ServiceHistoryView> services,
            List<HistoryPointView> timeline
    ) {
    }

    public record HistorySummary(
            long executions,
            Double availability,
            Long averageDurationMs,
            Long p95DurationMs,
            long warnings,
            long downs
    ) {
    }

    public record ServiceHistoryView(
            long serviceId,
            String serviceName,
            String environment,
            String systemName,
            long executions,
            Double availability,
            Long averageDurationMs,
            Long p95DurationMs,
            long warnings,
            long downs,
            String lastStatus,
            Instant lastCheckedAt
    ) {
    }

    public record HistoryPointView(
            long serviceId,
            String serviceName,
            Instant bucket,
            String status,
            Long averageDurationMs
    ) {
    }

    public record HistoryExecutionView(
            long runId,
            long serviceId,
            String serviceName,
            String environment,
            String systemName,
            Instant startedAt,
            Instant finishedAt,
            String status,
            Long durationMs,
            String triggerSource
    ) {
    }

    public record ArchivedServiceView(
            long id,
            Long groupId,
            String name,
            String environment,
            String systemName,
            Instant archivedAt
    ) {
    }

    public record AvailabilityScheduleInput(
            Long groupId,
            Long serviceId,
            String name,
            String timezone,
            List<Integer> workingDays,
            LocalTime startTime,
            LocalTime endTime,
            List<ScheduleExceptionInput> exceptions
    ) {
    }

    public record ScheduleExceptionInput(
            LocalDate date,
            boolean available,
            LocalTime startTime,
            LocalTime endTime,
            String description
    ) {
    }

    public record AvailabilityScheduleView(
            long id,
            Long groupId,
            Long serviceId,
            String scopeName,
            String name,
            String timezone,
            List<Integer> workingDays,
            LocalTime startTime,
            LocalTime endTime,
            List<ScheduleExceptionInput> exceptions
    ) {
    }

    public record IncidentClassificationInput(
            String classificationType,
            Instant from,
            Instant to,
            String ticketReference,
            String requestedBy,
            String notes,
            String confirmedBy
    ) {
    }

    public record IncidentClassificationView(
            long id,
            String classificationType,
            Instant from,
            Instant to,
            String ticketReference,
            String requestedBy,
            String notes,
            String confirmedBy,
            Instant createdAt
    ) {
    }

    public record IncidentView(
            long id,
            long serviceId,
            String serviceName,
            String environment,
            String systemName,
            Instant openedAt,
            Instant confirmedAt,
            Instant recoveredAt,
            String status,
            List<IncidentClassificationView> classifications
    ) {
    }

    public record DowntimeView(
            Instant generatedAt,
            Instant from,
            Instant to,
            Long serviceId,
            Long groupId,
            DowntimeSummary summary,
            List<ServiceDowntimeView> services
    ) {
    }

    public record DowntimeSummary(
            long incidents,
            long totalDownSeconds,
            long justifiedSeconds
    ) {
    }

    public record ServiceDowntimeView(
            long serviceId,
            Long groupId,
            String groupName,
            String serviceName,
            String environment,
            String systemName,
            String timezone,
            String scheduleLabel,
            List<AvailabilityWindowView> workingWindows,
            long totalDownSeconds,
            long justifiedSeconds,
            List<DowntimeSegmentView> segments
    ) {
    }

    public record AvailabilityWindowView(
            Instant from,
            Instant to
    ) {
    }

    public record DowntimeSegmentView(
            long incidentId,
            Instant from,
            Instant to,
            long durationSeconds,
            String category,
            String incidentStatus,
            Long classificationId,
            String ticketReference,
            String requestedBy,
            String notes
    ) {
    }

    public record ServiceInput(
            String name,
            String environment,
            String systemName,
            String description,
            Long groupId,
            List<ProbeInput> probes
    ) {
    }

    public record ProbeInput(
            Long clientId,
            Long dependsOnClientId,
            Long credentialId,
            String name,
            String probeType,
            Boolean mandatory,
            Integer displayOrder,
            String host,
            Integer port,
            String url,
            String httpMethod,
            String requestHeaders,
            String requestBody,
            String expectedStatuses,
            String expectedBody,
            String tokenJsonField,
            String authType,
            String authHeader,
            String dbEngine,
            String dbName,
            String dbService,
            String validationQuery,
            Integer timeoutMs
    ) {
    }

    public record CredentialInput(
            String name,
            String environment,
            String credentialType,
            String systemName,
            String username,
            String password,
            String token,
            String clientId,
            String clientSecret,
            Map<String, String> extra
    ) {
    }

    public record CredentialView(
            long id,
            String name,
            String environment,
            String credentialType,
            String systemName,
            String username,
            boolean hasPassword,
            boolean hasToken,
            boolean hasClientSecret,
            Map<String, String> extra
    ) {
    }

    public record CredentialSecret(
            String username,
            String password,
            String token,
            String clientId,
            String clientSecret,
            Map<String, String> extra
    ) {
    }

    public record GroupInput(Long parentId, String name, Integer displayOrder) {
    }

    public record GroupView(long id, Long parentId, String name, int displayOrder) {
    }

    public record UnlockRequest(String masterPassword) {
    }

    public record UnlockResponse(String token, Instant expiresAt) {
    }

    public record ImportRequest(
            List<ServiceInput> services,
            List<CredentialInput> credentials,
            List<HistoricalRunInput> history
    ) {
    }

    public record HistoricalRunInput(
            String serviceName,
            String environment,
            Instant startedAt,
            String status,
            Long durationMs,
            List<HistoricalResultInput> results
    ) {
    }

    public record HistoricalResultInput(
            String probeName,
            String probeType,
            boolean mandatory,
            String status,
            String phase,
            String message,
            long durationMs,
            Integer responseCode
    ) {
    }
}
