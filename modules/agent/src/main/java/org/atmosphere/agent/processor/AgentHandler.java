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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.agent.command.CommandResult;
import org.atmosphere.agent.command.CommandRouter;
import org.atmosphere.ai.processor.AiEndpointHandler;
import org.atmosphere.config.managed.Invoker;
import org.atmosphere.config.service.Message;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceHeartbeatEventListener;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.config.managed.Decoder;
import org.atmosphere.config.managed.Encoder;
import org.atmosphere.cpr.RawMessage;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link AtmosphereHandler} that wraps an {@link AiEndpointHandler} with
 * command routing. Messages starting with "/" are routed to the
 * {@link CommandRouter}; all other messages fall through to the AI pipeline.
 */
public class AgentHandler extends AbstractReflectorAtmosphereHandler
        implements AtmosphereResourceHeartbeatEventListener {

    private static final Logger logger = LoggerFactory.getLogger(AgentHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiEndpointHandler aiDelegate;
    private final CommandRouter commandRouter;
    private final Method messageMethod;
    private final Object messageTarget;
    private final List<Encoder<?, ?>> messageEncoders;
    private final List<Decoder<?, ?>> messageDecoders;

    /**
     * @param aiDelegate    the AI endpoint handler for LLM pipeline
     * @param commandRouter the command router for "/" commands
     */
    public AgentHandler(AiEndpointHandler aiDelegate, CommandRouter commandRouter) {
        this(aiDelegate, commandRouter, null, null);
    }

    /**
     * @param aiDelegate    the AI endpoint handler for LLM pipeline
     * @param commandRouter the command router for "/" commands
     * @param messageTarget the agent instance for @Message invocation (may be null)
     * @param config        the atmosphere config for encoder/decoder instantiation (may be null)
     */
    @SuppressWarnings("unchecked")
    public AgentHandler(AiEndpointHandler aiDelegate, CommandRouter commandRouter,
                        Object messageTarget, org.atmosphere.cpr.AtmosphereConfig config) {
        this.aiDelegate = aiDelegate;
        this.commandRouter = commandRouter;
        this.messageTarget = messageTarget;

        // Scan for @Message method
        Method found = null;
        if (messageTarget != null) {
            for (var m : messageTarget.getClass().getMethods()) {
                if (m.isAnnotationPresent(Message.class)) {
                    found = m;
                    break;
                }
            }
        }
        this.messageMethod = found;

        // Instantiate encoders/decoders from the @Message annotation
        var enc = new ArrayList<Encoder<?, ?>>();
        var dec = new ArrayList<Decoder<?, ?>>();
        if (messageMethod != null && config != null) {
            var ann = messageMethod.getAnnotation(Message.class);
            for (var e : ann.encoders()) {
                try {
                    enc.add(config.framework().newClassInstance(Encoder.class, (Class<Encoder<?, ?>>) e));
                } catch (Exception ex) {
                    logger.warn("Failed to instantiate encoder {}: {}", e.getName(), ex.getMessage());
                }
            }
            for (var d : ann.decoders()) {
                try {
                    dec.add(config.framework().newClassInstance(Decoder.class, (Class<Decoder<?, ?>>) d));
                } catch (Exception ex) {
                    logger.warn("Failed to instantiate decoder {}: {}", d.getName(), ex.getMessage());
                }
            }
        }
        this.messageEncoders = List.copyOf(enc);
        this.messageDecoders = List.copyOf(dec);
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
                        sendCommandResponse(resource, exec.response());
                        return;
                    }
                    case CommandResult.ConfirmationRequired confirm -> {
                        logger.debug("Confirmation required for client {}: {}",
                                clientId, confirm.prompt());
                        sendCommandResponse(resource, confirm.prompt());
                        return;
                    }
                    case CommandResult.NotACommand ignored -> {
                        // Fall through to @Message or AI pipeline
                    }
                }

                // Try @Message handler before AI pipeline
                if (messageMethod != null) {
                    var handled = invokeMessageHandler(resource, msg);
                    if (handled) {
                        return;
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

    @Override
    public void onHeartbeat(AtmosphereResourceEvent event) {
        aiDelegate.onHeartbeat(event);
    }

    /**
     * Sends a command response directly to the requesting client only (unicast).
     * Uses {@code resource.write()} instead of broadcaster to avoid leaking
     * command responses to other subscribers on the same path.
     */
    private void sendCommandResponse(AtmosphereResource resource, String response) {
        var sessionId = "cmd-" + resource.uuid();
        try {
            var tokenJson = MAPPER.writeValueAsString(
                    Map.of("type", "streaming-text", "data", response,
                            "sessionId", sessionId, "seq", 0));
            resource.write(tokenJson);

            var completeJson = MAPPER.writeValueAsString(
                    Map.of("type", "complete", "sessionId", sessionId, "seq", 1));
            resource.write(completeJson);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize command response: {}", e.getMessage(), e);
        }
    }

    /**
     * Invokes the {@code @Message}-annotated method with decoder/encoder support.
     * Returns {@code true} if the message was handled (method returned non-null).
     */
    private boolean invokeMessageHandler(AtmosphereResource resource, String rawMessage) {
        try {
            Object decoded = Invoker.decode(messageDecoders, rawMessage);
            if (decoded == null) {
                decoded = rawMessage;
            }

            Object result;
            if (messageMethod.getParameterTypes().length == 2) {
                result = Invoker.invokeMethod(messageMethod, messageTarget, resource, decoded);
            } else {
                result = Invoker.invokeMethod(messageMethod, messageTarget, decoded);
            }

            if (result != null) {
                Object encoded = Invoker.encode(messageEncoders, result);
                if (encoded == null) {
                    encoded = result;
                }
                resource.getBroadcaster().broadcast(new RawMessage(encoded));
                return true;
            }
        } catch (Exception e) {
            logger.error("@Message handler failed: {}", e.getMessage(), e);
        }
        return false;
    }

    // visible for testing
    AiEndpointHandler aiDelegate() {
        return aiDelegate;
    }

    CommandRouter commandRouter() {
        return commandRouter;
    }
}
