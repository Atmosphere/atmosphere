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

import org.atmosphere.config.service.WebSocketHandlerService;
import org.atmosphere.util.SimpleBroadcaster;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketHandler;
import org.atmosphere.websocket.WebSocketHandlerAdapter;

/**
 * Simple PubSub resource that demonstrate many functionality supported by
 * Atmosphere JQuery Plugin and {@link WebSocketHandler} extension.  You can compare that implementation
 * with the MeteorPubSub, AtmosphereHandlerPubSub and the JQueryPubsub sample
 * <p/>
 * This sample support out of the box WebSocket ONLY
 *
 * @author Jeanfrancois Arcand
 */
@WebSocketHandlerService (path ="/pubsub", broadcaster = SimpleBroadcaster.class)
public class WebSocketPubSub extends WebSocketHandlerAdapter {

    @Override
    public void onTextMessage(WebSocket webSocket, String message) {
        webSocket.broadcast(message.substring("message=".length()));
    }

}
