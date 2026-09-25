package com.ocptools.api;

import com.ocptools.tcpcheck.TcpCheckService;
import com.ocptools.tcpcheck.TcpCheckStatus;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/v1/tcp-check")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class TcpCheckResource {

    @Inject
    TcpCheckService service;

    @POST
    public Response check(@Valid TcpCheckRequest request) {
        TcpCheckResponse result = service.check(request);
        int httpStatus = result.status() == TcpCheckStatus.INVALID_REQUEST ? 400 : 200;
        return Response.status(httpStatus).entity(result).build();
    }
}
