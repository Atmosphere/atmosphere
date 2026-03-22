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
package org.atmosphere.agent.processor;

import org.atmosphere.agent.command.CommandResult;
import org.atmosphere.agent.command.CommandRouter;
import org.atmosphere.ai.processor.AiEndpointHandler;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.RawMessage;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * {@link AtmosphereHandler} that wraps an {@link AiEndpointHandler} with
 * command routing. Messages starting with "/" are routed to the
 * {@link CommandRouter}; all other messages fall through to the AI pipeline.
 *
 * <p>Uses composition, not inheritance — the {@code AiEndpointHandler} is
 * a delegate, keeping the two concerns cleanly separated.</p>
 *
 * <h3>Message routing flow</h3>
 * <pre>
 * Message arrives at AgentHandler
 *     |
 *     +-- RawMessage -> delegate to AI handler (streaming response)
 *     |
 *     +-- "/" prefix -> CommandRouter.route()
 *     |   +-- Executed(response) -> broadcast response
 *     |   +-- ConfirmationRequired(prompt) -> broadcast prompt
 *     |   +-- NotACommand -> fall through to LLM
 *     |
 *     +-- "yes/y" with pending confirmation -> execute pending command
 *     |
 *     +-- else -> AiEndpointHandler (LLM pipeline)
 * </pre>
 */
public class AgentHandler extends AbstractReflectorAtmosphereHandler {

    private static final Logger logger = LoggerFactory.getLogger(AgentHandler.class);

    private final AiEndpointHandler aiDelegate;
    private final CommandRouter commandRouter;

    /**
     * @param aiDelegate    the AI endpoint handler for LLM pipeline
     * @param commandRouter the command router for "/" commands
     */
    public AgentHandler(AiEndpointHandler aiDelegate, CommandRouter commandRouter) {
        this.aiDelegate = aiDelegate;
        this.commandRouter = commandRouter;
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        var method = resource.getRequest().getMethod();

        if ("POST".equalsIgnoreCase(method)) {
            AtmosphereRequestImpl.Body body = resource.getRequest().body();
            if (!body.isEmpty()) {
                var msg = body.hasString() ? body.asString() : new String(body.asBytes());

                // Try command routing first
                var clientId = resource.uuid();
                var result = commandRouter.route(clientId, msg);

                switch (result) {
                    case CommandResult.Executed exec -> {
                        logger.debug("Command executed for client {}: {}",
                                clientId, exec.response().substring(
                                        0, Math.min(exec.response().length(), 80)));
                        broadcastCommandResponse(resource, exec.response());
                        return;
                    }
                    case CommandResult.ConfirmationRequired confirm -> {
                        logger.debug("Confirmation required for client {}: {}",
                                clientId, confirm.prompt());
                        broadcastCommandResponse(resource, confirm.prompt());
                        return;
                    }
                    case CommandResult.NotACommand ignored -> {
                        // Fall through to AI pipeline
                    }
                }
            }
        }

        // Delegate to the AI endpoint handler for connection setup and LLM
        aiDelegate.onRequest(resource);
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        aiDelegate.onStateChange(event);
    }

    @Override
    public void destroy() {
        aiDelegate.destroy();
    }

    /**
     * Broadcasts a command response to the client as a complete AI-style message.
     * Uses the standard streaming session wire protocol so the client renders
     * command responses the same way as AI responses.
     */
    private void broadcastCommandResponse(AtmosphereResource resource, String response) {
        var broadcaster = resource.getBroadcaster();
        // Format as a complete streaming response:
        // send the text + complete signal via RawMessage
        var json = "{\"type\":\"token\",\"text\":" + escapeJson(response) + ","
                + "\"sessionId\":\"cmd-" + resource.uuid() + "\",\"seq\":0}";
        broadcaster.broadcast(new RawMessage(json));

        var complete = "{\"type\":\"complete\",\"sessionId\":\"cmd-" + resource.uuid() + "\",\"seq\":1}";
        broadcaster.broadcast(new RawMessage(complete));
    }

    private String escapeJson(String text) {
        var sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    // visible for testing
    AiEndpointHandler aiDelegate() {
        return aiDelegate;
    }

    CommandRouter commandRouter() {
        return commandRouter;
    }
}
