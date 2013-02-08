/*
 * Copyright 2013 Jeanfrancois Arcand
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
package org.atmosphere.samples.pubsub;

import org.atmosphere.annotation.Publish;
import org.atmosphere.annotation.Subscribe;

import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

/**
 * Simple Resource acting like a channel of communication.
 *
 * @author Jeanfrancois Arcand
 */
@Path("/")
@Produces("text/plain;charset=ISO-8859-1")
public class TypedChannel {
    /**
     * Subscribe to "channel" distribution. With comet, the http connection will be suspended. With websocket,
     * the handshake will gets executed.
     *
     * @return null - no message is send back to browser yet.
     */
    @GET
    @Subscribe("channel")
    public String handshake() {
        return null;
    }

    /**
     * Broadcast messages to the "channel" distribution. This method gets invoked when a WebSocket message arrive and distributed to
     * all websocket connection that has invoked the handshake method. Note that comet application will works as well
     * when sending POST.
     *
     * @param message the message to distribute
     * @return the message that will be send to the "channel" distribution.
     */
    @POST
    @Publish("channel")
    public String onMessage(@FormParam("message") String message) {
        return message;
    }
} 
