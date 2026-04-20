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
package org.atmosphere.ai.resume;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.Content;
import org.atmosphere.ai.StreamingSession;

import java.util.Map;
import java.util.Objects;

/**
 * {@link StreamingSession} decorator that mirrors every outbound event
 * emitted during an {@code @Prompt} turn into the run's
 * {@link RunEventReplayBuffer}. This is the producer side of the
 * reattach wire — without it the buffer stays empty in production and
 * {@link RunReattachSupport#replayPendingRun} has nothing to drain on
 * reconnect.
 *
 * <p>The capture happens BEFORE the delegate write so the client sees
 * the live stream unchanged and a disconnect between the buffer write
 * and the transport write still preserves the event for replay.
 * Complement / error paths are captured too so reconnecting clients
 * can reconstruct the complete dialog state, including terminal
 * markers.</p>
 *
 * <p>Installed by {@link org.atmosphere.ai.processor.AiEndpointHandler}
 * immediately after registering the run in
 * {@link RunRegistryHolder}. The decorator chain is:</p>
 *
 * <pre>
 *   AiStreamingSession (the framework-level session)
 *     → RunEventCapturingSession (this class — writes to replay buffer)
 *     → @Prompt method consumers (session.send / session.complete ...)
 * </pre>
 *
 * <p>When the client disconnects mid-stream and reconnects carrying
 * {@code X-Atmosphere-Run-Id}, {@link RunReattachSupport} drains the
 * accumulated events onto the new resource so the client catches up on
 * what it missed.</p>
 */
public final class RunEventCapturingSession implements StreamingSession {

    private final StreamingSession delegate;
    private final RunEventReplayBuffer buffer;

    public RunEventCapturingSession(StreamingSession delegate, RunEventReplayBuffer buffer) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.buffer = Objects.requireNonNull(buffer, "buffer");
    }

    @Override
    public Map<Class<?>, Object> injectables() {
        return delegate.injectables();
    }

    @Override
    public String sessionId() {
        return delegate.sessionId();
    }

    @Override
    public java.util.Optional<String> runId() {
        return delegate.runId();
    }

    @Override
    public void send(String text) {
        if (text != null && !text.isEmpty()) {
            // Capture under the wire-protocol type name so the replay
            // path can emit a valid AiStreamMessage JSON frame without
            // a type-name translation step — "text" would not parse as
            // a streaming-text event on the client.
            buffer.capture("streaming-text", text);
        }
        delegate.send(text);
    }

    @Override
    public void sendContent(Content content) {
        // Binary / multi-modal content isn't captured for replay — reconnecting
        // clients re-materialise the logical thread from text + terminal
        // markers. Preserving raw bytes across a disconnect would inflate the
        // buffer beyond the documented size bound with frames that the
        // client almost certainly cannot correlate back to its UI state.
        delegate.sendContent(content);
    }

    @Override
    public void sendMetadata(String key, Object value) {
        // Metadata (ai.tokens.*, timing, trace ids) is not replayed —
        // semantic state that only makes sense in the live turn.
        delegate.sendMetadata(key, value);
    }

    @Override
    public void progress(String message) {
        // Progress markers are advisory; skipping keeps the buffer focused
        // on payload events that reconstruct the dialog.
        delegate.progress(message);
    }

    @Override
    public void complete() {
        buffer.capture("complete", "");
        delegate.complete();
    }

    @Override
    public void complete(String summary) {
        buffer.capture("complete", summary != null ? summary : "");
        delegate.complete(summary);
    }

    @Override
    public void error(Throwable t) {
        var msg = t != null && t.getMessage() != null ? t.getMessage() : "error";
        buffer.capture("error", msg);
        delegate.error(t);
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    @Override
    public boolean hasErrored() {
        return delegate.hasErrored();
    }

    @Override
    public void emit(AiEvent event) {
        // Typed events flow through the default StreamingSession.emit
        // fallback on the delegate, which in turn calls send / complete /
        // error on us (the wrapper) — so those hits are already captured.
        delegate.emit(event);
    }

    @Override
    public void stream(String message) {
        // stream() dispatches a NEW user turn through the runtime —
        // its events come back via send/complete on the same session
        // and are captured there. Forward without double-capture.
        delegate.stream(message);
    }

    @Override
    public void handoff(String agentName, String message) {
        // Handoff dispatches to another @Agent; the target agent's
        // session.send events flow back through the replay path when
        // the framework re-broadcasts completions. Forward without
        // capturing here — the default base method throws
        // UnsupportedOperationException, which would mask agent-backed
        // handoffs that the underlying session supports.
        delegate.handoff(agentName, message);
    }
}
