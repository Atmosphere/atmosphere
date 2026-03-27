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
package org.atmosphere.agui.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.agui.event.AgUiEvent;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.Content;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link AtmosphereHandler} implementation for the AG-UI protocol. Handles
 * POST requests containing {@link RunContext} payloads, sets up SSE streaming,
 * and delegates to the {@link org.atmosphere.agui.annotation.AgUiAction @AgUiAction}
 * method on a virtual thread.
 *
 * <p>Uses Atmosphere's {@code resource.write()} for SSE delivery so that each
 * event is flushed immediately through the async I/O pipeline to the browser.</p>
 */
public final class AgUiHandler implements AtmosphereHandler {

    private static final Logger logger = LoggerFactory.getLogger(AgUiHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Object endpoint;
    private final Method actionMethod;
    private final org.atmosphere.ai.AiPipeline pipeline;

    public AgUiHandler(Object endpoint, Method actionMethod) {
        this(endpoint, actionMethod, null);
    }

    public AgUiHandler(Object endpoint, Method actionMethod,
                       org.atmosphere.ai.AiPipeline pipeline) {
        this.endpoint = endpoint;
        this.actionMethod = actionMethod;
        this.pipeline = pipeline;
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        var method = resource.getRequest().getMethod();

        if ("POST".equalsIgnoreCase(method)) {
            handlePost(resource);
        } else if ("GET".equalsIgnoreCase(method)) {
            handleGet(resource);
        } else {
            resource.getResponse().setStatus(405);
            resource.getResponse().getWriter().write("{\"error\":\"Method not allowed\"}");
        }
    }

    private void handlePost(AtmosphereResource resource) throws IOException {
        var request = resource.getRequest();
        var response = resource.getResponse();

        var reader = request.getReader();
        var sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }

        if (sb.isEmpty()) {
            response.setStatus(400);
            response.getWriter().write("{\"error\":\"Empty body\"}");
            return;
        }

        RunContext runContext;
        try {
            runContext = MAPPER.readValue(sb.toString(), RunContext.class);
        } catch (JsonProcessingException e) {
            response.setStatus(400);
            response.getWriter().write("{\"error\":\"Invalid request: " + e.getMessage() + "\"}");
            return;
        }

        if (runContext.runId() == null || runContext.runId().isBlank()) {
            runContext = new RunContext(
                    runContext.threadId() != null ? runContext.threadId() : UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    runContext.messages(), runContext.state(),
                    runContext.forwardedProps(), runContext.tools());
        }

        // Get the raw servlet response — bypass Atmosphere's response wrapper
        // so SSE frames flush directly to the browser's ReadableStream
        var servletResponse = (jakarta.servlet.http.HttpServletResponse) response.getResponse();
        servletResponse.setContentType("text/event-stream");
        servletResponse.setCharacterEncoding("UTF-8");
        servletResponse.setHeader("Cache-Control", "no-cache");
        servletResponse.setHeader("Connection", "keep-alive");
        servletResponse.setHeader("X-Accel-Buffering", "no");

        var servletWriter = servletResponse.getWriter();
        var sseWriter = new SseWriter(servletWriter);

        // Emit RunStarted immediately and flush
        sseWriter.write(new AgUiEvent.RunStarted(runContext.runId(), runContext.threadId()));

        // Execute on virtual thread
        var finalRunContext = runContext;
        Thread.ofVirtual().name("agui-run-" + runContext.runId()).start(() -> {
            try {
                var delegateSession = new NoOpStreamingSession();
                var session = new ResourceAgUiStreamingSession(
                        delegateSession, sseWriter, finalRunContext, pipeline);
                actionMethod.invoke(endpoint, finalRunContext, session);
                if (!session.isClosed()) {
                    session.complete();
                }
            } catch (Exception e) {
                logger.error("AG-UI action failed", e);
                sseWriter.write(new AgUiEvent.RunError(
                        finalRunContext.runId(),
                        e.getCause() != null ? e.getCause().getMessage() : e.getMessage(),
                        -1));
            } finally {
                try {
                    servletWriter.close();
                } catch (Exception ex) {
                    logger.trace("Failed to close servlet writer", ex);
                }
            }
        });
    }

