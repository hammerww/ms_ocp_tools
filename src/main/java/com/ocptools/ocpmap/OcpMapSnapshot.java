package com.ocptools.ocpmap;

import java.time.Instant;
import java.util.List;

public record OcpMapSnapshot(
        Instant generatedAt,
        String consoleBaseUrl,
        List<NamespaceView> namespaces,
        List<TestCaseView> testCases,
        List<DeploymentView> deployments,
        List<DeploymentView> unmappedDeployments
) {
    public record NamespaceView(
            String name,
            Instant lastAttemptAt,
            Instant lastSuccessAt,
            String lastError
    ) {
    }

    public record TestCaseView(
            long id,
            String code,
            String name,
            String description,
            String annotation,
            boolean hasFlow,
            List<String> metadata,
            List<String> elements,
            List<DeploymentView> deployments
    ) {
    }

    public record DeploymentView(
            long id,
            String namespace,
            String name,
            Integer desiredReplicas,
            Integer readyReplicas,
            Integer availableReplicas,
            String state,
            boolean active,
            Instant lastSeenAt,
            List<String> testCases,
            TestingMarkView testingMark,
            String consoleUrl
    ) {
    }

    public record TestingMarkView(
            String responsible,
            String note,
            Instant markedAt,
            Instant expiresAt,
            boolean active
    ) {
    }
}
