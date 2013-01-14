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

import org.atmosphere.config.service.ManagedService;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.handler.OnMessage;
import org.atmosphere.websocket.WebSocketEventListenerAdapter;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Date;

/**
 * Simple AtmosphereHandler that implement the logic to build a Chat application.
 *
 * @author Jeanfrancois Arcand
 */
@ManagedService(path = "/chat"
   /* Uncomment to receive connect/disconnect events for WebSocket */
   /*, listeners = {ChatAtmosphereHandler.WebSocketEventListener.class} */)
public class ChatAtmosphereHandler extends OnMessage<String> {

    private final static Logger logger = LoggerFactory.getLogger(ChatAtmosphereHandler.class);
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Echo the JSON Message we receives.
     * @param response an {@link AtmosphereResponse}
     * @param message  a message of type T
     * @throws IOException
     */
    @Override
    public void onMessage(AtmosphereResponse response, String message) throws IOException {
        response.getWriter().write(mapper.writeValueAsString(mapper.readValue(message, Data.class)));
    }

    /**
     * Simple listener for events.
     */
    public final static class WebSocketEventListener extends WebSocketEventListenerAdapter {
        @Override
        public void onConnect(WebSocketEvent event) {
            logger.debug("{}", event);
        }

        @Override
        public void onDisconnect(WebSocketEvent event) {
            logger.debug("{}", event);
        }
    }

    public final static class Data {

        private String message;
        private String author;
        private long time;

        public Data() {
            this("", "");
        }

        public Data(String author, String message) {
            this.author = author;
            this.message = message;
            this.time = new Date().getTime();
        }

        public String getMessage() {
            return message;
        }

        public String getAuthor() {
            return author;
        }

        public void setAuthor(String author) {
            this.author = author;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public long getTime() {
            return time;
        }

        public void setTime(long time) {
            this.time = time;
        }

    }
}
