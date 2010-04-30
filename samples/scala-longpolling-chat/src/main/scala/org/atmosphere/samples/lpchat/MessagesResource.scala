package org.atmosphere.samples.lpchat

import java.io.File;
import java.util.Date;

import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import com.sun.jersey.spi.resource.Singleton;

import org.atmosphere.annotation.Broadcast;
import org.atmosphere.annotation.Suspend;

@Path("/chat")
@Singleton
@Produces(Array("application/json"))
class MessagesResource {

  var messages = List[Message]();

  @Suspend { val resumeOnBroadcast = true }
  @GET
  def getMessages(@QueryParam("date") lastSeenTime : long) : unit = {
    val lastSeenDate = new Date(lastSeenTime);
    
    messages.filter(_.date.compareTo(lastSeenDate) > 0) match {
      case Nil => // Suspend and wait for data
      case x => // Return with data
        throw new WebApplicationException(
          Response.ok(x.reverse).build())
    }
  }

  @Broadcast
  @POST
  @Consumes(Array("application/x-www-form-urlencoded"))
  def publishMessage(@FormParam("message") message : String) = {
    val m = new Message(new Date(), message);
    messages = m :: messages;
    List(m);
  }
}
