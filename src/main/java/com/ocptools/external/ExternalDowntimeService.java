package com.ocptools.external;

import com.ocptools.external.ExternalModels.AvailabilityScheduleInput;
import com.ocptools.external.ExternalModels.AvailabilityScheduleView;
import com.ocptools.external.ExternalModels.DowntimeSegmentView;
import com.ocptools.external.ExternalModels.DowntimeSummary;
import com.ocptools.external.ExternalModels.DowntimeView;
import com.ocptools.external.ExternalModels.IncidentClassificationInput;
import com.ocptools.external.ExternalModels.IncidentClassificationView;
import com.ocptools.external.ExternalModels.IncidentView;
import com.ocptools.external.ExternalModels.ScheduleExceptionInput;
import com.ocptools.external.ExternalModels.ServiceDowntimeView;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class ExternalDowntimeService {
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Lima");
    private static final List<Integer> DEFAULT_DAYS = List.of(1, 2, 3, 4, 5);
    private static final LocalTime DEFAULT_START = LocalTime.of(8, 0);
    private static final LocalTime DEFAULT_END = LocalTime.of(19, 0);

    @Inject
    @DataSource("inventory")
    javax.sql.DataSource dataSource;

    public DowntimeView downtime(ExternalHistoryRange range, Long serviceId, Long groupId) {
        try (Connection connection = dataSource.getConnection()) {
            List<ServiceRow> services = loadServices(connection, serviceId, groupId);
            Map<Long, ScheduleRow> serviceSchedules = new HashMap<>();
            Map<Long, ScheduleRow> groupSchedules = new HashMap<>();
            loadSchedules(connection, serviceSchedules, groupSchedules);
            Map<Long, List<IncidentRow>> incidents = loadIncidents(connection, range, serviceId, groupId, true);
            Map<Long, List<ClassificationRow>> classifications = loadClassifications(connection, incidents);
            List<ServiceDowntimeView> result = new ArrayList<>();
            Set<Long> incidentIds = new HashSet<>();
            long totalDown = 0;
            long totalJustified = 0;
            for (ServiceRow service : services) {
                ScheduleRow schedule = serviceSchedules.getOrDefault(service.id,
                        groupSchedules.getOrDefault(service.groupId, ScheduleRow.defaultSchedule()));
                List<DowntimeSegmentView> segments = new ArrayList<>();
                for (IncidentRow incident : incidents.getOrDefault(service.id, List.of())) {
                    List<DowntimeSegmentView> incidentSegments = segments(range, schedule, incident,
                            classifications.getOrDefault(incident.id, List.of()));
                    if (!incidentSegments.isEmpty()) incidentIds.add(incident.id);
                    segments.addAll(incidentSegments);
                }
                segments = merge(segments);
                long down = segments.stream().filter(segment -> "UNPLANNED".equals(segment.category()))
                        .mapToLong(DowntimeSegmentView::durationSeconds).sum();
                long justified = segments.stream().filter(segment -> "JUSTIFIED".equals(segment.category()))
                        .mapToLong(DowntimeSegmentView::durationSeconds).sum();
                totalDown += down;
                totalJustified += justified;
                result.add(new ServiceDowntimeView(service.id, service.groupId, service.groupName, service.name,
                        service.environment, service.systemName, schedule.zone.getId(), schedule.label(), down,
                        justified, segments));
            }
            return new DowntimeView(Instant.now(), range.from(), range.to(), serviceId, groupId,
                    new DowntimeSummary(incidentIds.size(), totalDown, totalJustified), result);
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public List<IncidentView> incidents(ExternalHistoryRange range, Long serviceId, Long groupId) {
        try (Connection connection = dataSource.getConnection()) {
            Map<Long, List<IncidentRow>> grouped = loadIncidents(connection, range, serviceId, groupId, false);
            Map<Long, List<ClassificationRow>> classifications = loadClassifications(connection, grouped);
            return grouped.values().stream().flatMap(List::stream)
                    .sorted(Comparator.comparing(IncidentRow::openedAt).reversed())
                    .map(row -> new IncidentView(row.id, row.serviceId, row.serviceName, row.environment,
                            row.systemName, row.openedAt, row.confirmedAt, row.recoveredAt, row.status,
                            classifications.getOrDefault(row.id, List.of()).stream()
                                    .map(ClassificationRow::view).toList()))
                    .toList();
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public List<AvailabilityScheduleView> schedules() {
        try (Connection connection = dataSource.getConnection()) {
            Map<Long, ScheduleRow> serviceSchedules = new HashMap<>();
            Map<Long, ScheduleRow> groupSchedules = new HashMap<>();
            loadSchedules(connection, serviceSchedules, groupSchedules);
            return java.util.stream.Stream.concat(groupSchedules.values().stream(), serviceSchedules.values().stream())
                    .sorted(Comparator.comparing(ScheduleRow::scopeName, String.CASE_INSENSITIVE_ORDER))
                    .map(ScheduleRow::view).toList();
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public long saveSchedule(Long id, AvailabilityScheduleInput input) {
        validateSchedule(input);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long scheduleId = id == null ? insertSchedule(connection, input) : updateSchedule(connection, id, input);
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM external_schedule_exception WHERE schedule_id=?")) {
                    statement.setLong(1, scheduleId);
                    statement.executeUpdate();
                }
                for (ScheduleExceptionInput exception : input.exceptions() == null ? List.<ScheduleExceptionInput>of() : input.exceptions()) {
                    validateException(exception);
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO external_schedule_exception(schedule_id, exception_date, available,
                                start_time, end_time, description) VALUES (?, ?, ?, ?, ?, ?)
                            """)) {
                        statement.setLong(1, scheduleId);
                        statement.setDate(2, Date.valueOf(exception.date()));
                        statement.setBoolean(3, exception.available());
                        setTime(statement, 4, exception.startTime());
                        setTime(statement, 5, exception.endTime());
                        statement.setString(6, blank(exception.description()));
                        statement.executeUpdate();
                    }
                }
                connection.commit();
                return scheduleId;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public void archiveSchedule(long id) {
        execute("UPDATE external_availability_schedule SET archived_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP "
                + "WHERE id=? AND archived_at IS NULL", id, "Horario no encontrado");
    }

    public long classify(long incidentId, IncidentClassificationInput input) {
        validateClassification(input);
        try (Connection connection = dataSource.getConnection()) {
            IncidentBounds bounds = incidentBounds(connection, incidentId);
            if (input.from().isBefore(bounds.openedAt)
                    || (bounds.recoveredAt != null && input.to().isAfter(bounds.recoveredAt))) {
                throw new IllegalArgumentException("El intervalo debe estar contenido dentro del incidente");
            }
            try (PreparedStatement overlap = connection.prepareStatement("""
                    SELECT COUNT(*) FROM external_incident_classification
                    WHERE incident_id=? AND archived_at IS NULL AND from_at < ? AND to_at > ?
                    """)) {
                overlap.setLong(1, incidentId);
                overlap.setTimestamp(2, Timestamp.from(input.to()));
                overlap.setTimestamp(3, Timestamp.from(input.from()));
                try (ResultSet rows = overlap.executeQuery()) {
                    rows.next();
                    if (rows.getLong(1) > 0) throw new IllegalArgumentException("El intervalo ya está clasificado");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO external_incident_classification(incident_id, classification_type, from_at, to_at,
                        ticket_reference, requested_by, notes, confirmed_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, incidentId);
                statement.setString(2, input.classificationType().trim().toUpperCase());
                statement.setTimestamp(3, Timestamp.from(input.from()));
                statement.setTimestamp(4, Timestamp.from(input.to()));
                statement.setString(5, blank(input.ticketReference()));
                statement.setString(6, blank(input.requestedBy()));
                statement.setString(7, blank(input.notes()));
                statement.setString(8, input.confirmedBy().trim());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("No se obtuvo el identificador generado");
                    return keys.getLong(1);
                }
            }
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    public void archiveClassification(long id) {
        execute("UPDATE external_incident_classification SET archived_at=CURRENT_TIMESTAMP "
                + "WHERE id=? AND archived_at IS NULL", id, "Clasificación no encontrada");
    }

    private void execute(String sql, long id, String notFound) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            if (statement.executeUpdate() == 0) throw new IllegalArgumentException(notFound);
        } catch (SQLException exception) {
            throw unavailable(exception);
        }
    }

    static List<DowntimeSegmentView> segments(ExternalHistoryRange range, ScheduleRow schedule,
                                              IncidentRow incident, List<ClassificationRow> classifications) {
        Instant incidentStart = later(range.from(), incident.openedAt);
        Instant incidentEnd = earlier(range.to(), incident.recoveredAt == null ? range.to() : incident.recoveredAt);
        if (!incidentStart.isBefore(incidentEnd)) return List.of();
        List<DowntimeSegmentView> result = new ArrayList<>();
        LocalDate first = incidentStart.atZone(schedule.zone).toLocalDate();
        LocalDate last = incidentEnd.minusNanos(1).atZone(schedule.zone).toLocalDate();
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            TimeWindow window = schedule.window(date);
            if (window == null) continue;
            Instant workStart = later(incidentStart, date.atTime(window.start).atZone(schedule.zone).toInstant());
            Instant workEnd = earlier(incidentEnd, date.atTime(window.end).atZone(schedule.zone).toInstant());
            if (!workStart.isBefore(workEnd)) continue;
            List<Instant> boundaries = new ArrayList<>(List.of(workStart, workEnd));
            for (ClassificationRow classification : classifications) {
                if (classification.to.isAfter(workStart) && classification.from.isBefore(workEnd)) {
                    boundaries.add(later(workStart, classification.from));
                    boundaries.add(earlier(workEnd, classification.to));
                }
            }
            boundaries = boundaries.stream().distinct().sorted().toList();
            for (int index = 0; index < boundaries.size() - 1; index++) {
                Instant from = boundaries.get(index);
                Instant to = boundaries.get(index + 1);
                if (!from.isBefore(to)) continue;
                Instant midpoint = from.plusMillis(Math.max(1, Duration.between(from, to).toMillis() / 2));
                ClassificationRow classification = classifications.stream()
                        .filter(item -> !midpoint.isBefore(item.from) && midpoint.isBefore(item.to))
                        .findFirst().orElse(null);
                boolean justified = classification != null && classification.justified();
                result.add(new DowntimeSegmentView(incident.id, from, to, Duration.between(from, to).getSeconds(),
                        justified ? "JUSTIFIED" : "UNPLANNED", incident.status,
                        classification == null ? null : classification.id,
                        classification == null ? null : classification.ticketReference,
                        classification == null ? null : classification.requestedBy,
                        classification == null ? null : classification.notes));
            }
        }
        return result;
    }

    private static List<DowntimeSegmentView> merge(List<DowntimeSegmentView> source) {
        List<DowntimeSegmentView> sorted = source.stream().sorted(Comparator.comparing(DowntimeSegmentView::from)).toList();
        List<DowntimeSegmentView> result = new ArrayList<>();
        for (DowntimeSegmentView current : sorted) {
            if (!result.isEmpty()) {
                DowntimeSegmentView previous = result.get(result.size() - 1);
                if (previous.incidentId() == current.incidentId() && previous.to().equals(current.from())
                        && previous.category().equals(current.category())
                        && java.util.Objects.equals(previous.classificationId(), current.classificationId())) {
                    result.set(result.size() - 1, new DowntimeSegmentView(previous.incidentId(), previous.from(),
                            current.to(), previous.durationSeconds() + current.durationSeconds(), previous.category(),
                            previous.incidentStatus(), previous.classificationId(), previous.ticketReference(),
                            previous.requestedBy(), previous.notes()));
                    continue;
                }
            }
            result.add(current);
        }
        return result;
    }

    private static List<ServiceRow> loadServices(Connection connection, Long serviceId, Long groupId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT s.id, s.group_id, g.name group_name, s.name, s.environment, s.system_name
                FROM external_service s LEFT JOIN external_group g ON g.id=s.group_id
                WHERE s.archived_at IS NULL AND s.id=COALESCE(?, s.id)
                  AND COALESCE(s.group_id, 0)=COALESCE(?, COALESCE(s.group_id, 0))
                ORDER BY s.name
                """)) {
            setLong(statement, 1, serviceId);
            setLong(statement, 2, groupId);
            try (ResultSet rows = statement.executeQuery()) {
                List<ServiceRow> result = new ArrayList<>();
                while (rows.next()) result.add(new ServiceRow(rows.getLong("id"), nullableLong(rows, "group_id"),
                        rows.getString("group_name"), rows.getString("name"), rows.getString("environment"),
                        rows.getString("system_name")));
                return result;
            }
        }
    }

    private static Map<Long, List<IncidentRow>> loadIncidents(Connection connection, ExternalHistoryRange range,
                                                               Long serviceId, Long groupId, boolean confirmedOnly)
            throws SQLException {
        String confirmed = confirmedOnly ? " AND i.confirmed_run_id IS NOT NULL" : "";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT i.id, i.service_id, s.name, s.environment, s.system_name, i.opened_at,
                       i.confirmed_at, i.recovered_at, i.status
                FROM external_incident i JOIN external_service s ON s.id=i.service_id
                WHERE s.archived_at IS NULL AND i.opened_at < ? AND COALESCE(i.recovered_at, ?) > ?
                  AND s.id=COALESCE(?, s.id)
                  AND COALESCE(s.group_id, 0)=COALESCE(?, COALESCE(s.group_id, 0))
                """ + confirmed + " ORDER BY i.opened_at")) {
            statement.setTimestamp(1, Timestamp.from(range.to()));
            statement.setTimestamp(2, Timestamp.from(range.to()));
            statement.setTimestamp(3, Timestamp.from(range.from()));
            setLong(statement, 4, serviceId);
            setLong(statement, 5, groupId);
            try (ResultSet rows = statement.executeQuery()) {
                Map<Long, List<IncidentRow>> result = new LinkedHashMap<>();
                while (rows.next()) {
                    IncidentRow row = new IncidentRow(rows.getLong("id"), rows.getLong("service_id"),
                            rows.getString("name"), rows.getString("environment"), rows.getString("system_name"),
                            instant(rows, "opened_at"), instant(rows, "confirmed_at"),
                            instant(rows, "recovered_at"), rows.getString("status"));
                    result.computeIfAbsent(row.serviceId, ignored -> new ArrayList<>()).add(row);
                }
                return result;
            }
        }
    }

    private static Map<Long, List<ClassificationRow>> loadClassifications(Connection connection,
                                                                           Map<Long, List<IncidentRow>> incidents)
            throws SQLException {
        Set<Long> ids = new HashSet<>();
        incidents.values().forEach(rows -> rows.forEach(row -> ids.add(row.id)));
        if (ids.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, incident_id, classification_type, from_at, to_at, ticket_reference,
                       requested_by, notes, confirmed_by, created_at
                FROM external_incident_classification WHERE archived_at IS NULL AND incident_id IN (%s)
                ORDER BY from_at
                """.formatted(placeholders))) {
            int index = 1;
            for (Long id : ids) statement.setLong(index++, id);
            try (ResultSet rows = statement.executeQuery()) {
                Map<Long, List<ClassificationRow>> result = new HashMap<>();
                while (rows.next()) {
                    ClassificationRow row = new ClassificationRow(rows.getLong("id"), rows.getLong("incident_id"),
                            rows.getString("classification_type"), instant(rows, "from_at"), instant(rows, "to_at"),
                            rows.getString("ticket_reference"), rows.getString("requested_by"), rows.getString("notes"),
                            rows.getString("confirmed_by"), instant(rows, "created_at"));
                    result.computeIfAbsent(row.incidentId, ignored -> new ArrayList<>()).add(row);
                }
                return result;
            }
        }
    }

    private static void loadSchedules(Connection connection, Map<Long, ScheduleRow> serviceSchedules,
                                      Map<Long, ScheduleRow> groupSchedules) throws SQLException {
        Map<Long, ScheduleRowBuilder> builders = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.id, a.group_id, a.service_id, COALESCE(s.name, g.name) scope_name, a.name,
                       a.timezone, a.working_days, a.start_time, a.end_time
                FROM external_availability_schedule a
                LEFT JOIN external_service s ON s.id=a.service_id
                LEFT JOIN external_group g ON g.id=a.group_id
                WHERE a.archived_at IS NULL ORDER BY a.id
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                long id = rows.getLong("id");
                builders.put(id, new ScheduleRowBuilder(id, nullableLong(rows, "group_id"),
                        nullableLong(rows, "service_id"), rows.getString("scope_name"), rows.getString("name"),
                        ZoneId.of(rows.getString("timezone")), parseDays(rows.getString("working_days")),
                        rows.getTime("start_time").toLocalTime(), rows.getTime("end_time").toLocalTime()));
            }
        }
        if (!builders.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(builders.size(), "?"));
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT schedule_id, exception_date, available, start_time, end_time, description
                    FROM external_schedule_exception WHERE schedule_id IN (%s) ORDER BY exception_date
                    """.formatted(placeholders))) {
                int index = 1;
                for (Long id : builders.keySet()) statement.setLong(index++, id);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) builders.get(rows.getLong("schedule_id")).exceptions.add(
                            new ScheduleExceptionInput(rows.getDate("exception_date").toLocalDate(),
                                    rows.getBoolean("available"), localTime(rows, "start_time"),
                                    localTime(rows, "end_time"), rows.getString("description")));
                }
            }
        }
        for (ScheduleRowBuilder builder : builders.values()) {
            ScheduleRow schedule = builder.build();
            if (schedule.serviceId != null) serviceSchedules.put(schedule.serviceId, schedule);
            else groupSchedules.put(schedule.groupId, schedule);
        }
    }

    private static long insertSchedule(Connection connection, AvailabilityScheduleInput input) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO external_availability_schedule(group_id, service_id, name, timezone, working_days,
                    start_time, end_time) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            bindSchedule(statement, input);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("No se obtuvo el identificador generado");
                return keys.getLong(1);
            }
        }
    }

    private static long updateSchedule(Connection connection, long id, AvailabilityScheduleInput input) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE external_availability_schedule SET group_id=?, service_id=?, name=?, timezone=?,
                    working_days=?, start_time=?, end_time=?, updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND archived_at IS NULL
                """)) {
            bindSchedule(statement, input);
            statement.setLong(8, id);
            if (statement.executeUpdate() == 0) throw new IllegalArgumentException("Horario no encontrado");
            return id;
        }
    }

    private static void bindSchedule(PreparedStatement statement, AvailabilityScheduleInput input) throws SQLException {
        setLong(statement, 1, input.groupId());
        setLong(statement, 2, input.serviceId());
        statement.setString(3, input.name().trim());
        statement.setString(4, input.timezone().trim());
        statement.setString(5, input.workingDays().stream().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",")));
        statement.setTime(6, Time.valueOf(input.startTime()));
        statement.setTime(7, Time.valueOf(input.endTime()));
    }

    private static IncidentBounds incidentBounds(Connection connection, long incidentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT opened_at, recovered_at FROM external_incident WHERE id=?")) {
            statement.setLong(1, incidentId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Incidente no encontrado");
                return new IncidentBounds(instant(rows, "opened_at"), instant(rows, "recovered_at"));
            }
        }
    }

    private static void validateSchedule(AvailabilityScheduleInput input) {
        if (input == null || (input.groupId() == null) == (input.serviceId() == null))
            throw new IllegalArgumentException("El horario debe pertenecer a un grupo o a un servicio");
        if (blank(input.name()) == null) throw new IllegalArgumentException("Nombre de horario requerido");
        try { ZoneId.of(input.timezone()); } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Zona horaria inválida");
        }
        if (input.workingDays() == null || input.workingDays().isEmpty()
                || input.workingDays().stream().anyMatch(day -> day == null || day < 1 || day > 7))
            throw new IllegalArgumentException("Seleccione al menos un día laboral válido");
        if (input.startTime() == null || input.endTime() == null || !input.startTime().isBefore(input.endTime()))
            throw new IllegalArgumentException("El horario inicial debe ser anterior al final");
    }

    private static void validateException(ScheduleExceptionInput input) {
        if (input == null || input.date() == null) throw new IllegalArgumentException("Fecha de excepción requerida");
        if (input.available() && (input.startTime() == null || input.endTime() == null
                || !input.startTime().isBefore(input.endTime())))
            throw new IllegalArgumentException("La excepción laboral requiere un intervalo válido");
    }

    private static void validateClassification(IncidentClassificationInput input) {
        if (input == null || input.classificationType() == null
                || !Set.of("UNPLANNED", "REQUESTED_RESTART", "PLANNED_WORK")
                .contains(input.classificationType().trim().toUpperCase()))
            throw new IllegalArgumentException("Clasificación inválida");
        if (input.from() == null || input.to() == null || !input.from().isBefore(input.to()))
            throw new IllegalArgumentException("El intervalo de clasificación no es válido");
        if (blank(input.confirmedBy()) == null) throw new IllegalArgumentException("Responsable de confirmación requerido");
    }

    private static List<Integer> parseDays(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim).map(Integer::valueOf).toList();
    }

    private static LocalTime localTime(ResultSet rows, String column) throws SQLException {
        Time value = rows.getTime(column);
        return value == null ? null : value.toLocalTime();
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT); else statement.setLong(index, value);
    }

    private static void setTime(PreparedStatement statement, int index, LocalTime value) throws SQLException {
        if (value == null) statement.setNull(index, Types.TIME); else statement.setTime(index, Time.valueOf(value));
    }

    private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static Instant later(Instant first, Instant second) { return first.isAfter(second) ? first : second; }
    private static Instant earlier(Instant first, Instant second) { return first.isBefore(second) ? first : second; }
    private static RuntimeException unavailable(SQLException exception) {
        return new ExternalUnavailableException("El catálogo de servicios externos no está disponible", exception);
    }

    private record ServiceRow(long id, Long groupId, String groupName, String name, String environment,
                              String systemName) { }
    private record IncidentBounds(Instant openedAt, Instant recoveredAt) { }
    record IncidentRow(long id, long serviceId, String serviceName, String environment, String systemName,
                       Instant openedAt, Instant confirmedAt, Instant recoveredAt, String status) { }
    private record TimeWindow(LocalTime start, LocalTime end) { }

    record ClassificationRow(long id, long incidentId, String type, Instant from, Instant to,
                             String ticketReference, String requestedBy, String notes,
                             String confirmedBy, Instant createdAt) {
        boolean justified() { return "REQUESTED_RESTART".equals(type) || "PLANNED_WORK".equals(type); }
        IncidentClassificationView view() {
            return new IncidentClassificationView(id, type, from, to, ticketReference, requestedBy, notes,
                    confirmedBy, createdAt);
        }
    }

    record ScheduleRow(long id, Long groupId, Long serviceId, String scopeName, String name, ZoneId zone,
                       List<Integer> workingDays, LocalTime start, LocalTime end,
                       List<ScheduleExceptionInput> exceptions) {
        static ScheduleRow defaultSchedule() {
            return new ScheduleRow(0, null, null, "Predeterminado", "Horario laboral predeterminado", DEFAULT_ZONE,
                    DEFAULT_DAYS, DEFAULT_START, DEFAULT_END, List.of());
        }
        String label() { return start + "–" + end + " · " + zone.getId(); }
        TimeWindow window(LocalDate date) {
            ScheduleExceptionInput exception = exceptions.stream().filter(item -> date.equals(item.date())).findFirst().orElse(null);
            if (exception != null) return exception.available() ? new TimeWindow(exception.startTime(), exception.endTime()) : null;
            return workingDays.contains(date.getDayOfWeek().getValue()) ? new TimeWindow(start, end) : null;
        }
        AvailabilityScheduleView view() {
            return new AvailabilityScheduleView(id, groupId, serviceId, scopeName, name, zone.getId(), workingDays,
                    start, end, exceptions);
        }
    }

    private static final class ScheduleRowBuilder {
        private final long id; private final Long groupId; private final Long serviceId; private final String scopeName;
        private final String name; private final ZoneId zone; private final List<Integer> workingDays;
        private final LocalTime start; private final LocalTime end;
        private final List<ScheduleExceptionInput> exceptions = new ArrayList<>();
        private ScheduleRowBuilder(long id, Long groupId, Long serviceId, String scopeName, String name, ZoneId zone,
                                   List<Integer> workingDays, LocalTime start, LocalTime end) {
            this.id=id; this.groupId=groupId; this.serviceId=serviceId; this.scopeName=scopeName; this.name=name;
            this.zone=zone; this.workingDays=workingDays; this.start=start; this.end=end;
        }
        private ScheduleRow build() { return new ScheduleRow(id, groupId, serviceId, scopeName, name, zone,
                workingDays, start, end, List.copyOf(exceptions)); }
    }
}
