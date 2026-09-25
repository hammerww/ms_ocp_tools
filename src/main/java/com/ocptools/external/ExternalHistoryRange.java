package com.ocptools.external;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Rango común para consulta, visualización y exportación del histórico. */
public record ExternalHistoryRange(Integer hours, Instant from, Instant to, long bucketSeconds) {
    private static final List<Integer> PRESET_HOURS = List.of(1, 6, 24, 168, 720);
    private static final Duration MAX_RANGE = Duration.ofDays(90);
    private static final long[] BUCKETS = {
            60, 300, 900, 1_800, 3_600, 7_200, 10_800, 21_600,
            43_200, 86_400, 172_800, 604_800, 1_209_600, 2_592_000
    };

    public static ExternalHistoryRange resolve(Integer hours, String fromValue, String toValue) {
        boolean custom = present(fromValue) || present(toValue);
        if (custom) {
            if (!present(fromValue) || !present(toValue)) {
                throw new IllegalArgumentException("El rango personalizado requiere fecha inicial y final");
            }
            try {
                return custom(Instant.parse(fromValue), Instant.parse(toValue));
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException("Las fechas del rango no tienen un formato válido");
            }
        }
        int selectedHours = hours == null ? 24 : hours;
        if (!PRESET_HOURS.contains(selectedHours)) {
            throw new IllegalArgumentException("Rango histórico no soportado");
        }
        Instant to = Instant.now();
        Instant from = to.minus(selectedHours, ChronoUnit.HOURS);
        return new ExternalHistoryRange(selectedHours, from, to, bucketSeconds(from, to));
    }

    static ExternalHistoryRange custom(Instant from, Instant to) {
        if (!from.isBefore(to)) throw new IllegalArgumentException("La fecha inicial debe ser anterior a la final");
        Duration duration = Duration.between(from, to);
        if (duration.compareTo(MAX_RANGE) > 0) {
            throw new IllegalArgumentException("El rango personalizado no puede superar 90 días");
        }
        return new ExternalHistoryRange(null, from, to, bucketSeconds(from, to));
    }

    String sqlBucket() {
        return bucketSeconds + " seconds";
    }

    private static long bucketSeconds(Instant from, Instant to) {
        long seconds = Math.max(1, Duration.between(from, to).getSeconds());
        for (long bucket : BUCKETS) {
            // El +1 cubre la posible desalineación entre el inicio y date_bin.
            if ((seconds + bucket - 1) / bucket + 1 <= 40) return bucket;
        }
        return BUCKETS[BUCKETS.length - 1];
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
