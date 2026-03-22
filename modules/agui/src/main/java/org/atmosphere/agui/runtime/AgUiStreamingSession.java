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
import org.atmosphere.agui.event.AgUiEventMapper;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.Content;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.cpr.AtmosphereResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Decorator around a {@link StreamingSession} that translates {@link AiEvent}
 * emissions into AG-UI SSE frames written to the HTTP response.
 *
 * <p>Each AG-UI event is serialized as a standard SSE frame:</p>
 * <pre>
 * event: TEXT_MESSAGE_CONTENT
 * data: {"messageId":"msg-1","delta":"Hello"}
 *
 * </pre>
 *
 * <p>Lifecycle events ({@link AgUiEvent.RunStarted}, {@link AgUiEvent.RunFinished},
 * {@link AgUiEvent.RunError}) are emitted automatically by the handler and this
 * session's {@link #complete()} / {@link #error(Throwable)} methods.</p>
 */
public final class AgUiStreamingSession implements StreamingSession {

    private static final Logger logger = LoggerFactory.getLogger(AgUiStreamingSession.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StreamingSession delegate;
    private final AgUiEventMapper eventMapper;
    private final AtmosphereResponse response;
    private final RunContext runContext;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public AgUiStreamingSession(StreamingSession delegate, AtmosphereResponse response,
                                RunContext runContext) {
        this.delegate = delegate;
        this.eventMapper = new AgUiEventMapper();
        this.response = response;
        this.runContext = runContext;
    }

    @Override
    public String sessionId() {
        return delegate.sessionId();
    }

    @Override
    public void send(String text) {
        emit(new AiEvent.TextDelta(text));
    }

    @Override
    public void sendMetadata(String key, Object value) {
        delegate.sendMetadata(key, value);
    }

    @Override
    public void progress(String message) {
        emit(new AiEvent.Progress(message, null));
    }

    @Override
    public void complete() {
        complete(null);
    }

    @Override
    public void complete(String summary) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        writeSSE(new AgUiEvent.RunFinished(runContext.runId(), runContext.threadId()));
        eventMapper.reset();
        delegate.complete(summary);
    }

    @Override
    public void error(Throwable t) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        writeSSE(new AgUiEvent.RunError(runContext.runId(), t.getMessage(), -1));
        eventMapper.reset();
        delegate.error(t);
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void sendContent(Content content) {
        delegate.sendContent(content);
    }

    @Override
    public void emit(AiEvent event) {
        for (var agUiEvent : eventMapper.toAgUi(event)) {
            writeSSE(agUiEvent);
        }
        delegate.emit(event);
    }

    @Override
    public void stream(String message) {
        delegate.stream(message);
    }

    /**
     * Returns the {@link RunContext} associated with this streaming session.
     */
    public RunContext runContext() {
        return runContext;
    }

    private void writeSSE(AgUiEvent event) {
        try {
            var json = MAPPER.writeValueAsString(event);
            var sseFrame = "event: " + event.type() + "\ndata: " + json + "\n\n";
            writeLock.lock();
            try {
                response.getWriter().write(sseFrame);
                response.getWriter().flush();
            } finally {
                writeLock.unlock();
            }
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize AG-UI event: {}", event.type(), e);
        } catch (IOException e) {
            logger.debug("Failed to write AG-UI SSE frame", e);
        }
    }
}
