/*
 * Copyright 2008-2026 Async-IO.org
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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.ObjectMapper;
import org.atmosphere.client.TrackMessageSizeInterceptor;
import org.atmosphere.config.service.WebSocketHandlerService;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.util.SimpleBroadcaster;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketEventListenerAdapter;
import org.atmosphere.websocket.WebSocketHandler;
import org.atmosphere.websocket.WebSocketStreamingHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Date;

/**
 * Simple {@link WebSocketHandler} that implement the logic to build a Chat application.
 *
 * <p>{@link TrackMessageSizeInterceptor} is installed because the Atmosphere Console (and any
 * atmosphere.js client with {@code trackMessageLength}) subscribes with
 * {@code X-Atmosphere-TrackMessageSize=true} and reads every frame, the protocol handshake
 * included, as {@code <length>|<message>}. Unlike {@code @ManagedService}, a
 * {@code @WebSocketHandlerService} installs no interceptor of its own, so without it the
 * client read the handshake's uuid as the length and ran with uuid {@code 0}: every
 * reconnect then created a new server resource.</p>
 *
 * @author Jeanfrancois Arcand
 */
@WebSocketHandlerService(path = "/chat", broadcaster = SimpleBroadcaster.class,
        interceptors = {TrackMessageSizeInterceptor.class},
        atmosphereConfig = {"org.atmosphere.websocket.WebSocketProtocol=org.atmosphere.websocket.protocol.StreamingHttpProtocol"})
public class WebSocketChat extends WebSocketStreamingHandlerAdapter {

    private final Logger logger = LoggerFactory.getLogger(WebSocketChat.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void onOpen(WebSocket webSocket) throws IOException {
        webSocket.resource().addEventListener(new WebSocketEventListenerAdapter() {
            @Override
            public void onDisconnect(AtmosphereResourceEvent event) {
                if (event.isCancelled()) {
                    logger.info("Browser {} unexpectedly disconnected", event.getResource().uuid());
                } else if (event.isClosedByClient()) {
                    logger.info("Browser {} closed the connection", event.getResource().uuid());
                }
            }
        });
    }

    @Override
    public void onTextStream(WebSocket webSocket, Reader reader) {
        try (BufferedReader br = new BufferedReader(reader)) {
            webSocket.broadcast(mapper.writeValueAsString(mapper.readValue(br.readLine(), Data.class)));
        } catch (Exception e) {
            logger.error("Failed to parse JSON", e);
        }
    }

    /**
     * JDK 25: Using record for immutable data transfer
     */
    public record Data(String author, String message, long time) {
        public Data() {
            this("", "", new Date().getTime());
        }

        @JsonCreator
        public Data(@JsonProperty("author") String author,
                    @JsonProperty("message") String message) {
            this(author, message, new Date().getTime());
        }
    }
}
