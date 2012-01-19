package org.atmosphere.samples.multirequest.handlers;

import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;

import org.apache.log4j.Logger;
import org.atmosphere.annotation.Broadcast;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.jersey.Broadcastable;
import org.atmosphere.jersey.SuspendResponse;

@Path("/subscribe/{topic}")
@Produces("text/html;charset=ISO-8859-1")
public class Subscriber {

	private static final Logger LOG = Logger.getLogger(Subscriber.class);

	@PathParam("topic")
	private Broadcaster topic;

	@GET
	public SuspendResponse<String> subscribe() {
		LOG.debug("OnSubscribe to topic");
		SuspendResponse<String> sr = new SuspendResponse.SuspendResponseBuilder<String>().broadcaster(topic).outputComments(true)
				.addListener(new EventsLogger()).build();
		return sr;
	}

	@POST
	@Broadcast
	public Broadcastable publish(@FormParam("message") String message) {
		LOG.debug("Receive message <" + message + ">, dispatch to other connected");
		return new Broadcastable(message, "", topic);
	}
}
