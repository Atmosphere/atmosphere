package org.atmosphere.samples.pubsub;

import com.sun.jersey.spi.resource.Singleton;

import javax.servlet.ServletContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.PathSegment;
import java.io.InputStream;

@Path("/")
@Singleton
public class FileResource
{

    @Context ServletContext sc;

    @Path("jquery{id}.js")
    @GET
    public InputStream getJQuery(@PathParam("id") PathSegment ps)
    {
        return sc.getResourceAsStream("/" + ps.getPath());
    }

    @GET
    @Produces("text/html")
    public InputStream getIndex()
    {
        return sc.getResourceAsStream("/index.html");
    }
}