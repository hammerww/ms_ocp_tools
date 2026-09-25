package com.ocptools.ocpmap;

import java.util.List;

public record AdminTestCaseRequest(
        String code,
        String name,
        String description,
        String annotation,
        Integer displayOrder,
        List<String> metadata,
        List<Long> elementIds,
        List<Long> deploymentIds
) {
}
