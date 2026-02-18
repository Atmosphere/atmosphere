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
package org.atmosphere.mcp.runtime;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * {@link AtmosphereHandler} that bridges Atmosphere's transport layer with the
 * MCP JSON-RPC protocol. Registered at the path specified by {@code @McpServer}.
 */
public final class McpHandler implements AtmosphereHandler {

    private static final Logger logger = LoggerFactory.getLogger(McpHandler.class);

    private final McpProtocolHandler protocolHandler;

    public McpHandler(McpProtocolHandler protocolHandler) {
        this.protocolHandler = protocolHandler;
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        // Suspend on GET (WebSocket upgrade or SSE)
        if ("GET".equalsIgnoreCase(resource.getRequest().getMethod())) {
            resource.suspend();
            return;
        }

        // POST — SSE transport sends requests via HTTP POST
        var reader = resource.getRequest().getReader();
        var sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        if (!sb.isEmpty()) {
            processMessage(resource, sb.toString());
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        var resource = event.getResource();
        if (event.isCancelled() || event.isClosedByClient() || event.isClosedByApplication()) {
            logger.debug("MCP connection closed: {}", resource.uuid());
            return;
        }

        var message = event.getMessage();
        if (message instanceof String text) {
            // Incoming WebSocket message
            var response = protocolHandler.handleMessage(resource, text);
            if (response != null) {
                write(resource.getResponse(), response);
            }
        } else if (message instanceof List<?> list) {
            // Broadcast — write each message
            for (var item : list) {
                if (item instanceof String text) {
                    write(resource.getResponse(), text);
                }
            }
        }
    }

    /**
     * Called directly for WebSocket messages (bypasses onStateChange for request-response).
     */
    public void processMessage(AtmosphereResource resource, String message) throws IOException {
        var response = protocolHandler.handleMessage(resource, message);
        if (response != null) {
            write(resource.getResponse(), response);
        }
    }

    @Override
    public void destroy() {
        logger.debug("McpHandler destroyed");
    }

    private void write(AtmosphereResponse response, String data) throws IOException {
        response.getWriter().write(data);
        response.getWriter().flush();
    }
}
