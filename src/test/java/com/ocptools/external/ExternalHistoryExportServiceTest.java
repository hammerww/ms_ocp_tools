package com.ocptools.external;

import com.ocptools.external.ExternalModels.HistoryExecutionView;
import com.ocptools.external.ExternalModels.HistorySummary;
import com.ocptools.external.ExternalModels.HistoryView;
import com.ocptools.external.ExternalModels.ServiceHistoryView;
import com.ocptools.external.ExternalModels.DowntimeSummary;
import com.ocptools.external.ExternalModels.DowntimeSegmentView;
import com.ocptools.external.ExternalModels.DowntimeView;
import com.ocptools.external.ExternalModels.ServiceDowntimeView;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalHistoryExportServiceTest {
    @Test
    void exportsSensingAndDowntimeFilesInOneZip() throws Exception {
        Instant from = Instant.parse("2026-09-01T05:00:00Z");
        Instant to = Instant.parse("2026-09-02T05:00:00Z");
        ExternalHistoryRange range = ExternalHistoryRange.custom(from, to);
        ServiceHistoryView service = new ServiceHistoryView(7, "Equivalencias", "Testing", "EQV", 2,
                50.0, 150L, 200L, 0, 1, "DOWN", to.minusSeconds(60));
        HistoryView history = new HistoryView(to, null, from, to, range.bucketSeconds(), null,
                new HistorySummary(2, 50.0, 150L, 200L, 0, 1), List.of(service), List.of());
        HistoryExecutionView execution = new HistoryExecutionView(21, 7, "Equivalencias", "Testing", "EQV",
                from.plusSeconds(60), from.plusSeconds(61), "UP", 1_000L, "SCHEDULER");

        ExternalHistoryExportService exporter = new ExternalHistoryExportService();
        exporter.repository = new StubRepository(history, List.of(execution));
        var segment = new DowntimeSegmentView(31, from.plusSeconds(3_600), from.plusSeconds(7_200),
                3_600, "UNPLANNED", "RECOVERED", null, "INC-10", "Proveedor", "Interrupción");
        var serviceDowntime = new ServiceDowntimeView(7, 2L, "Proveedor crítico", "Equivalencias",
                "Testing", "EQV", "America/Lima", "08:00–19:00 · America/Lima", 3_600, 0,
                List.of(segment));
        exporter.downtimeService = new StubDowntimeService(new DowntimeView(to, from, to, null, 2L,
                new DowntimeSummary(1, 3_600, 0), List.of(serviceDowntime)));
        ExternalHistoryExportService.ExportPayload payload = exporter.export(range, null);

        Map<String, String> files = unzip(payload.bytes());
        assertEquals(4, files.size());
        assertTrue(files.get("resumen.csv").contains("Equivalencias"));
        assertTrue(files.get("resumen.csv").contains("America/Lima"));
        assertTrue(files.get("ejecuciones.csv").contains("SCHEDULER"));
        assertTrue(files.get("ejecuciones.csv").contains("\"21\""));
        assertTrue(files.get("downtime-resumen.csv").contains("total_down_horas"));
        assertTrue(files.get("downtime-resumen.csv").contains("Proveedor crítico"));
        assertTrue(files.get("downtime-detalle.csv").contains("clasificacion"));
        assertTrue(files.get("downtime-detalle.csv").contains("INC-10"));
    }

    private static Map<String, String> unzip(byte[] payload) throws Exception {
        Map<String, String> result = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(payload), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                result.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static final class StubRepository extends ExternalRepository {
        private final HistoryView history;
        private final List<HistoryExecutionView> executions;

        private StubRepository(HistoryView history, List<HistoryExecutionView> executions) {
            this.history = history;
            this.executions = executions;
        }

        @Override public HistoryView history(ExternalHistoryRange range, Long serviceId) { return history; }
        @Override public HistoryView history(ExternalHistoryRange range, Long serviceId, Long groupId) { return history; }
        @Override public List<HistoryExecutionView> historyExecutions(ExternalHistoryRange range, Long serviceId) {
            return executions;
        }
        @Override public List<HistoryExecutionView> historyExecutions(ExternalHistoryRange range, Long serviceId, Long groupId) {
            return executions;
        }
    }

    private static final class StubDowntimeService extends ExternalDowntimeService {
        private final DowntimeView view;
        private StubDowntimeService(DowntimeView view) { this.view = view; }
        @Override public DowntimeView downtime(ExternalHistoryRange range, Long serviceId, Long groupId) { return view; }
    }
}
