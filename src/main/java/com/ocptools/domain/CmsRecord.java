package com.ocptools.domain;

public record CmsRecord(
        String interactionDate,
        String accessId,
        String externalId,
        String pid,
        String execId
) {
}

