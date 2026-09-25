package com.ocptools.api;

import com.ocptools.domain.EnvironmentTarget;
import com.ocptools.domain.ToolStatus;
import com.ocptools.environment.EnvironmentCatalog;
import com.ocptools.service.CmsToolService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/v1/cms")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class CmsToolResource {

    @Inject
    CmsToolService service;

    @Inject
    EnvironmentCatalog environments;

    @GET
    @Path("/environments")
    public List<EnvironmentTarget> environments() {
        return environments.all();
    }

    @POST
    @Path("/validate")
    public Response validate(@Valid ToolRequest request) {
        ToolResponse result = service.validate(request);
        return Response.status(httpStatus(result.status(), false)).entity(result).build();
    }

    @POST
    @Path("/register")
    public Response register(@Valid ToolRequest request) {
        ToolResponse result = service.register(request);
        return Response.status(httpStatus(result.status(), true)).entity(result).build();
    }

    private static int httpStatus(ToolStatus status, boolean registration) {
        return switch (status) {
            case INVALID_REQUEST -> 400;
            case DATABASE_ERROR -> 503;
            case TIMEOUT -> 504;
            case QUERY_ERROR, REGISTER_FAILED, REGISTER_UNVERIFIED -> 502;
            case ENVIRONMENT_FOUND -> registration ? 409 : 200;
            default -> 200;
        };
    }
}

