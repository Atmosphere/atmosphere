package org.atmosphere.samples.di.guice;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.atmosphere.annotation.Broadcast;
import org.atmosphere.annotation.Suspend;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.jersey.Broadcastable;

import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;

/**
 * @author Mathieu Carbou
 * @since 0.7
 */
@Path("/topic")
@Singleton
public class MessageResource {

    @Inject
    Service service;

    @GET
    @Path("{name}")
    @Produces("text/html;charset=UTF-8")
    @Suspend(outputComments = true, resumeOnBroadcast = false, listeners = EventsLogger.class)
    public String listen(@PathParam("name") Broadcaster bc) {
        System.out.println("[" + Thread.currentThread().getName() + "] LISTENING to '" + bc.getID() + "'");
        if (service == null) throw new AssertionError();
        return "Connected !\n";
    }

    @POST
    @Path("{name}")
    @Broadcast(MessageFilter.class)
    @Produces("text/html;charset=UTF-8")
    public Broadcastable publish(@PathParam("name") Broadcaster bc, @FormParam("message") String message) {
        System.out.println("[" + Thread.currentThread().getName() + "] PUBLISH to '" + bc.getID() + "' : '" + message.replace("\n", "\\n") + "'");
        if (service == null) throw new AssertionError();
        return new Broadcastable(message, bc);
    }

    @POST
    @Broadcast
    @Path("close/{name}")
    @Produces("text/html;charset=UTF-8")
    public String close(@PathParam("name") Broadcaster bc) {
        System.out.println("[" + Thread.currentThread().getName() + "] DISCONNECT from '" + bc.getID() + "'");
        if (service == null) throw new AssertionError();
        bc.destroy();
        return "";
    }

}
