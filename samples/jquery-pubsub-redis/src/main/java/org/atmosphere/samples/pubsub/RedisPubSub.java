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
package org.atmosphere.samples.pubsub;

import org.atmosphere.annotation.Broadcast;
import org.atmosphere.jersey.Broadcastable;
import org.atmosphere.jersey.SuspendResponse;
import org.atmosphere.plugin.redis.RedisBroadcaster;

import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;

/**
 * Simple Redis PubSub resource that demonstrate many functionality supported by
 * Atmosphere JQuery Plugin,  Atmosphere Jersey extension and Atmosphere Redis Plugin
 *
 * @author Jeanfrancois Arcand
 */
@Path("/pubsub/{topic}")
@Produces("text/html;charset=ISO-8859-1")
public class RedisPubSub {

    private
    @PathParam("topic")
    RedisBroadcaster topic;

    @GET
    public SuspendResponse<String> subscribe() {

        return new SuspendResponse.SuspendResponseBuilder<String>()
                .broadcaster(topic)
                .outputComments(true)
                .addListener(new EventsLogger())
                .build();
    }

    @POST
    @Broadcast
    public Broadcastable publish(@FormParam("message") String message) {
        return new Broadcastable(message, "", topic);
    }
}