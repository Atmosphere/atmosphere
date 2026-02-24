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
package org.atmosphere.wasync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.config.managed.Encoder;
import org.atmosphere.config.managed.Decoder;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Message;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Date;

/**
 * Chat handler for integration tests using {@link ManagedService}.
 * Supports all transports: WebSocket, SSE, streaming, and long-polling.
 */
@ManagedService(path = "/chat")
public class ChatHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);

    @Inject
    private AtmosphereResource resource;

    @Ready
    public void onReady() {
        logger.debug("Client connected: {} via {}", resource.uuid(), resource.transport());
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.debug("Client disconnected: {}", event.getResource().uuid());
    }

    @Message(encoders = {ChatEncoder.class}, decoders = {ChatDecoder.class})
    public ChatMessage onMessage(ChatMessage message) {
        logger.debug("{} sent: {}", message.author(), message.message());
        return message;
    }

    public record ChatMessage(String author, String message, long time) {
        public ChatMessage() {
            this("", "", new Date().getTime());
        }

        public ChatMessage(String author, String message) {
            this(author, message, new Date().getTime());
        }
    }

    public static class ChatEncoder implements Encoder<ChatMessage, String> {
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public String encode(ChatMessage m) {
            try {
                return mapper.writeValueAsString(m);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static class ChatDecoder implements Decoder<String, ChatMessage> {
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public ChatMessage decode(String s) {
            try {
                return mapper.readValue(s, ChatMessage.class);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
