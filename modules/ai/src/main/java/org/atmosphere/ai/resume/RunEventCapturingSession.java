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
import org.atmosphere.ai.DelegatingStreamingSession;
import org.atmosphere.ai.RunCancelledException;
import org.atmosphere.ai.StreamingSession;

import java.util.Objects;

/**
 * {@link StreamingSession} decorator that mirrors the text and the terminal
 * frame a client receives into the run's {@link RunEventReplayBuffer}. This is
 * the producer side of the reattach wire — without it the buffer stays empty in
 * production and {@link RunReattachSupport#replayPendingRun} has nothing to
 * drain on reconnect.
 *
 * <p>The endpoint handler installs it directly above the connection's leaf
 * session, below {@code AiStreamingSession}, where every outbound frame of the
 * run passes: what the {@code @Prompt} method sends itself and what the runtime
 * streams for {@code session.stream(...)} alike. Text arrives as
 * {@code send}, {@code sendContent} with a text part, or an
 * {@link AiEvent.TextDelta}; the terminal frame as {@code complete},
 * {@code error} (a {@link RunCancelledException} is captured as the
 * {@code cancelled} frame), {@link AiEvent.Complete} or {@link AiEvent.Error}. Other
 * frames (metadata, progress, tool events) are not captured.</p>
 *
 * <p>The capture happens BEFORE the delegate write so the client sees
 * the live stream unchanged and a disconnect between the buffer write
 * and the transport write still preserves the event for replay.
 * Complement / error paths are captured too so reconnecting clients
 * can reconstruct the complete dialog state, including terminal
 * markers.</p>
 *
 * <p>Extends {@link DelegatingStreamingSession} so every interface
 * method that this decorator does not explicitly override is forwarded
 * automatically. The class originally hand-wrote each delegation and
 * missed {@code handoff()} — the trap the base class exists to
 * prevent.</p>
 */
public final class RunEventCapturingSession extends DelegatingStreamingSession {

    private final RunEventReplayBuffer buffer;

    public RunEventCapturingSession(StreamingSession delegate, RunEventReplayBuffer buffer) {
        super(delegate);
        this.buffer = Objects.requireNonNull(buffer, "buffer");
    }

    @Override
    public void send(String text) {
        if (captureText(text)) {
            return;
        }
        delegate.send(text);
    }

    @Override
    public void sendContent(Content content) {
        if (content instanceof Content.Text text && captureText(text.text())) {
            return;
        }
        delegate.sendContent(content);
    }

    @Override
    public void emit(AiEvent event) {
        switch (event) {
            case AiEvent.TextDelta delta -> {
                if (captureText(delta.text())) {
                    return;
                }
            }
            case AiEvent.Complete complete ->
                    buffer.terminate("complete", complete.summary() != null ? complete.summary() : "");
            case AiEvent.Error error ->
                    buffer.terminate("error", error.message() != null ? error.message() : "error");
            default -> {
                // Not a text or terminal frame: forwarded, not captured.
            }
        }
        delegate.emit(event);
    }

    /**
     * Capture a text frame under the wire-protocol type name, so the replay
     * path can emit a valid AiStreamMessage JSON frame without a type-name
     * translation step — "text" would not parse as a streaming-text event on
     * the client.
     *
     * @return {@code true} when the client this session wrote to is gone: the
     *         event is in the buffer, which is how a reattached client receives
     *         it, and writing to the closed session would only log a WARN per
     *         remaining chunk
     */
    private boolean captureText(String text) {
        if (text != null && !text.isEmpty()) {
            buffer.capture("streaming-text", text);
        }
        return delegate.isClosed();
    }

    // Terminal events go through terminate: the first one ends the run, so a
    // run that already timed out or completed does not record a second.

    @Override
    public void complete() {
        buffer.terminate("complete", "");
        delegate.complete();
    }

    @Override
    public void complete(String summary) {
        buffer.terminate("complete", summary != null ? summary : "");
        delegate.complete(summary);
    }

    @Override
    public void error(Throwable t) {
        if (t instanceof RunCancelledException cancelled) {
            // The run was cut short: a reattached client gets the same
            // cancelled frame the live client got.
            buffer.terminate(RunCancelledException.FRAME_TYPE, cancelled.reason().wire());
        } else {
            buffer.terminate("error", t == null ? "error"
                    : t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        }
        delegate.error(t);
    }
}
