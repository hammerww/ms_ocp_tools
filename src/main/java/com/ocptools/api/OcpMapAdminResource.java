package com.ocptools.api;

import com.ocptools.common.RequestMetadata;
import com.ocptools.config.ToolsConfig;
import com.ocptools.ocpmap.AdminElementRequest;
import com.ocptools.ocpmap.AdminTestCaseRequest;
import com.ocptools.ocpmap.OcpMapCatalogRepository;
import com.ocptools.ocpmap.OcpMapFlowRepository;
import com.ocptools.ocpmap.OcpMapLifecycle;
import com.ocptools.ocpmap.OcpMapUnavailableException;
import com.ocptools.ocpmap.OcpNamespacePolicy;
import com.ocptools.ocpmap.TestCaseFlowDefinition;
import jakarta.inject.Inject;
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

@Path("/api/v1/ocp-map/admin")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class OcpMapAdminResource {
    private static final Logger LOG = Logger.getLogger(OcpMapAdminResource.class);

    @Inject
    OcpMapCatalogRepository repository;

    @Inject
    OcpMapFlowRepository flowRepository;

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
            return Response.ok(repository.load(config.ocpMap().consoleBaseUrl(),
                    namespacePolicy.scanned(), namespacePolicy.visible())).build();
        } catch (OcpMapUnavailableException exception) {
            return unavailable();
        }
    }

    @POST
    @Path("/test-cases")
    public Response createTestCase(AdminTestCaseRequest request) {
        return write("create-test-case", () -> {
            long id = repository.createTestCase(request, Instant.now());
            return Response.status(Response.Status.CREATED).entity(Map.of("id", id)).build();
        });
    }

    @PUT
    @Path("/test-cases/{id}")
    public Response updateTestCase(@PathParam("id") long id, AdminTestCaseRequest request) {
        return write("update-test-case", () -> {
            repository.updateTestCase(id, request, Instant.now());
            return Response.noContent().build();
        });
    }

    @POST
    @Path("/test-cases/{id}/clone")
    public Response cloneTestCase(@PathParam("id") long id, AdminTestCaseRequest request) {
        return write("clone-test-case", () -> {
            long cloneId = repository.cloneTestCase(id, request, Instant.now());
            return Response.status(Response.Status.CREATED).entity(Map.of("id", cloneId)).build();
        });
    }

    @PUT
    @Path("/test-cases/{id}/flow")
    public Response saveFlow(@PathParam("id") long id, TestCaseFlowDefinition request) {
        return write("save-test-case-flow", () -> {
            flowRepository.save(id, request, Instant.now());
            return Response.noContent().build();
        });
    }

    @POST
    @Path("/test-cases/{id}/archive")
    public Response archiveTestCase(@PathParam("id") long id) {
        return archive("archive-test-case", id, true, true);
    }

    @POST
    @Path("/test-cases/{id}/restore")
    public Response restoreTestCase(@PathParam("id") long id) {
        return archive("restore-test-case", id, false, true);
    }

    @POST
    @Path("/elements")
    public Response createElement(AdminElementRequest request) {
        return write("create-element", () -> {
            long id = repository.createElement(request, Instant.now());
            return Response.status(Response.Status.CREATED).entity(Map.of("id", id)).build();
        });
    }

    @PUT
    @Path("/elements/{id}")
    public Response updateElement(@PathParam("id") long id, AdminElementRequest request) {
        return write("update-element", () -> {
            repository.updateElement(id, request, Instant.now());
            return Response.noContent().build();
        });
    }

    @POST
    @Path("/elements/{id}/archive")
    public Response archiveElement(@PathParam("id") long id) {
        return archive("archive-element", id, true, false);
    }

    @POST
    @Path("/elements/{id}/restore")
    public Response restoreElement(@PathParam("id") long id) {
        return archive("restore-element", id, false, false);
    }

    private Response archive(String action, long id, boolean archived, boolean testCase) {
        return write(action, () -> {
            if (testCase) {
                repository.setTestCaseArchived(id, archived, Instant.now());
            } else {
                repository.setElementArchived(id, archived, Instant.now());
            }
            return Response.noContent().build();
        });
    }

    private Response write(String action, WriteOperation operation) {
        if (!lifecycle.isDatabaseReady()) {
            return unavailable();
        }
        try {
            Response response = operation.run();
            LOG.infof("audit=true user=%s tool=ocp-map-admin action=%s correlationId=%s",
                    safe(metadata.user()), action, safe(metadata.correlationId()));
            return response;
        } catch (IllegalArgumentException exception) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", exception.getMessage())).build();
        } catch (OcpMapUnavailableException exception) {
            return unavailable();
        }
    }

    private static Response unavailable() {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of("message", "El catálogo del mapa OCP aún no está disponible"))
                .build();
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value.replaceAll("[\\r\\n\\t]", "_");
    }

    @FunctionalInterface
    private interface WriteOperation {
        Response run();
    }
}
