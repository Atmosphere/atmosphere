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
package org.atmosphere.samples.chat.jaxrs2;

import org.atmosphere.annotation.Broadcast;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.Suspend;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.ExecutionContext;

/**
 * Simple chat resource demonstrating the power of Atmosphere using JAXRS 2 Spec
 * This resource supports transport like WebSocket, Streaming, JSONP and Long-Polling.
 *
 * @author Jeanfrancois Arcand
 */
@Path("/")
@Produces("application/json")
public class Jaxrs2Chat {

    // You can use that object to suspend as well.
    @Context
    ExecutionContext ctx;

    /**
     * Suspend the response without writing anything back to the client.
     * @return a white space
     */
    @Suspend()
    @GET
    public String suspend() {
        // ctx.suspend
        return "";
    }

    /**
     * Broadcast the received message object to all suspended response. Do not write back the message to the calling connection.
     * @param message a {@link Message}
     * @return a {@link Response}
     */
    @POST
    @Broadcast(writeEntity = false)
    public Response broadcast(Message message) {
        return new Response(message.author, message.message);
    }

}
