package com.ocptools.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;

@Path("/admin")
public class AdminPageResource {
    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response page() {
        return resource("META-INF/resources/admin.html");
    }

    @GET
    @Path("/flow")
    @Produces(MediaType.TEXT_HTML)
    public Response flow() {
        return resource("META-INF/resources/admin-flow.html");
    }

    private static Response resource(String name) {
        InputStream page = Thread.currentThread().getContextClassLoader().getResourceAsStream(name);
        if (page == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(page).build();
    }
}
