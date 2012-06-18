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
package org.atmosphere.samples.chat;

import org.atmosphere.config.service.WebSocketHandlerService;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketHandler;

import java.util.Date;

/**
 * Simple {@link WebSocketHandler} that implement the logic to build a Chat application.
 *
 * @author Jeanfrancois Arcand
 */
@WebSocketHandlerService
public class WebSocketChat extends WebSocketHandler {

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.resource().setBroadcaster(BroadcasterFactory.getDefault().lookup("/chat", true)).suspend(-1);
    }

    public void onTextMessage(WebSocket webSocket, String message) {

        AtmosphereResource r = webSocket.resource();
        Broadcaster b = r.getBroadcaster();

        // Simple JSON -- Use Jackson for more complex structure
        // Message looks like { "author" : "foo", "message" : "bar" }
        String author = message.substring(message.indexOf(":") + 2, message.indexOf(",") - 1);
        String chat = message.substring(message.lastIndexOf(":") + 2, message.length() - 2);

        b.broadcast(new Data(author, chat).toString());
    }

    private final static class Data {

        private final String text;
        private final String author;

        public Data(String author, String text) {
            this.author = author;
            this.text = text;
        }

        public String toString() {
            return "{ \"text\" : \"" + text + "\", \"author\" : \"" + author
                    + "\" , \"time\" : " + new Date().getTime() + "}";
        }
    }
}
