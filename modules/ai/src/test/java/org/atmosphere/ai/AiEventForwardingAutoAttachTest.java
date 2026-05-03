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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the runtime-truth contract for {@link AiEventForwardingListener}'s
 * {@link AiEventForwardingListener#METADATA_KEY} auto-attach hook in
 * {@link AiPipeline}.
 *
 * <p>Why this test exists: the listener used to be an SPI shipped without a
 * production consumer (zero non-test grep hits outside its own file). Per
 * {@code feedback_primitive_needs_consumer.md}, "SPI presence ≠ runtime
 * presence" — to claim it is wired, a production code path must apply it.
 * The pipeline now reads {@code request.metadata().get(METADATA_KEY)} and
 * attaches a fresh listener bound to the live session when the flag is set;
 * this test pins that behavior on both sides (flag present → listener fires;
 * flag absent → no listener attached).</p>
 */
class AiEventForwardingAutoAttachTest {

    /**
     * Runtime that fires {@code onModelStart}/{@code onModelEnd} on the
     * captured context's listeners — same behavior the real
     * {@code OpenAiCompatibleClient} ships, but isolated so the test does not
     * need a network model.
     */
    static class LifecycleFiringRuntime implements AgentRuntime {
        final AtomicReference<AgentExecutionContext> lastContext = new AtomicReference<>();

        @Override public String name() { return "lifecycle-firing"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() { return Set.of(AiCapability.TEXT_STREAMING); }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            lastContext.set(context);
            AgentLifecycleListener.fireModelStart(context.listeners(), "test-model", 1, 0);
            session.send("hello");
            AgentLifecycleListener.fireModelEnd(context.listeners(), "test-model",
                    new TokenUsage(10, 5, 0, 15, "test-model"), 42L);
            session.complete();
        }
    }

    /** Records every {@code progress(...)} call so the test can assert wire-frame contents. */
    static class ProgressRecordingSession implements StreamingSession {
        final List<String> progressMessages = new ArrayList<>();
        final List<String> sent = new ArrayList<>();
        boolean completed;
        Throwable lastError;

        @Override public String sessionId() { return "test-session"; }
        @Override public void send(String text) { sent.add(text); }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { progressMessages.add(message); }
        @Override public void complete() { completed = true; }
        @Override public void complete(String summary) { completed = true; }
        @Override public void error(Throwable t) { lastError = t; }
        @Override public boolean isClosed() { return completed || lastError != null; }
    }

    @Test
    void flagPresentAttachesForwardingListenerSoModelLifecycleEventsHitTheWire() {
        var runtime = new LifecycleFiringRuntime();
        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                null, null, List.of(), List.of(), null);
        var session = new ProgressRecordingSession();

        pipeline.execute("client-1", "hello", session,
                Map.of(AiEventForwardingListener.METADATA_KEY, Boolean.TRUE));

        var ctx = runtime.lastContext.get();
        assertNotNull(ctx, "runtime must have been called");
        assertEquals(1, ctx.listeners().size(),
                "AiPipeline should have attached exactly one AiEventForwardingListener");
        assertTrue(ctx.listeners().get(0) instanceof AiEventForwardingListener,
                "attached listener must be AiEventForwardingListener");

        assertTrue(session.completed);
        assertEquals(2, session.progressMessages.size(),
                "expected one progress frame per model:start and model:end fire");
        assertTrue(session.progressMessages.get(0).startsWith("model:start"),
                "first progress frame should be model:start, was: " + session.progressMessages.get(0));
        assertTrue(session.progressMessages.get(1).startsWith("model:end"),
                "second progress frame should be model:end, was: " + session.progressMessages.get(1));
        assertTrue(session.progressMessages.get(1).contains("in=10"),
                "model:end should carry input token count: " + session.progressMessages.get(1));
        assertTrue(session.progressMessages.get(1).contains("out=5"),
                "model:end should carry output token count: " + session.progressMessages.get(1));
    }

    @Test
    void flagAbsentLeavesContextListenersEmptySoNoWireFramesAreEmitted() {
        var runtime = new LifecycleFiringRuntime();
        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                null, null, List.of(), List.of(), null);
        var session = new ProgressRecordingSession();

        pipeline.execute("client-1", "hello", session);

        var ctx = runtime.lastContext.get();
        assertNotNull(ctx);
        assertTrue(ctx.listeners().isEmpty(),
                "no listener should be attached when METADATA_KEY is absent");
        assertTrue(session.completed);
        assertTrue(session.progressMessages.isEmpty(),
                "no progress frames should be emitted without the listener");
        assertNull(session.lastError);
    }

    @Test
    void flagFalseDoesNotAttachListener() {
        var runtime = new LifecycleFiringRuntime();
        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                null, null, List.of(), List.of(), null);
        var session = new ProgressRecordingSession();

        pipeline.execute("client-1", "hello", session,
                Map.of(AiEventForwardingListener.METADATA_KEY, Boolean.FALSE));

        var ctx = runtime.lastContext.get();
        assertNotNull(ctx);
        assertTrue(ctx.listeners().isEmpty(),
                "Boolean.FALSE must be treated as opt-out (Boolean.TRUE-only check)");
        assertFalse(session.progressMessages.stream().anyMatch(m -> m.startsWith("model:")));
    }
}
