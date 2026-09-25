package com.ocptools.ocpmap;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OcpNamespacePolicyTest {
    @Test
    void normalizesWhitespaceBlankValuesAndDuplicatesWithoutChangingOrder() {
        assertEquals(List.of("testing", "testing-matrix"),
                OcpNamespacePolicy.normalize(List.of(" testing ", "", "testing-matrix", "testing")));
    }
}
