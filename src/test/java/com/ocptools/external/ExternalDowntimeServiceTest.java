package com.ocptools.external;

import com.ocptools.external.ExternalModels.DowntimeSegmentView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
    }
}
