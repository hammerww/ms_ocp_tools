package com.ocptools.external;

import com.ocptools.external.ExternalModels.DowntimeSegmentView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExternalDowntimeServiceTest {
    @Test
    void clipsToBusinessHoursAndSeparatesJustifiedTime() {
        Instant from = Instant.parse("2026-10-05T05:00:00Z");
        Instant to = Instant.parse("2026-10-06T05:00:00Z");
        var incident = new ExternalDowntimeService.IncidentRow(9, 3, "Servicio", "Testing", "Sistema",
                Instant.parse("2026-10-05T12:00:00Z"), Instant.parse("2026-10-05T12:10:00Z"),
                Instant.parse("2026-10-06T01:00:00Z"), "RECOVERED");
        var classification = new ExternalDowntimeService.ClassificationRow(4, 9, "REQUESTED_RESTART",
                Instant.parse("2026-10-05T15:00:00Z"), Instant.parse("2026-10-05T16:15:00Z"),
                "CHG-10", "Operaciones", "Reinicio", "Admin", Instant.parse("2026-10-05T17:00:00Z"));

        List<DowntimeSegmentView> result = ExternalDowntimeService.segments(
                ExternalHistoryRange.custom(from, to), ExternalDowntimeService.ScheduleRow.defaultSchedule(),
                incident, List.of(classification));

        long justified = result.stream().filter(item -> "JUSTIFIED".equals(item.category()))
                .mapToLong(DowntimeSegmentView::durationSeconds).sum();
        long unplanned = result.stream().filter(item -> "UNPLANNED".equals(item.category()))
                .mapToLong(DowntimeSegmentView::durationSeconds).sum();
        assertEquals(4_500, justified);
        assertEquals(35_100, unplanned);
    }

    @Test
    void excludesWeekendFromDowntime() {
        Instant from = Instant.parse("2026-10-04T05:00:00Z");
        Instant to = Instant.parse("2026-10-05T05:00:00Z");
        var incident = new ExternalDowntimeService.IncidentRow(10, 3, "Servicio", "Testing", "Sistema",
                from, from.plusSeconds(600), to, "RECOVERED");

        List<DowntimeSegmentView> result = ExternalDowntimeService.segments(
                ExternalHistoryRange.custom(from, to), ExternalDowntimeService.ScheduleRow.defaultSchedule(),
                incident, List.of());

        assertEquals(List.of(), result);
        assertEquals(List.of(), ExternalDowntimeService.windows(
                ExternalHistoryRange.custom(from, to), ExternalDowntimeService.ScheduleRow.defaultSchedule()));
    }

    @Test
    void includesWeekendWhenScheduleExplicitlyEnablesIt() {
        Instant from = Instant.parse("2026-10-03T05:00:00Z");
        Instant to = Instant.parse("2026-10-04T05:00:00Z");
        var incident = new ExternalDowntimeService.IncidentRow(11, 3, "Servicio", "Testing", "Sistema",
                from, from.plusSeconds(600), to, "RECOVERED");
        var schedule = new ExternalDowntimeService.ScheduleRow(7, 2L, null, "Grupo", "Horario extendido",
                ZoneId.of("America/Lima"), List.of(1, 2, 3, 4, 5, 6), LocalTime.of(8, 0),
                LocalTime.of(19, 0), List.of());

        List<DowntimeSegmentView> result = ExternalDowntimeService.segments(
                ExternalHistoryRange.custom(from, to), schedule, incident, List.of());

        assertEquals(39_600, result.stream().mapToLong(DowntimeSegmentView::durationSeconds).sum());
        assertEquals(1, ExternalDowntimeService.windows(
                ExternalHistoryRange.custom(from, to), schedule).size());
        assertEquals("L,M,X,J,V,S · 08:00–19:00 · America/Lima", schedule.label());
    }

    @Test
    void workingWindowsHonorWeekendExceptions() {
        Instant from = Instant.parse("2026-10-03T05:00:00Z");
        Instant to = Instant.parse("2026-10-04T05:00:00Z");
        var exception = new ExternalModels.ScheduleExceptionInput(LocalDate.of(2026, 10, 3), true,
                LocalTime.of(9, 0), LocalTime.of(12, 0), "Ventana especial");
        var schedule = new ExternalDowntimeService.ScheduleRow(8, 2L, null, "Grupo", "Horario laboral",
                ZoneId.of("America/Lima"), List.of(1, 2, 3, 4, 5), LocalTime.of(8, 0),
                LocalTime.of(19, 0), List.of(exception));

        var windows = ExternalDowntimeService.windows(ExternalHistoryRange.custom(from, to), schedule);

        assertEquals(1, windows.size());
        assertEquals(Instant.parse("2026-10-03T14:00:00Z"), windows.get(0).from());
        assertEquals(Instant.parse("2026-10-03T17:00:00Z"), windows.get(0).to());
    }
}
