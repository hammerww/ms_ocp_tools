package com.ocptools.external;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalHistoryRangeTest {
    @Test
    void presetUsesAtMostFortyVisualBuckets() {
        assertEquals(300, ExternalHistoryRange.resolve(1, null, null).bucketSeconds());
        assertEquals(900, ExternalHistoryRange.resolve(6, null, null).bucketSeconds());
        assertEquals(3_600, ExternalHistoryRange.resolve(24, null, null).bucketSeconds());
        assertEquals(21_600, ExternalHistoryRange.resolve(168, null, null).bucketSeconds());
        assertEquals(86_400, ExternalHistoryRange.resolve(720, null, null).bucketSeconds());
    }

    @Test
    void customRangeKeepsExactBounds() {
        Instant from = Instant.parse("2026-09-01T05:00:00Z");
        Instant to = Instant.parse("2026-09-08T05:00:00Z");
        ExternalHistoryRange range = ExternalHistoryRange.resolve(null, from.toString(), to.toString());

        assertNull(range.hours());
        assertEquals(from, range.from());
        assertEquals(to, range.to());
        assertEquals(21_600, range.bucketSeconds());
    }

    @Test
    void rejectsIncompleteOrOversizedCustomRange() {
        assertThrows(IllegalArgumentException.class,
                () -> ExternalHistoryRange.resolve(null, "2026-09-01T05:00:00Z", null));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalHistoryRange.resolve(null, "2026-01-01T05:00:00Z", "2026-09-01T05:00:00Z"));
    }
}
