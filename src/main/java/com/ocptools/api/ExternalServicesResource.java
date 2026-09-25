package com.ocptools.api;

import com.ocptools.external.ExternalAdminSessions;
import com.ocptools.external.ExternalModels;
import com.ocptools.external.ExternalMonitorService;
import com.ocptools.external.ExternalHistoryExportService;
import com.ocptools.external.ExternalHistoryRange;
import com.ocptools.external.ExternalRepository;
import com.ocptools.external.ExternalUnavailableException;
import com.ocptools.external.KdbxExportService;
import com.ocptools.ocpmap.OcpMapLifecycle;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Map;

@Path("/api/v1/external-services")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ExternalServicesResource {
    public static final String ADMIN_HEADER = "X-External-Admin-Token";
    private static final Logger LOG = Logger.getLogger(ExternalServicesResource.class);

    @Inject ExternalRepository repository;
    @Inject ExternalAdminSessions sessions;
    @Inject ExternalMonitorService monitor;
    @Inject ExternalHistoryExportService historyExport;
    @Inject KdbxExportService kdbx;
    @Inject OcpMapLifecycle lifecycle;

    @GET
    @Path("/snapshot")
    public Response snapshot() {
        if (!lifecycle.isDatabaseReady()) return unavailable();
        try {
            return Response.ok(repository.snapshot()).header("Cache-Control", "no-store").build();
        } catch (ExternalUnavailableException exception) {
            return unavailable();
        }
    }

    @GET
    @Path("/history")
    public Response history(@QueryParam("hours") Integer hours, @QueryParam("from") String from,
                            @QueryParam("to") String to, @QueryParam("serviceId") Long serviceId) {
        if (!lifecycle.isDatabaseReady()) return unavailable();
        try {
            return Response.ok(repository.history(ExternalHistoryRange.resolve(hours, from, to), serviceId))
                    .header("Cache-Control", "no-store").build();
        } catch (IllegalArgumentException exception) {
            return error(Response.Status.BAD_REQUEST, exception.getMessage());
        } catch (ExternalUnavailableException exception) {
            return unavailable();
        }
    }

    @GET
    @Path("/history/export.zip")
    @Produces("application/zip")
    public Response exportHistory(@QueryParam("hours") Integer hours, @QueryParam("from") String from,
                                  @QueryParam("to") String to, @QueryParam("serviceId") Long serviceId) {
        if (!lifecycle.isDatabaseReady()) return unavailable();
        try {
            var payload = historyExport.export(ExternalHistoryRange.resolve(hours, from, to), serviceId);
            LOG.infof("tool=external-monitor action=export-kpi result=success bytes=%d", payload.bytes().length);
            return Response.ok(payload.bytes()).type("application/zip").header("Content-Disposition",
                            "attachment; filename=\"" + payload.filename() + "\"")
                    .header("Cache-Control", "no-store").build();
        } catch (IllegalArgumentException exception) {
            return error(Response.Status.BAD_REQUEST, exception.getMessage());
        } catch (ExternalUnavailableException exception) {
            return unavailable();
        }
    }

    @POST
    @Path("/admin/unlock")
    public Response unlock(ExternalModels.UnlockRequest request, @Context HttpHeaders headers) {
        try {
            String client = first(headers.getHeaderString("X-Forwarded-For"), headers.getHeaderString("X-Real-IP"), "unknown");
            return Response.ok(sessions.unlock(request == null ? null : request.masterPassword(), client))
                    .header("Cache-Control", "no-store").build();
        } catch (ExternalAdminSessions.TooManyAttemptsException exception) {
            return error(Response.Status.TOO_MANY_REQUESTS, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return error(Response.Status.UNAUTHORIZED, exception.getMessage());
        }
    }

    @GET
    @Path("/admin/credentials")
    public Response credentials(@HeaderParam(ADMIN_HEADER) String token) {
        return secured(token, () -> Response.ok(repository.credentials())
                .header("Cache-Control", "no-store").build());
    }

    @POST
    @Path("/admin/credentials")
    public Response createCredential(@HeaderParam(ADMIN_HEADER) String token, ExternalModels.CredentialInput input) {
        return secured(token, () -> Response.status(Response.Status.CREATED)
                .entity(Map.of("id", repository.saveCredential(null, input))).build());
    }

    @PUT
    @Path("/admin/credentials/{id}")
    public Response updateCredential(@HeaderParam(ADMIN_HEADER) String token, @PathParam("id") long id,
                                     ExternalModels.CredentialInput input) {
        return secured(token, () -> Response.ok(Map.of("id", repository.saveCredential(id, input))).build());
    }

    @POST
    @Path("/admin/credentials/{id}/archive")
    public Response archiveCredential(@HeaderParam(ADMIN_HEADER) String token, @PathParam("id") long id) {
        return secured(token, () -> { repository.archiveCredential(id); return Response.noContent().build(); });
    }

    @POST
    @Path("/admin/groups")
    public Response createGroup(@HeaderParam(ADMIN_HEADER) String token, ExternalModels.GroupInput input) {
        return secured(token, () -> Response.status(Response.Status.CREATED)
                .entity(Map.of("id", repository.createGroup(input))).build());
    }

    @POST
    @Path("/admin/services")
    public Response createService(@HeaderParam(ADMIN_HEADER) String token, ExternalModels.ServiceInput input) {
        return secured(token, () -> Response.status(Response.Status.CREATED)
                .entity(Map.of("id", repository.saveService(null, input))).build());
    }

    @PUT
    @Path("/admin/services/{id}")
    public Response updateService(@HeaderParam(ADMIN_HEADER) String token, @PathParam("id") long id,
                                  ExternalModels.ServiceInput input) {
        return secured(token, () -> Response.ok(Map.of("id", repository.saveService(id, input))).build());
    }

    @POST
    @Path("/admin/services/{id}/clone")
    public Response cloneService(@HeaderParam(ADMIN_HEADER) String token, @PathParam("id") long id,
                                 Map<String, String> body) {
        return secured(token, () -> Response.status(Response.Status.CREATED)
                .entity(Map.of("id", repository.cloneService(id, body == null ? null : body.get("name")))).build());
    }

    @POST
    @Path("/admin/services/{id}/archive")
    public Response archiveService(@HeaderParam(ADMIN_HEADER) String token, @PathParam("id") long id) {
        return secured(token, () -> { repository.archiveService(id); return Response.noContent().build(); });
    }

    @POST
    @Path("/admin/services/{id}/run")
    public Response manualRun(@HeaderParam(ADMIN_HEADER) String token, @PathParam("id") long id) {
        return secured(token, () -> Response.ok(monitor.manualRun(id)).build());
    }

    @GET
    @Path("/admin/export.kdbx")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response export(@HeaderParam(ADMIN_HEADER) String token) {
        return secured(token, () -> {
            try {
                byte[] payload = kdbx.export();
                LOG.infof("tool=external-monitor action=export-kdbx result=success bytes=%d", payload.length);
                return Response.ok(payload)
                        .header("Content-Disposition", "attachment; filename=ocp-tools-credenciales.kdbx")
                        .header("Cache-Control", "no-store").build();
            } catch (RuntimeException exception) {
                LOG.warnf("tool=external-monitor action=export-kdbx result=failure type=%s",
                        exception.getClass().getSimpleName());
                throw exception;
            }
        });
    }

    @POST
    @Path("/admin/import-history")
    public Response importHistory(@HeaderParam(ADMIN_HEADER) String token,
                                  java.util.List<ExternalModels.HistoricalRunInput> history) {
        return secured(token, () -> Response.ok(Map.of("imported", repository.importHistory(history))).build());
    }

    private Response secured(String token, Operation operation) {
        if (!lifecycle.isDatabaseReady()) return unavailable();
        try {
            sessions.require(token);
            return operation.run();
        } catch (SecurityException exception) {
            return error(Response.Status.UNAUTHORIZED, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return error(Response.Status.BAD_REQUEST, exception.getMessage());
        } catch (ExternalUnavailableException exception) {
            return unavailable();
        } catch (IllegalStateException exception) {
            return error(Response.Status.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    private static Response unavailable() {
        return error(Response.Status.SERVICE_UNAVAILABLE, "El catálogo de servicios externos aún no está disponible");
    }

    private static Response error(Response.Status status, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON)
                .entity(Map.of("message", message == null ? status.getReasonPhrase() : message)).build();
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.split(",")[0].trim();
        return "unknown";
    }

    @FunctionalInterface
    private interface Operation { Response run(); }
}
