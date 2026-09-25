package com.ocptools.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;

@Path("/flow")
public class FlowPageResource {
    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response page() {
        InputStream page = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("META-INF/resources/flow.html");
        if (page == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(page).build();
    }
}
