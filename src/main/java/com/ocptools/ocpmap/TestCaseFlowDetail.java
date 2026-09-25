package com.ocptools.ocpmap;

import java.time.Instant;

public record TestCaseFlowDetail(
        long testCaseId,
        String code,
        String name,
        TestCaseFlowDefinition flow,
        Instant updatedAt
) {
}
