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
import org.atmosphere.agent.session.AgentSessionRegistry;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.processor.AiEndpointHandler;
import org.atmosphere.config.managed.Invoker;
import org.atmosphere.config.service.Message;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResourceHeartbeatEventListener;
import org.atmosphere.config.managed.Decoder;
import org.atmosphere.config.managed.Encoder;
import org.atmosphere.cpr.RawMessage;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.atmosphere.util.PayloadTooLargeException;
import org.atmosphere.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link AtmosphereHandler} that wraps an {@link AiEndpointHandler} with
 * command routing. Messages starting with "/" are routed to the
 * {@link CommandRouter}; all other messages fall through to the AI pipeline.
 */
public class AgentHandler extends AbstractReflectorAtmosphereHandler
        implements AtmosphereResourceHeartbeatEventListener, org.atmosphere.ai.HandoffTarget {

    private static final Logger logger = LoggerFactory.getLogger(AgentHandler.class);

    private final AiEndpointHandler aiDelegate;
    private final CommandRouter commandRouter;
    private final Method messageMethod;
    private final Object messageTarget;
    private final List<Encoder<?, ?>> messageEncoders;
    private final List<Decoder<?, ?>> messageDecoders;
    private String agentName;

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

        // Session tracking: register on first request, track messages on POST
        if (agentName != null) {
            var registry = AgentSessionRegistry.instance();
            var uuid = resource.uuid();
            if ("GET".equalsIgnoreCase(method)) {
                registry.sessionStarted(uuid, agentName, Utils.transportName(resource));
                resource.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                    @Override
                    public void onDisconnect(AtmosphereResourceEvent event) {
                        registry.sessionEnded(uuid);
                    }

                    @Override
                    public void onClose(AtmosphereResourceEvent event) {
                        registry.sessionEnded(uuid);
                    }
                });
            } else if ("POST".equalsIgnoreCase(method)) {
                registry.messageReceived(uuid);
            }
        }

        if ("POST".equalsIgnoreCase(method)) {
            // An SSE, streaming or long-polling POST arrives unread, a WebSocket frame
            // cached: read both the way the AI endpoint does, bounded, so a
            // command or @Message input is routed on every transport. The text
            // stays cached on the request for the AI endpoint below.
            String msg;
            try {
                msg = AiEndpointHandler.readPrompt(resource);
            } catch (PayloadTooLargeException | CharacterCodingException | UnsupportedEncodingException e) {
                aiDelegate.refuseUnreadablePrompt(resource, e);
                return;
            }
            if (msg != null) {
                // Try command routing first
                var clientId = resource.uuid();
                if (commandRouter.handles(clientId, msg)) {
                    // The answer goes to the sender's connection, which must be
                    // found before the command runs: when none resolves the
                    // message was refused (400 / 503 / an error frame), and a
                    // client that sends it again must not run it twice.
                    var sender = aiDelegate.resolveSender(resource);
                    if (sender.isEmpty()) {
                        return;
                    }
                    // The answer's session is started before the command runs,
                    // as a prompt's is: it reads, from the connection, the
                    // identity a long-polling reply is kept for, which a poll
                    // that ends while a slow command runs no longer yields.
                    var answer = new AtomicReference<StreamingSession>();
                    CommandResult result;
                    try {
                        result = commandRouter.route(clientId, msg, null,
                                () -> answer.set(StreamingSessions.start(sender.get())));
                    } catch (RuntimeException e) {
                        if (answer.get() != null) {
                            answer.get().error(e);
                        }
                        throw e;
                    }
                    switch (result) {
                        case CommandResult.Executed exec -> {
                            logger.debug("Command executed for client {}: {}",
                                    clientId, exec.response().substring(
                                            0, Math.min(exec.response().length(), 80)));
                            sendCommandResponse(answer.get(), exec.response());
                            return;
                        }
                        case CommandResult.ConfirmationRequired confirm -> {
                            logger.debug("Confirmation required for client {}: {}",
                                    clientId, confirm.prompt());
                            sendCommandResponse(answer.get(), confirm.prompt());
                            return;
                        }
                        case CommandResult.NotACommand ignored -> {
                            // A confirmation that expired meanwhile: fall through
                            // to @Message or the AI pipeline
                        }
                    }
                }

                // Try @Message handler before AI pipeline
                if (messageMethod != null) {
                    var handled = invokeMessageHandler(resource, msg);
                    if (handled) return;
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
    public boolean acceptHandoff(AtmosphereResource resource, String message) {
        return aiDelegate.acceptHandoff(resource, message);
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
     * Sends a command response to the requesting client only, never to the other
     * subscribers of the path, as a reply of its own: a {@code streaming-text}
     * frame and a {@code complete} frame under a fresh session id, so a client
     * that tracks frames by session id and sequence takes each command's answer
     * as a new reply.
     *
     * <p>{@code session} was started on the sender's connection ({@link
     * AiEndpointHandler#resolveSender}), not on the POST that carried the command,
     * whose response an SSE, streaming or long-polling client does not read,
     * before the command ran. The reply is delivered like an AI reply: through
     * the path broadcaster's filters to that connection over WebSocket, SSE and
     * streaming, and over long-polling kept whole for the client's next poll
     * made under the identity the connection had when the command arrived.</p>
     */
    private void sendCommandResponse(StreamingSession session, String response) {
        session.send(response);
        session.complete();
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

    /**
     * Set the agent name for session tracking. Called by the processor
     * after construction.
     */
    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    // visible for testing
    AiEndpointHandler aiDelegate() {
        return aiDelegate;
    }

    CommandRouter commandRouter() {
        return commandRouter;
    }
}
