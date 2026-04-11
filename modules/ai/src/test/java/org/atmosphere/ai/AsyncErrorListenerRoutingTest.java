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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: AbstractAgentRuntime.executeWithHandle fired {@code onCompletion}
 * whenever the runtime's done future completed normally, even if the bridge
 * had emitted an error through {@code session.error(t)} and resolved the
 * future to {@code null}. Spring AI's reactive bridge and ADK's async
 * callbacks can end up in exactly that state — a drained stream reporting
 * failure via the session while the handle's future finishes cleanly.
 *
 * <p>Fix: consult {@link StreamingSession#hasErrored()} before firing
 * {@code onCompletion}. This test drives a fake runtime whose
 * {@code doExecuteWithHandle} calls {@code session.error(...)} and returns a
 * normally-completed handle, asserting the listener observed {@code onError}
 * and NOT {@code onCompletion}.</p>
 */
class AsyncErrorListenerRoutingTest {

    private static final class FailingRuntime extends AbstractAgentRuntime<String> {
        @Override public String name() { return "failing"; }
        @Override public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING);
        }
        @Override protected String nativeClientClassName() {
            return "java.lang.String";
        }
        @Override protected String createNativeClient(AiConfig.LlmSettings settings) {
            return "stub-client";
        }
        @Override protected String clientDescription() { return "stub"; }
        @Override public boolean isAvailable() { return true; }

        @Override
        protected void doExecute(String client, AgentExecutionContext context, StreamingSession session) {
            session.error(new java.io.IOException("upstream went away"));
        }

        @Override
        protected ExecutionHandle doExecuteWithHandle(String client,
                                                      AgentExecutionContext context,
                                                      StreamingSession session) {
            session.error(new java.io.IOException("upstream went away"));
            // Return the default pre-completed handle — mimics a bridge that
            // drained the reactive pipeline, emitted the error via the session,
            // and then resolved its internal whenDone() future normally.
            return ExecutionHandle.completed();
        }
    }

    private static final class RecordingListener implements AgentLifecycleListener {
        final AtomicBoolean completionFired = new AtomicBoolean();
        final AtomicReference<Throwable> errorFired = new AtomicReference<>();
        @Override public void onCompletion(AgentExecutionContext ctx) { completionFired.set(true); }
        @Override public void onError(AgentExecutionContext ctx, Throwable t) { errorFired.set(t); }
    }

    private static AgentExecutionContext ctxWith(AgentLifecycleListener listener) {
        return new AgentExecutionContext(
                "hello", null, "fake", null, null, null, null,
                List.of(), null, null, List.of(), java.util.Map.of(),
                List.of(), null, null, List.of(listener));
    }

    /** Minimal session that tracks error state just like DefaultStreamingSession. */
    private static final class ErroringSession implements StreamingSession {
        private volatile boolean errored;
        private volatile boolean closed;
        @Override public String sessionId() { return "test"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { closed = true; }
        @Override public void complete(String summary) { closed = true; }
        @Override public void error(Throwable t) { errored = true; closed = true; }
        @Override public boolean isClosed() { return closed; }
        @Override public boolean hasErrored() { return errored; }
    }

    @Test
    void synchronousExecuteRoutesAsyncErrorToListener() {
        var runtime = new FailingRuntime();
        var listener = new RecordingListener();
        var ctx = ctxWith(listener);
        var session = new ErroringSession();

        runtime.execute(ctx, session);

        assertFalse(listener.completionFired.get(),
                "onCompletion must NOT fire when session.error(...) was called");
        assertNotNull(listener.errorFired.get(),
                "onError must fire when session.error(...) was called");
        assertTrue(session.hasErrored());
    }

    @Test
    void executeWithHandleRoutesAsyncErrorToListener() {
        var runtime = new FailingRuntime();
        var listener = new RecordingListener();
        var ctx = ctxWith(listener);
        var session = new ErroringSession();

        var handle = runtime.executeWithHandle(ctx, session);
        handle.whenDone().join();

        assertFalse(listener.completionFired.get(),
                "onCompletion must NOT fire when the handle finishes normally "
                        + "but the session has errored out of band");
        assertNotNull(listener.errorFired.get());
    }
}
