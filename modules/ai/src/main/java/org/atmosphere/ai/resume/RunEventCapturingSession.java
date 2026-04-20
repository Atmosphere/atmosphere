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

import org.atmosphere.ai.DelegatingStreamingSession;
import org.atmosphere.ai.StreamingSession;

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
}
