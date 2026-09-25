package com.ocptools.external;

import com.ocptools.external.ExternalModels.HistoryExecutionView;
import com.ocptools.external.ExternalModels.HistorySummary;
import com.ocptools.external.ExternalModels.HistoryView;
import com.ocptools.external.ExternalModels.ServiceHistoryView;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@ApplicationScoped
public class ExternalHistoryExportService {
    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final DateTimeFormatter CSV_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    @Inject
    ExternalRepository repository;

    public ExportPayload export(ExternalHistoryRange range, Long serviceId) {
        HistoryView history = repository.history(range, serviceId);
        List<HistoryExecutionView> executions = repository.historyExecutions(range, serviceId);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            add(zip, "resumen.csv", summaryCsv(history));
            add(zip, "ejecuciones.csv", executionsCsv(executions));
            zip.finish();
            String filename = "kpi-servicios-externos-" + fileDate(range.from()) + "_" + fileDate(range.to()) + ".zip";
            return new ExportPayload(bytes.toByteArray(), filename);
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo generar la exportación KPI", exception);
        }
    }

    private static String summaryCsv(HistoryView history) {
        List<List<?>> rows = new ArrayList<>();
        rows.add(List.of("tipo", "servicio", "ambiente", "sistema", "desde_america_lima",
                "hasta_america_lima", "zona_horaria", "tamano_bloque_segundos", "ejecuciones",
                "disponibilidad_pct", "latencia_media_ms", "p95_ms", "advertencias", "caidas",
                "ultimo_estado", "ultima_validacion_america_lima"));
        HistorySummary total = history.summary();
        String selected = history.serviceId() == null || history.services().isEmpty()
                ? "Todos los servicios" : history.services().get(0).serviceName();
        rows.add(List.of("TOTAL", selected, "", "", csvDate(history.from()), csvDate(history.to()),
                LIMA.getId(), history.bucketSeconds(), total.executions(), value(total.availability()),
                value(total.averageDurationMs()), value(total.p95DurationMs()), total.warnings(), total.downs(), "", ""));
        for (ServiceHistoryView service : history.services()) {
            rows.add(List.of("SERVICIO", value(service.serviceName()), value(service.environment()), value(service.systemName()),
                    csvDate(history.from()), csvDate(history.to()), LIMA.getId(), history.bucketSeconds(),
                    service.executions(), value(service.availability()), value(service.averageDurationMs()),
                    value(service.p95DurationMs()), service.warnings(), service.downs(),
                    value(service.lastStatus()), csvDate(service.lastCheckedAt())));
        }
        return csv(rows);
    }

    private static String executionsCsv(List<HistoryExecutionView> executions) {
        List<List<?>> rows = new ArrayList<>();
        rows.add(List.of("run_id", "servicio_id", "servicio", "ambiente", "sistema",
                "inicio_america_lima", "fin_america_lima", "zona_horaria", "estado", "duracion_ms", "origen"));
        for (HistoryExecutionView run : executions) {
            rows.add(List.of(run.runId(), run.serviceId(), value(run.serviceName()), value(run.environment()), value(run.systemName()),
                    csvDate(run.startedAt()), csvDate(run.finishedAt()), LIMA.getId(), value(run.status()),
                    value(run.durationMs()), value(run.triggerSource())));
        }
        return csv(rows);
    }

    private static String csv(List<List<?>> rows) {
        StringBuilder result = new StringBuilder("\uFEFF");
        for (List<?> row : rows) {
            for (int index = 0; index < row.size(); index++) {
                if (index > 0) result.append(',');
                String value = String.valueOf(row.get(index));
                result.append('"').append(value.replace("\"", "\"\"")).append('"');
            }
            result.append("\r\n");
        }
        return result.toString();
    }

    private static void add(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String csvDate(Instant instant) {
        return instant == null ? "" : CSV_DATE.format(instant.atZone(LIMA));
    }

    private static String fileDate(Instant instant) {
        return FILE_DATE.format(instant.atZone(LIMA));
    }

    private static Object value(Object value) {
        return value == null ? "" : value;
    }

    public record ExportPayload(byte[] bytes, String filename) {
    }
}
