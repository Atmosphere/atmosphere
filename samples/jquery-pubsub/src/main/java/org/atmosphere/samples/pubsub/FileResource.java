package org.atmosphere.samples.pubsub;

import javax.servlet.ServletContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.PathSegment;
import java.io.InputStream;

@Path("/")
@Produces("text/html")
public class FileResource {

    private
    @Context
    ServletContext sc;

    @Path("/jquery/jquery{id}.js")
    @GET
    public InputStream getJQuery(@PathParam("id") PathSegment ps) {
        return sc.getResourceAsStream("/jquery/" + ps.getPath());
    }

    @GET
    public InputStream getIndex() {
        return sc.getResourceAsStream("/index.html");
    }
}
