package com.ocptools.ocpmap;

public record AdminElementRequest(
        String name,
        String description,
        Integer displayOrder
) {
}
