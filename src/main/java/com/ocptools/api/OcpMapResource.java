package com.ocptools.api;

import com.ocptools.common.RequestMetadata;
import com.ocptools.config.ToolsConfig;
import com.ocptools.ocpmap.OcpMapLifecycle;
import com.ocptools.ocpmap.OcpMapRepository;
import com.ocptools.ocpmap.OcpMapUnavailableException;
import com.ocptools.ocpmap.OcpNamespacePolicy;
import com.ocptools.ocpmap.TestingMarkRemovalRequest;
import com.ocptools.ocpmap.TestingMarkRequest;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Map;

@Path("/api/v1/ocp-map")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class OcpMapResource {
    private static final Logger LOG = Logger.getLogger(OcpMapResource.class);

    @Inject
    OcpMapRepository repository;

    @Inject
    OcpMapLifecycle lifecycle;

    @Inject
    ToolsConfig config;

    @Inject
    OcpNamespacePolicy namespacePolicy;

    @Inject
    RequestMetadata metadata;

    @GET
    @Path("/snapshot")
    public Response snapshot() {
        if (!lifecycle.isDatabaseReady()) {
            return unavailable();
        }
        try {
            return Response.ok(repository.loadSnapshot(config.ocpMap().consoleBaseUrl(),
                    namespacePolicy.scanned(), namespacePolicy.visible())).build();
        } catch (OcpMapUnavailableException exception) {
            return unavailable();
        }
    }

    @PUT
    @Path("/deployments/{namespace}/{name}/testing-mark")
    public Response mark(@PathParam("namespace") String namespace, @PathParam("name") String name,
                         @Valid TestingMarkRequest request) {
        try {
            validateResourceName(namespace, name);
            repository.saveTestingMark(namespace, name, request, Instant.now());
            audit("mark", namespace, name, request.responsible());
            return Response.noContent().build();
        } catch (IllegalArgumentException exception) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", exception.getMessage())).build();
        } catch (OcpMapUnavailableException exception) {
            return unavailable();
        }
    }

    @POST
    @Path("/deployments/{namespace}/{name}/testing-mark/remove")
    public Response removeMark(@PathParam("namespace") String namespace, @PathParam("name") String name,
                               @Valid TestingMarkRemovalRequest request) {
        try {
            validateResourceName(namespace, name);
            repository.removeTestingMark(namespace, name, request, Instant.now());
            audit("remove-mark", namespace, name, request.responsible());
            return Response.noContent().build();
        } catch (IllegalArgumentException exception) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", exception.getMessage())).build();
        } catch (OcpMapUnavailableException exception) {
            return unavailable();
        }
    }

    private void audit(String action, String namespace, String name, String responsible) {
        LOG.infof("audit=true user=%s tool=ocp-map action=%s namespace=%s deployment=%s responsible=%s correlationId=%s",
                safe(metadata.user()), action, safe(namespace), safe(name), safe(responsible),
                safe(metadata.correlationId()));
    }

    private static void validateResourceName(String namespace, String name) {
        if (!namespace.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?") || namespace.length() > 63
                || !name.matches("[a-z0-9]([-a-z0-9.]*[a-z0-9])?") || name.length() > 253) {
            throw new IllegalArgumentException("Namespace o deployment inválido");
        }
    }

    private static Response unavailable() {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of("message", "El inventario del mapa OCP aún no está disponible"))
                .build();
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.replaceAll("[\\r\\n\\t]", "_");
    }
}
