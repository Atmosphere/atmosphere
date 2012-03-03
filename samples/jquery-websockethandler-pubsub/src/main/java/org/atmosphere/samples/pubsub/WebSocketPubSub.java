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

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketEventListenerAdapter;
import org.atmosphere.websocket.WebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple PubSub resource that demonstrate many functionality supported by
 * Atmosphere JQuery Plugin and {@link WebSocketHandler} extension.  You can compare that implementation
 * with the MeteorPubSub, AtmosphereHandlerPubSub and the JQueryPubsub sample
 * <p/>
 * This sample support out of the box WebSocket ONLY
 *
 * @author Jeanfrancois Arcand
 */
public class WebSocketPubSub extends WebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketPubSub.class);

    @Override
    public void onTextMessage(WebSocket webSocket, String message) {
        AtmosphereResource r = (AtmosphereResource) webSocket.resource();
        Broadcaster b = lookupBroadcaster(r.getRequest().getPathInfo());

        if (message != null && message.indexOf("message") != -1) {
            b.broadcast(message.substring("message=".length()));
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        // Accept the handshake by suspending the response.
        AtmosphereResource r = webSocket.resource();
        // Create a Broadcaster based on the path
        Broadcaster b = lookupBroadcaster(r.getRequest().getPathInfo());
        r.setBroadcaster(b);
        r.addEventListener(new WebSocketEventListenerAdapter());
        r.suspend(-1);
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
