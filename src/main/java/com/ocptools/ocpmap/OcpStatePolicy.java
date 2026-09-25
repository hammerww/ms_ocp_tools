package com.ocptools.ocpmap;

import io.fabric8.kubernetes.api.model.apps.Deployment;

public final class OcpStatePolicy {
    private OcpStatePolicy() {
    }

    public static String state(Integer desiredReplicas) {
        if (desiredReplicas == null) {
            return "DESCONOCIDO";
        }
        return desiredReplicas == 0 ? "APAGADO" : "ENCENDIDO";
    }

    public static ObservedDeployment from(Deployment deployment) {
        int desired = deployment.getSpec() == null || deployment.getSpec().getReplicas() == null
                ? 1 : deployment.getSpec().getReplicas();
        int ready = deployment.getStatus() == null || deployment.getStatus().getReadyReplicas() == null
                ? 0 : deployment.getStatus().getReadyReplicas();
        int available = deployment.getStatus() == null || deployment.getStatus().getAvailableReplicas() == null
                ? 0 : deployment.getStatus().getAvailableReplicas();
        return new ObservedDeployment(deployment.getMetadata().getName(), desired, ready, available);
    }
}
