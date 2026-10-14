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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.atmosphere.agui.event.AgUiEvent;
import org.atmosphere.agui.event.AgUiEventMapper;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.AiPipeline;
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
 *
 * <p>RUN_FINISHED and RUN_ERROR are terminal, whichever path reaches one first
 * ({@link #complete()}, {@link #error(Throwable)}, or an {@link AiEvent.Complete}
 * or {@link AiEvent.Error} passed to {@link #emit(AiEvent)}): nothing is written after it, later
 * terminal calls are no-ops, and the wrapped session is completed or errored
 * exactly once. The state check and the frames it guards run under one lock,
 * so a concurrent emission cannot land after the terminal frame.</p>
 */
public final class AgUiStreamingSession implements StreamingSession {

    private static final Logger logger = LoggerFactory.getLogger(AgUiStreamingSession.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StreamingSession delegate;
    private final AgUiEventMapper eventMapper;
    private final AtmosphereResponse response;
    private final RunContext runContext;
    private final AiPipeline pipeline;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile boolean errored;

    public AgUiStreamingSession(StreamingSession delegate, AtmosphereResponse response,
                                RunContext runContext) {
        this(delegate, response, runContext, null);
    }

    /**
     * @param pipeline the pipeline {@link #stream(String)} runs a prompt
     *                 through, with this session receiving the run; may be
     *                 null, and {@code stream} then fails
     */
    public AgUiStreamingSession(StreamingSession delegate, AtmosphereResponse response,
                                RunContext runContext, AiPipeline pipeline) {
        this.delegate = delegate;
        this.eventMapper = new AgUiEventMapper();
        this.response = response;
        this.runContext = runContext;
        this.pipeline = pipeline;
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
        writeLock.lock();
        try {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            writeFinish(summary);
        } finally {
            writeLock.unlock();
        }
        delegate.complete(summary);
    }

    private void writeFinish(String summary) {
        // Close any in-flight tool call first (innermost scope): a ToolStart
        // with no ToolResult/ToolError before complete() would leave
        // TOOL_CALL_START dangling, and the mapper reset below would silently
        // drop the tracked id. The mapper emits TOOL_CALL_END only while a
        // tool call is open, so this is close-once like the text close below.
        for (var agUiEvent : eventMapper.closeOpenToolCall()) {
            writeSSE(agUiEvent);
        }
        // Close any in-flight AG-UI text message before finishing the run:
        // pipeline-routed dispatches end with complete() without a terminal
        // AiEvent.TextComplete, which would otherwise leave TEXT_MESSAGE_START
        // dangling. The mapper emits TEXT_MESSAGE_END only while a message is
        // open, so emitters that already sent an explicit TextComplete don't
        // get a duplicate END (close-once semantics; Mode Parity with
        // AgUiHandler.ResourceAgUiStreamingSession).
        for (var agUiEvent : eventMapper.toAgUi(
                new AiEvent.TextComplete(summary != null ? summary : ""))) {
            writeSSE(agUiEvent);
        }
        writeSSE(new AgUiEvent.RunFinished(runContext.runId(), runContext.threadId()));
        eventMapper.reset();
    }

    @Override
    public void error(Throwable t) {
        if (terminateWithError(AgUiEventMapper.runErrorMessage(t.getMessage(), t), null)) {
            delegate.error(t);
        }
    }

    /**
     * Writes RUN_ERROR and closes the session, unless a terminal frame was
     * already written.
     *
     * @return whether this call ended the run
     */
    private boolean terminateWithError(String message, String code) {
        writeLock.lock();
        try {
            if (!closed.compareAndSet(false, true)) {
                return false;
            }
            errored = true;
            // No TEXT_MESSAGE_END here: the AG-UI protocol permits RUN_ERROR
            // as a terminal hard-abort while a text message is open, and
            // fabricating an END would signal normal completion of a message
            // that failed mid-stream. The mapper reset drops the open-message
            // state.
            writeSSE(new AgUiEvent.RunError(runContext.runId(), message, code));
            eventMapper.reset();
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public boolean hasErrored() {
        return errored;
    }

    @Override
    public void sendContent(Content content) {
        delegate.sendContent(content);
    }

    @Override
    public void emit(AiEvent event) {
        if (event instanceof AiEvent.Complete done) {
            // RUN_FINISHED ends the run: an emitted Complete finishes it
            // exactly like complete(), as StreamingSession#emit routes it.
            complete(done.summary());
            return;
        }
        if (event instanceof AiEvent.Error error) {
            // RUN_ERROR ends the run: route it through the terminal path, as
            // StreamingSession#emit routes an Error to error(Throwable).
            var message = AgUiEventMapper.runErrorMessage(error.message(), null);
            if (terminateWithError(message, error.code())) {
                delegate.error(new IllegalStateException(message));
            }
            return;
        }
        writeLock.lock();
        try {
            if (closed.get()) {
                // Frames after RUN_FINISHED / RUN_ERROR are AG-UI protocol
                // violations — drop late emissions once the run is terminal.
                return;
            }
            for (var agUiEvent : eventMapper.toAgUi(event)) {
                writeSSE(agUiEvent);
            }
        } finally {
            writeLock.unlock();
        }
        delegate.emit(event);
    }

    /**
     * Runs {@code message} through the pipeline with this session receiving
     * the run, so its events reach the AG-UI wire, the same way the handler's
     * session streams.
     *
     * @throws UnsupportedOperationException when the session has no pipeline
     */
    @Override
    public void stream(String message) {
        AgUiHandler.streamThroughPipeline(pipeline, runContext, message, this);
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
        } catch (JacksonException e) {
            logger.warn("Failed to serialize AG-UI event: {}", event.type(), e);
        } catch (IOException e) {
            logger.debug("Failed to write AG-UI SSE frame", e);
        }
    }
}
