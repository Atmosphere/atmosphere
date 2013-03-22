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

import org.atmosphere.config.service.Get;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Message;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;

import java.io.IOException;

/**
 * Simple PubSub resource that demonstrate many functionality supported by
 * Atmosphere JQuery Plugin and AtmosphereHandler extension.  You can compare that implementation
 * with the MeteorPubSub and the JQueryPubsub sample
 *
 * This sample support out of the box WebSocket, Long-Polling and Streaming
 *
 * @author Jeanfrancois Arcand
 */
@ManagedService(path = "/")
public class AtmosphereHandlerPubSub  {

    @Get
    public void onRequest(AtmosphereResource r) throws IOException {
        r.setBroadcaster(lookupBroadcaster(r.getRequest().getPathInfo()));
    }

    @Message
    public String onMessage(String message) throws IOException {
        if (message != null && message.indexOf("message") != -1) {
            return (message.substring("message=".length()));
        } else {
            return "=error=";
        }
    }

    /**
     * Retrieve the {@link Broadcaster} based on the request's path info.
     *
     * @param pathInfo
     * @return the {@link Broadcaster} based on the request's path info.
     */
    Broadcaster lookupBroadcaster(String pathInfo) {
        String[] decodedPath = pathInfo.split("/");
        Broadcaster b = BroadcasterFactory.getDefault().lookup(decodedPath[decodedPath.length - 1], true);
        return b;
    }

}
