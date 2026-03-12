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
package org.atmosphere.mcp;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Atmosphere handler that receives tool call responses from clients
 * and routes them to the {@link BiDirectionalToolBridge}.
 *
 * <p>Register this handler on the {@code /_mcp/tool-response} endpoint:</p>
 * <pre>{@code
 * framework.addAtmosphereHandler("/_mcp/tool-response",
 *     new ToolResponseHandler(bridge));
 * }</pre>
 */
public class ToolResponseHandler implements AtmosphereHandler {

    private static final Logger logger = LoggerFactory.getLogger(ToolResponseHandler.class);

    private final BiDirectionalToolBridge bridge;

    public ToolResponseHandler(BiDirectionalToolBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        var request = resource.getRequest();
        var body = readBody(request);
        if (body != null && !body.isBlank()) {
            logger.debug("Received tool response: {}", body);
            bridge.completePendingCall(body);
        }
        resource.resume();
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isClosedByClient()) {
            return;
        }
        // Handle WebSocket message frames
        var message = event.getMessage();
        if (message instanceof String text && !text.isBlank()) {
            logger.debug("Received tool response via WebSocket: {}", text);
            bridge.completePendingCall(text);
        }
    }

    @Override
    public void destroy() {
        // no-op
    }

    private static String readBody(AtmosphereRequest request) throws IOException {
        try (var reader = new BufferedReader(
                new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8))) {
            var sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }
}
