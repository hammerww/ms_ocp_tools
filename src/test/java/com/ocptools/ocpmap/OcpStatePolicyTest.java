package com.ocptools.ocpmap;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpecBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatusBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OcpStatePolicyTest {
    @Test
    void reportsTheLastObservedStateAndUsesUnknownOnlyBeforeAnySuccessfulObservation() {
        assertEquals("APAGADO", OcpStatePolicy.state(0));
        assertEquals("ENCENDIDO", OcpStatePolicy.state(2));
        assertEquals("DESCONOCIDO", OcpStatePolicy.state(null));
    }

    @Test
    void mapsDeploymentSpecAndStatusWithoutConfusingReadyWithDesired() {
        Deployment deployment = new Deployment();
        deployment.setMetadata(new ObjectMetaBuilder().withName("sample").build());
        deployment.setSpec(new DeploymentSpecBuilder().withReplicas(2).build());
        deployment.setStatus(new DeploymentStatusBuilder().withReadyReplicas(1).withAvailableReplicas(1).build());

        ObservedDeployment result = OcpStatePolicy.from(deployment);

        assertEquals("sample", result.name());
        assertEquals(2, result.desiredReplicas());
        assertEquals(1, result.readyReplicas());
        assertEquals(1, result.availableReplicas());
    }

    @Test
    void defaultsDesiredReplicasToOneWhenOcpOmitsTheField() {
        Deployment deployment = new Deployment();
        deployment.setMetadata(new ObjectMetaBuilder().withName("defaulted").build());
        deployment.setSpec(new DeploymentSpecBuilder().build());

        assertEquals(1, OcpStatePolicy.from(deployment).desiredReplicas());
    }
}
