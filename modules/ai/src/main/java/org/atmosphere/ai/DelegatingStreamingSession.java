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
package org.atmosphere.ai;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Abstract base class for {@link StreamingSession} decorators that
 * forwards every method to a delegate by default. Subclasses override
 * only the methods they need to intercept, so any new method added to
 * {@link StreamingSession} is implicitly forwarded instead of silently
 * falling back to the default interface implementation — which would
 * shadow the underlying session's behaviour (the class of bug that
 * {@link org.atmosphere.ai.resume.RunEventCapturingSession} hit when
 * {@link StreamingSession#handoff(String, String)} was added: the
 * decorator inherited the default throw, masking the agent-backed
 * session's implementation).
 *
 * <p>Contract: if {@link StreamingSession} gains a method, this class
 * MUST get a forward override for it; the companion test
 * {@code DelegatingStreamingSessionContractTest} reflects over
 * {@code StreamingSession.class.getDeclaredMethods()} and fails the
 * build when a method is added upstream without a matching override
 * here. That is the entire point of the pattern — every decorator
 * inheriting from this class stops being the weakest link when the
 * interface evolves.</p>
 */
public abstract class DelegatingStreamingSession implements StreamingSession {

    protected final StreamingSession delegate;

    protected DelegatingStreamingSession(StreamingSession delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override public String sessionId() { return delegate.sessionId(); }
    @Override public Optional<String> runId() { return delegate.runId(); }
    @Override public Map<Class<?>, Object> injectables() { return delegate.injectables(); }
    @Override public void send(String text) { delegate.send(text); }
    @Override public void sendContent(Content content) { delegate.sendContent(content); }
    @Override public void sendMetadata(String key, Object value) { delegate.sendMetadata(key, value); }
    @Override public void usage(TokenUsage usage) { delegate.usage(usage); }
    @Override public void toolCallDelta(String toolCallId, String argsChunk) {
        delegate.toolCallDelta(toolCallId, argsChunk);
    }
    @Override public void progress(String message) { delegate.progress(message); }
    @Override public void complete() { delegate.complete(); }
    @Override public void complete(String summary) { delegate.complete(summary); }
    @Override public void error(Throwable t) { delegate.error(t); }
    @Override public boolean isClosed() { return delegate.isClosed(); }
    @Override public boolean hasErrored() { return delegate.hasErrored(); }
    @Override public void emit(AiEvent event) { delegate.emit(event); }
    @Override public void stream(String message) { delegate.stream(message); }
    @Override public void handoff(String agentName, String message) {
        delegate.handoff(agentName, message);
    }
    @Override public void close() {
        try {
            delegate.close();
        } catch (Exception e) {
            // StreamingSession's own close() default swallows
            // AutoCloseable's checked throw; match that contract so
            // subclasses don't have to declare throws Exception.
        }
    }
}
