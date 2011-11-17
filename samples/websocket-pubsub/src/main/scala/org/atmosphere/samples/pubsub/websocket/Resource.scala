package org.atmosphere.samples.pubsub.websocket

import org.atmosphere.cpr.Broadcaster
import org.atmosphere.jersey.{SuspendResponse, Broadcastable}
import javax.ws.rs._
import org.atmosphere.annotation.Broadcast

@Path("/resource/{topic}")
@Produces(Array("text/html;charset=ISO-8859-1"))
class Resource {

  @PathParam("topic") private var topic: Broadcaster = null

  @GET def subscribe: SuspendResponse[String] = {
    return new SuspendResponse.SuspendResponseBuilder[String]()
      .broadcaster(topic)
      .outputComments(true)
      .addListener(new Console)
      .build
  }

  @POST
  @Broadcast def publish(@FormParam("message") message: String): Broadcastable = {
    return new Broadcastable(message, "", topic)
  }

  @Path("devoxx")
  @POST
  @Broadcast def devoxx(@FormParam("message") message: String): Broadcastable = {
    return new Broadcastable(message, "", topic)
  }

}
