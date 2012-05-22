/*
 * Copyright 2012 Jeanfrancois Arcand
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.samples.pubsub.websocket

import org.atmosphere.cpr.Broadcaster
import org.atmosphere.jersey.{SuspendResponse, Broadcastable}
import javax.ws.rs._
import org.atmosphere.annotation.Broadcast

@Path("/resource/{topic}")
@Produces(Array("text/html;charset=ISO-8859-1"))
class Resource {

  @PathParam("topic") private var topic: Broadcaster = null

  @GET
  def subscribe: SuspendResponse[String] = {
    return new SuspendResponse.SuspendResponseBuilder[String]()
      .broadcaster(topic)
      .outputComments(true)
      .addListener(new Console)
      .build
  }

  @POST
  @Broadcast
  def publish(@FormParam("message") message: String): Broadcastable = {
    return new Broadcastable(message, "", topic)
  }

}
