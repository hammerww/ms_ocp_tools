package com.ocptools.ocpmap;

import java.time.Instant;
import java.util.List;

public record OcpMapCatalogSnapshot(
        Instant generatedAt,
        List<String> scannedNamespaces,
        List<String> visibleNamespaces,
        List<NamespaceDetail> namespaceStatus,
        List<TestCaseDetail> testCases,
        List<ElementDetail> elements,
        List<DeploymentDetail> deployments
) {
    public record NamespaceDetail(
            String name,
            boolean visible,
            Instant lastAttemptAt,
            Instant lastSuccessAt,
            String lastError
    ) {
    }

    public record TestCaseDetail(
            long id,
            String code,
            String name,
            String description,
            String annotation,
            boolean hasFlow,
            int displayOrder,
            List<String> metadata,
            List<ElementReference> elements,
            List<DeploymentDetail> deployments,
            Instant createdAt,
            Instant updatedAt,
            Instant archivedAt
    ) {
    }

    public record ElementReference(long id, String name, boolean archived) {
    }

    public record ElementDetail(
            long id,
            String name,
            String description,
            int displayOrder,
            int activeTestCaseCount,
            Instant createdAt,
            Instant updatedAt,
            Instant archivedAt
    ) {
    }

    public record DeploymentDetail(
            long id,
            String namespace,
            String name,
            Integer desiredReplicas,
            Integer readyReplicas,
            String state,
            boolean active,
            boolean visible,
            Instant lastSeenAt,
            List<String> activeTestCases,
            String consoleUrl
    ) {
    }
}
