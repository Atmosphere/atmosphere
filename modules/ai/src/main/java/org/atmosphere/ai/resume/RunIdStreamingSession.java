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
 * Wraps a streaming session so it reports a fixed {@code runId}, delegating all
 * output to the wrapped session. A crash-resume re-drive streams its
 * reconstructed output to the reconnected client through the delegate, while the
 * journaled seams resolve the replay scope from {@link #runId()} — the one handle
 * that reaches every runtime. The wrapped session itself need not know the run id
 * (a plain resource-bound session does not), so this adapter supplies it without
 * rebuilding the full {@code AiStreamingSession} machinery.
 *
 * @since 4.0
 */
public final class RunIdStreamingSession implements StreamingSession {

    private final StreamingSession delegate;
    private final String runId;

    public RunIdStreamingSession(StreamingSession delegate, String runId) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.runId = Objects.requireNonNull(runId, "runId");
    }

    @Override
    public Optional<String> runId() {
        return Optional.of(runId);
    }

    @Override
    public void emit(AiEvent event) {
        delegate.emit(event);
    }

    @Override
    public String sessionId() {
        return delegate.sessionId();
    }

    @Override
    public void send(String text) {
        delegate.send(text);
    }

    @Override
    public void sendMetadata(String key, Object value) {
        delegate.sendMetadata(key, value);
    }

    @Override
    public void progress(String message) {
        delegate.progress(message);
    }

    @Override
    public void complete() {
        delegate.complete();
    }

    @Override
    public void complete(String summary) {
        delegate.complete(summary);
    }

    @Override
    public void error(Throwable t) {
        delegate.error(t);
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }
}
