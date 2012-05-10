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

import org.atmosphere.annotation.Asynchronous;

import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import java.util.concurrent.Callable;

/**
 * Simple Asynchronous sample that demonstrate how task can be asynchronously executed. This class support all protocol
 * and use the {@link org.atmosphere.cpr.HeaderConfig#X_ATMOSPHERE_TRACKING_ID} to create a single {@link org.atmosphere.cpr.Broadcaster}
 * associated with the remote client.
 *
 * @author Jeanfrancois Arcand
 */
@Path("/async")
public class AsynchronousAnnotation {
    /**
     * Suspend the connection, wait for a broadcast events.
     * @return
     */
    @GET
    @Asynchronous
    public String subscribe() {
        return null;
    }

    /**
     * Asynchronously return a String.
     * @param message a message sent by the client.
     * @return a Callable that will resume the suspended connection if long-polling/jsonp is used, or keep-alive the
     * connection for websocket and http streaming.
     */
    @POST
    @Asynchronous
    public Callable<String> publish(final @FormParam("message") String message) {
        return new Callable<String>() {

            public String call() throws Exception {
                return message;
            }
        };
    }
}
