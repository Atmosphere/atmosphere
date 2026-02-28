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
package org.atmosphere.ai.processor;

import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiStreamingSession;
import org.atmosphere.ai.AiSupport;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * {@link AtmosphereHandler} that bridges an {@link org.atmosphere.ai.annotation.AiEndpoint}
 * annotated class to Atmosphere's lifecycle. Handles connect, disconnect, and message
 * events — delegating prompt handling to the user's {@code @Prompt} method on a virtual thread.
 */
public class AiEndpointHandler implements AtmosphereHandler {

    /**
     * Request attribute key for the system prompt configured on the {@code @AiEndpoint}.
     * The {@code @Prompt} method can retrieve this via
     * {@code resource.getRequest().getAttribute(AiEndpointHandler.SYSTEM_PROMPT_ATTRIBUTE)}.
     */
    public static final String SYSTEM_PROMPT_ATTRIBUTE = "org.atmosphere.ai.systemPrompt";

    private static final Logger logger = LoggerFactory.getLogger(AiEndpointHandler.class);

    private final Object target;
    private final Method promptMethod;
    private final int paramCount;
    private final long suspendTimeout;
    private final String systemPrompt;
    private final AiSupport aiSupport;
    private final List<AiInterceptor> interceptors;

    /**
     * @param target       the user's @AiEndpoint instance
     * @param promptMethod the @Prompt-annotated method
     * @param timeout      per-resource suspend timeout in milliseconds
     * @param systemPrompt the system prompt from the @AiEndpoint annotation (may be empty)
     * @param aiSupport    the resolved AI support implementation
     * @param interceptors the interceptor chain
     */
    public AiEndpointHandler(Object target, Method promptMethod, long timeout,
                             String systemPrompt, AiSupport aiSupport,
                             List<AiInterceptor> interceptors) {
        this.target = target;
        this.promptMethod = promptMethod;
        this.paramCount = promptMethod.getParameterCount();
        this.suspendTimeout = timeout;
        this.systemPrompt = systemPrompt != null ? systemPrompt : "";
        this.aiSupport = aiSupport;
        this.interceptors = interceptors != null ? interceptors : List.of();
        this.promptMethod.setAccessible(true);
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        var method = resource.getRequest().getMethod();

        // WebSocket frames arrive as POST requests via SimpleHttpProtocol.
        // Read the message body and dispatch to the @Prompt method.
        if ("POST".equalsIgnoreCase(method)) {
            // Read body directly from the request — IOUtils.readEntirely() checks the
            // internal request method (GET for WebSocket upgrades) and blocks the read.
            AtmosphereRequestImpl.Body body = resource.getRequest().body();
            if (body.isEmpty()) {
                return;
            }

            var userMessage = body.hasString() ? body.asString() : new String(body.asBytes());
            logger.info("Received prompt from {}: {}", resource.uuid(), userMessage);

            var delegate = StreamingSessions.start(resource);
            var session = new AiStreamingSession(delegate, aiSupport,
                    systemPrompt, null, interceptors, resource);

            Thread.startVirtualThread(() -> {
                try {
                    invokePrompt(userMessage, session, resource);
                } catch (Exception e) {
                    logger.error("Error invoking @Prompt method", e);
                    session.error(e);
                }
            });
            return;
        }

        // Initial connection: suspend the resource.
        if (resource.transport() == AtmosphereResource.TRANSPORT.WEBSOCKET
                || resource.transport() == AtmosphereResource.TRANSPORT.SSE
                || resource.transport() == AtmosphereResource.TRANSPORT.LONG_POLLING) {
            resource.suspend(suspendTimeout);
            if (!systemPrompt.isEmpty()) {
                resource.getRequest().setAttribute(SYSTEM_PROMPT_ATTRIBUTE, systemPrompt);
            }
            logger.info("Client {} connected to AI endpoint", resource.uuid());
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        var resource = event.getResource();

        if (event.isClosedByClient() || event.isClosedByApplication()) {
            logger.info("Client {} disconnected from AI endpoint", resource.uuid());
            return;
        }

        if (event.isCancelled()) {
            logger.info("Client {} unexpectedly disconnected from AI endpoint", resource.uuid());
            return;
        }

        var message = event.getMessage();
        if (message == null) {
            return;
        }

        // Unwrap RawMessage (broadcast from StreamingSession — tokens, progress, etc.)
        if (message instanceof RawMessage raw) {
            message = raw.message();
        }

        // Write broadcast content to the response (delivers tokens to the client).
        var response = resource.getResponse();
        response.write(message.toString());
        response.flushBuffer();
    }

    @Override
    public void destroy() {
        // no-op
    }

    private void invokePrompt(String message, StreamingSession session, AtmosphereResource resource)
            throws InvocationTargetException, IllegalAccessException {
        if (paramCount == 3) {
            promptMethod.invoke(target, message, session, resource);
        } else {
            promptMethod.invoke(target, message, session);
        }
    }

    // visible for testing
    Object target() {
        return target;
    }

    Method promptMethod() {
        return promptMethod;
    }

    long suspendTimeout() {
        return suspendTimeout;
    }

    String systemPrompt() {
        return systemPrompt;
    }

    AiSupport aiSupport() {
        return aiSupport;
    }

    List<AiInterceptor> interceptors() {
        return interceptors;
    }
}
