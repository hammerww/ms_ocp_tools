package com.ocptools.ocpmap;

public record ObservedDeployment(
        String name,
        int desiredReplicas,
        int readyReplicas,
        int availableReplicas
) {
}

