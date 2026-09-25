package com.ocptools.api;

import com.ocptools.config.ToolsConfig;
import com.ocptools.ocpmap.OcpMapCatalogRepository;
import com.ocptools.ocpmap.OcpMapCatalogSnapshot;
import com.ocptools.ocpmap.OcpMapCatalogSnapshot.TestCaseDetail;
import com.ocptools.ocpmap.OcpMapFlowRepository;
import com.ocptools.ocpmap.OcpMapLifecycle;
import com.ocptools.ocpmap.OcpMapUnavailableException;
import com.ocptools.ocpmap.OcpNamespacePolicy;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@Path("/api/v1/ocp-map")
@Produces(MediaType.APPLICATION_JSON)
public class OcpMapCatalogResource {
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

    @GET
    @Path("/deployments")
    public Response deployments(@QueryParam("includeInactive") boolean includeInactive) {
        return catalogResponse(snapshot -> visibleDeployments(snapshot, includeInactive));
    }

    @GET
    @Path("/test-cases")
    public Response testCases(@QueryParam("includeArchived") boolean includeArchived,
                              @QueryParam("includeInactive") boolean includeInactive) {
        return catalogResponse(snapshot -> filteredCases(snapshot, includeArchived, includeInactive));
    }

    @GET
    @Path("/elements")
    public Response elements(@QueryParam("includeArchived") boolean includeArchived) {
        return catalogResponse(snapshot -> snapshot.elements().stream()
                .filter(element -> includeArchived || element.archivedAt() == null)
                .toList());
    }

    @GET
    @Path("/test-cases/{id}/flow")
    public Response flow(@PathParam("id") long id) {
        if (!lifecycle.isDatabaseReady()) {
            return unavailable();
        }
        try {
            return flowRepository.findByTestCaseId(id)
                    .map(flow -> Response.ok(flow).build())
                    .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("message", "El caso no tiene un flujo configurado")).build());
        } catch (IllegalArgumentException exception) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", exception.getMessage())).build();
        } catch (OcpMapUnavailableException exception) {
            return unavailable();
        }
    }

    @GET
    @Path("/export")
    public Response export(@QueryParam("includeArchived") boolean includeArchived,
                           @QueryParam("includeInactive") boolean includeInactive) {
        return catalogResponse(snapshot -> new OcpMapCatalogSnapshot(snapshot.generatedAt(),
                snapshot.scannedNamespaces(), snapshot.visibleNamespaces(), snapshot.namespaceStatus(),
                filteredCases(snapshot, includeArchived, includeInactive),
                snapshot.elements().stream().filter(element -> includeArchived || element.archivedAt() == null).toList(),
                visibleDeployments(snapshot, includeInactive)));
    }

    private Response catalogResponse(CatalogProjection projection) {
        if (!lifecycle.isDatabaseReady()) {
            return unavailable();
        }
        try {
            return Response.ok(projection.apply(load())).build();
        } catch (OcpMapUnavailableException exception) {
            return unavailable();
        }
    }

    private OcpMapCatalogSnapshot load() {
        return repository.load(config.ocpMap().consoleBaseUrl(), namespacePolicy.scanned(), namespacePolicy.visible());
    }

    private static List<OcpMapCatalogSnapshot.DeploymentDetail> visibleDeployments(
            OcpMapCatalogSnapshot snapshot, boolean includeInactive) {
        return snapshot.deployments().stream()
                .filter(OcpMapCatalogSnapshot.DeploymentDetail::visible)
                .filter(deployment -> includeInactive || deployment.active())
                .toList();
    }

    private static List<TestCaseDetail> filteredCases(OcpMapCatalogSnapshot snapshot, boolean includeArchived,
                                                       boolean includeInactive) {
        return snapshot.testCases().stream()
                .filter(testCase -> includeArchived || testCase.archivedAt() == null)
                .map(testCase -> new TestCaseDetail(testCase.id(), testCase.code(), testCase.name(),
                        testCase.description(), testCase.annotation(), testCase.hasFlow(), testCase.displayOrder(),
                        testCase.metadata(), testCase.elements(),
                        testCase.deployments().stream()
                                .filter(OcpMapCatalogSnapshot.DeploymentDetail::visible)
                                .filter(deployment -> includeInactive || deployment.active())
                                .toList(),
                        testCase.createdAt(), testCase.updatedAt(), testCase.archivedAt()))
                .toList();
    }

    private static Response unavailable() {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of("message", "El catálogo del mapa OCP aún no está disponible"))
                .build();
    }

    @FunctionalInterface
    private interface CatalogProjection {
        Object apply(OcpMapCatalogSnapshot snapshot);
    }
}
