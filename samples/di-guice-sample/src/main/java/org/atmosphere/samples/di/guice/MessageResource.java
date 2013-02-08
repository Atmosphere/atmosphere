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
package org.atmosphere.samples.di.guice;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.atmosphere.annotation.Broadcast;
import org.atmosphere.annotation.Suspend;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.jersey.Broadcastable;
import org.atmosphere.jersey.JerseyBroadcaster;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger logger = LoggerFactory.getLogger(MessageResource.class);

    @Inject
    Service service;

    @GET
    @Path("{name}")
    @Suspend(outputComments = true, resumeOnBroadcast = false, listeners = EventsLogger.class)
    public Broadcastable listen(@PathParam("name") String topic) throws JSONException {
        Broadcaster broadcaster = BroadcasterFactory.getDefault().lookup(JerseyBroadcaster.class, topic, true);
        logger.info("thread: {} LISTENING to '{}'", Thread.currentThread().getName(), broadcaster.getID());
        if (service == null) {
            throw new AssertionError();
        }
        return new Broadcastable(new JSONObject().put("from", "system").put("msg", "Connected !"), broadcaster);
    }

    @POST
    @Path("{name}")
    @Broadcast
    public Broadcastable publish(@PathParam("name") String topic, @FormParam("from") String from,
                                 @FormParam("msg") String message) throws JSONException {
        Broadcaster broadcaster = BroadcasterFactory.getDefault().lookup(JerseyBroadcaster.class, topic, true);
        logger.info("thread: {} PUBLISH to '{}' from {}: {}",
                new Object[]{Thread.currentThread().getName(), broadcaster.getID(), from, message});
        if (service == null) {
            throw new AssertionError();
        }
        return new Broadcastable(new JSONObject().put("from", from).put("msg", message), "", broadcaster);
    }

}
