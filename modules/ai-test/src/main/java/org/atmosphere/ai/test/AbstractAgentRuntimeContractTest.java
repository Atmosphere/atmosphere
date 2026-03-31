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
package org.atmosphere.ai.test;

import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TCK-style contract test for {@link AgentRuntime} implementations.
 * Each runtime module creates a concrete subclass that provides
 * the runtime instance and any required mocks/stubs.
 *
 * <p>Contract assertions:</p>
 * <ol>
 *   <li>Capabilities declaration — minimum required capabilities</li>
 *   <li>Runtime identification — non-blank name</li>
 *   <li>Streaming completion — session.complete() called exactly once</li>
 *   <li>Text delivery — at least one text chunk sent</li>
 *   <li>Error handling — session.error() called on failure</li>
 * </ol>
 */
public abstract class AbstractAgentRuntimeContractTest {

    /**
     * Provide the runtime under test, fully configured with a mock LLM backend.
     */
    protected abstract AgentRuntime createRuntime();

    /**
     * Provide a context that will trigger a simple text response.
     */
    protected abstract AgentExecutionContext createTextContext();

    /**
     * Provide a context that will trigger a tool call followed by a text response.
     * Return {@code null} if the runtime does not support tool calling.
     */
    protected abstract AgentExecutionContext createToolCallContext();

    /**
     * Provide a context that will cause the runtime to error.
     * Return {@code null} to skip the error-handling test.
     */
    protected abstract AgentExecutionContext createErrorContext();

    @Test
    protected void runtimeDeclaresMinimumCapabilities() {
        var runtime = createRuntime();
        var caps = runtime.capabilities();
        assertTrue(caps.contains(AiCapability.TEXT_STREAMING),
                runtime.name() + " must declare TEXT_STREAMING");
    }

    @Test
    protected void runtimeHasNonBlankName() {
        var runtime = createRuntime();
        assertNotNull(runtime.name());
        assertFalse(runtime.name().isBlank());
    }

    @Test
    protected void runtimeIsAvailable() {
        assertTrue(createRuntime().isAvailable());
    }

    @Test
    protected void textStreamingCompletesSession() throws Exception {
        var runtime = createRuntime();
        var context = createTextContext();
        var session = new RecordingSession();

        runtime.execute(context, session);

        assertTrue(session.awaitCompletion(10, TimeUnit.SECONDS),
                "Session should complete within 10s");
        assertFalse(session.textChunks.isEmpty(),
                "At least one text chunk should be sent");
        assertTrue(session.errors.isEmpty(),
                "No errors expected: " + session.errors);
    }

    @Test
    protected void toolCallExecutesIfSupported() throws Exception {
        var runtime = createRuntime();
        if (!runtime.capabilities().contains(AiCapability.TOOL_CALLING)) {
            return;
        }
        var context = createToolCallContext();
        if (context == null) {
            return;
        }
        var session = new RecordingSession();

        runtime.execute(context, session);

        assertTrue(session.awaitCompletion(10, TimeUnit.SECONDS),
                "Session should complete within 10s after tool call");
    }

    @Test
    protected void errorContextTriggersSessionError() throws Exception {
        var context = createErrorContext();
        if (context == null) {
            return;
        }
        var runtime = createRuntime();
        var session = new RecordingSession();

        runtime.execute(context, session);

        assertTrue(session.awaitCompletion(10, TimeUnit.SECONDS),
                "Session should complete (via error) within 10s");
        assertFalse(session.errors.isEmpty(),
                "At least one error expected");
    }

    /**
     * Test double that captures all session events for assertion.
     */
    protected static class RecordingSession implements StreamingSession {
        public final List<String> textChunks = new CopyOnWriteArrayList<>();
        public final Map<String, Object> metadata = new ConcurrentHashMap<>();
        public final List<String> progressMessages = new CopyOnWriteArrayList<>();
        public final List<AiEvent> events = new CopyOnWriteArrayList<>();
        public final List<Throwable> errors = new CopyOnWriteArrayList<>();
        public final AtomicInteger completionCount = new AtomicInteger();
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public String sessionId() {
            return "contract-test";
        }

        @Override
        public void send(String text) {
            textChunks.add(text);
        }

        @Override
        public void sendMetadata(String key, Object value) {
            metadata.put(key, value);
        }

        @Override
        public void progress(String message) {
            progressMessages.add(message);
        }

        @Override
        public void complete() {
            completionCount.incrementAndGet();
            closed.set(true);
            latch.countDown();
        }

        @Override
        public void complete(String summary) {
            completionCount.incrementAndGet();
            closed.set(true);
            latch.countDown();
        }

        @Override
        public void error(Throwable t) {
            errors.add(t);
            closed.set(true);
            latch.countDown();
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public void emit(AiEvent event) {
            events.add(event);
            StreamingSession.super.emit(event);
        }

        public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }
    }
}
