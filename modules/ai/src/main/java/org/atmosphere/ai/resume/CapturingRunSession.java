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
import org.atmosphere.ai.StreamingSession;

import java.util.Objects;
import java.util.Optional;

/**
 * A non-streaming sink that reports a fixed {@code runId} and accumulates the
 * re-driven text, for callers with no live client to stream to — chiefly an
 * admin-triggered re-drive, where the value is finalizing the run and capturing
 * its reconstructed answer rather than pushing frames to a UI.
 *
 * @since 4.0
 */
public final class CapturingRunSession implements StreamingSession {

    private final String runId;
    private final StringBuilder text = new StringBuilder();
    private volatile boolean closed;

    public CapturingRunSession(String runId) {
        this.runId = Objects.requireNonNull(runId, "runId");
    }

    /** The full text re-driven for the run. */
    public String text() {
        return text.toString();
    }

    @Override
    public Optional<String> runId() {
        return Optional.of(runId);
    }

    @Override
    public void emit(AiEvent event) {
        // The admin sink does not forward frames; effects are already journaled.
    }

    @Override
    public String sessionId() {
        return "admin-resume:" + runId;
    }

    @Override
    public synchronized void send(String chunk) {
        if (chunk != null) {
            text.append(chunk);
        }
    }

    @Override
    public void sendMetadata(String key, Object value) {
        // no metadata channel for an admin re-drive
    }

    @Override
    public void progress(String message) {
        // no progress channel for an admin re-drive
    }

    @Override
    public void complete() {
        closed = true;
    }

    @Override
    public void complete(String summary) {
        closed = true;
    }

    @Override
    public void error(Throwable t) {
        closed = true;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }
}