    private void handleGet(AtmosphereResource resource) {
        var response = resource.getResponse();
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        resource.suspend();
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isClosedByClient() || event.isClosedByApplication()) {
            logger.debug("AG-UI connection closed: {}", event.getResource().uuid());
            return;
        }
        // resource.write() delivers data here — flush it to the client
        var message = event.getMessage();
        if (message instanceof String text) {
            var response = event.getResource().getResponse();
            response.getWriter().write(text);
            response.getWriter().flush();
        }
    }

    @Override
    public void destroy() {
        logger.debug("AgUiHandler destroyed");
    }

    /**
     * Writes SSE frames directly to the raw servlet response writer,
     * bypassing Atmosphere's async I/O to ensure immediate browser delivery.
     */
    static final class SseWriter {
        private final java.io.PrintWriter writer;

        SseWriter(java.io.PrintWriter writer) {
            this.writer = writer;
        }

        synchronized void write(AgUiEvent event) {
            try {
                var json = MAPPER.writeValueAsString(event);
                writer.write("event: " + event.type() + "\ndata: " + json + "\n\n");
                writer.flush();
            } catch (Exception e) {
                logger.debug("Failed to write AG-UI SSE event: {}", event.type(), e);
            }
        }
    }

    /**
     * AG-UI streaming session that uses {@link SseWriter} for proper browser delivery.
     */
    static final class ResourceAgUiStreamingSession implements StreamingSession {
        private final StreamingSession delegate;
        private final SseWriter writer;
        private final RunContext runContext;
        private final org.atmosphere.ai.AiPipeline pipeline;
        private final org.atmosphere.agui.event.AgUiEventMapper eventMapper =
                new org.atmosphere.agui.event.AgUiEventMapper();
        private final AtomicBoolean closed = new AtomicBoolean(false);

        ResourceAgUiStreamingSession(StreamingSession delegate, SseWriter writer,
                                     RunContext runContext) {
            this(delegate, writer, runContext, null);
        }

        ResourceAgUiStreamingSession(StreamingSession delegate, SseWriter writer,
                                     RunContext runContext,
                                     org.atmosphere.ai.AiPipeline pipeline) {
            this.delegate = delegate;
            this.writer = writer;
            this.runContext = runContext;
            this.pipeline = pipeline;
        }

        @Override public String sessionId() { return delegate.sessionId(); }

        @Override
        public void send(String text) {
            emit(new AiEvent.TextDelta(text));
        }

        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { emit(new AiEvent.Progress(message, null)); }

        @Override
        public void complete() {
            complete(null);
        }

        @Override
        public void complete(String summary) {
            if (closed.compareAndSet(false, true)) {
                writer.write(new AgUiEvent.RunFinished(runContext.runId(), runContext.threadId()));
                eventMapper.reset();
            }
        }

        @Override
        public void error(Throwable t) {
            if (closed.compareAndSet(false, true)) {
                writer.write(new AgUiEvent.RunError(runContext.runId(), t.getMessage(), -1));
                eventMapper.reset();
            }
        }

        @Override public boolean isClosed() { return closed.get(); }
        @Override public void sendContent(Content content) { }

        @Override
        public void emit(AiEvent event) {
            for (var agUiEvent : eventMapper.toAgUi(event)) {
                writer.write(agUiEvent);
            }
        }

        @Override
        public void stream(String message) {
            if (pipeline != null) {
                pipeline.execute(runContext.threadId(), message, this);
            } else {
                throw new UnsupportedOperationException(
                        "stream(String) requires an AiPipeline. "
                                + "Ensure the AG-UI handler is registered with a pipeline.");
            }
        }
    }

    private static final class NoOpStreamingSession implements StreamingSession {
        private final String id = UUID.randomUUID().toString();
        private final AtomicBoolean closed = new AtomicBoolean(false);

        @Override public String sessionId() { return id; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { closed.set(true); }
        @Override public void complete(String summary) { closed.set(true); }
        @Override public void error(Throwable t) { closed.set(true); }
        @Override public boolean isClosed() { return closed.get(); }
        @Override public void emit(AiEvent event) { }
        @Override public void sendContent(Content content) { }
    }
}
