package org.atmosphere.samples.scala.chat

import javax.ws.rs.{GET, POST, Path, Produces, WebApplicationException, Consumes}
import javax.ws.rs.core.MultivaluedMap
import org.atmosphere.annotation.{Broadcast, Suspend}
import org.atmosphere.util.XSSHtmlFilter

@Path("/chat")
class Chat {

    @Suspend
    @GET
    @Produces(Array("text/html;charset=ISO-8859-1"))
    def suspend() = {
        ""
    }

    @Broadcast(Array(classOf[XSSHtmlFilter],classOf[JsonpFilter]))
    @Consumes(Array("application/x-www-form-urlencoded"))
    @POST
    @Produces(Array("text/html;charset=ISO-8859-1"))
    def publishMessage(form: MultivaluedMap[String, String]) = {
        val action = form.getFirst("action")
        val name = form.getFirst("name")

        if ("login".equals(action)) "System Message" + "__" + name + " has joined."
             else if ("post".equals(action)) name + "__" + form.getFirst("message")
             else throw new WebApplicationException(422)

    }


}