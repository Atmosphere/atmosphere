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
/**
 * This code was donated by Dan Vulpe https://github.com/dvulpe/atmosphere-ws-pubsub
 */
package org.atmosphere.samples.pubsub.config.protocol;

import org.atmosphere.config.service.WebSocketProtocolService;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.samples.pubsub.config.SpringApplicationContext;
import org.atmosphere.samples.pubsub.dto.BaseCommand;
import org.atmosphere.samples.pubsub.dto.Command;
import org.atmosphere.samples.pubsub.dto.EmptyCommand;
import org.atmosphere.samples.pubsub.services.ChatService;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.websocket.WebSocketProtocol;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

@WebSocketProtocolService
public class DelegatingWebSocketProtocol implements WebSocketProtocol {

    public static final Logger LOG = LoggerFactory.getLogger(DelegatingWebSocketProtocol.class);

    @Override
    public void configure(AtmosphereConfig atmosphereConfig) {
        // nothing needed
    }

    @Override
    public List<AtmosphereRequest> onMessage(WebSocket webSocket, String message) {
        if (webSocket.resource() == null) {
            return null;
        }
        AtmosphereResourceImpl resource = (AtmosphereResourceImpl) webSocket.resource();
        resource.suspend();

        ChatService chatService = SpringApplicationContext.getBean(ChatService.class);
        ObjectMapper mapper = SpringApplicationContext.getBean(ObjectMapper.class);
        Command command = readCommand(message, mapper);
        command.setResource(resource);
        chatService.execute(command);
        return null;
    }

    private Command readCommand(String s, ObjectMapper mapper) {
        Command command = new EmptyCommand();
        try {
            command = mapper.readValue(s, BaseCommand.class);
        } catch (IOException e) {
            LOG.error("Exception converting JSON:", e);
        }
        return command;
    }

    @Override
    public List<AtmosphereRequest> onMessage(WebSocket webSocket, byte[] bytes, int offset, int length) {
        return onMessage(webSocket, new String(bytes, offset, length));
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        LOG.debug("opened web socket connection {}", webSocket);
    }

    @Override
    public void onClose(WebSocket webSocket) {
        LOG.debug("closing web socket connection {}", webSocket);
    }

    @Override
    public void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException e) {
        LOG.error("error on websocket connection {}", e);
    }
}