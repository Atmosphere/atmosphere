package org.atmosphere.samples.di.guice;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.atmosphere.annotation.Broadcast;
import org.atmosphere.annotation.Suspend;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.jersey.Broadcastable;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

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
@Produces("application/json")
public class MessageResource {

    @Inject
    Service service;

    @GET
    @Path("{name}")
    @Suspend(outputComments = true, resumeOnBroadcast = false, listeners = EventsLogger.class)
    public Broadcastable listen(@PathParam("name") String topic) throws JSONException {
        Broadcaster broadcaster = BroadcasterFactory.getDefault().lookup(DefaultBroadcaster.class, topic, true);
        System.out.println("[" + Thread.currentThread().getName() + "] LISTENING to '" + broadcaster.getID() + "'");
        if (service == null) throw new AssertionError();
        return new Broadcastable(new JSONObject().put("from", "system").put("msg", "Connected !"), broadcaster);
    }

    @POST
    @Path("{name}")
    @Broadcast
    public Broadcastable publish(@PathParam("name") String topic, @FormParam("from") String from, @FormParam("msg") String message) throws JSONException {
        Broadcaster broadcaster = BroadcasterFactory.getDefault().lookup(DefaultBroadcaster.class, topic, true);
        System.out.println("[" + Thread.currentThread().getName() + "] PUBLISH to '" + broadcaster.getID() + "' from '" + from + "' : '" + message + "'");
        if (service == null) throw new AssertionError();
        return new Broadcastable(new JSONObject().put("from", from).put("msg", message), broadcaster);
    }

}
