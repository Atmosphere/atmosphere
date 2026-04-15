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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AiPipeline#execute} — the core pipeline flow including
 * guardrails, memory integration, metrics decoration, and context providers.
 */
class AiPipelineExecuteTest {

    /** Minimal runtime that records the context and echoes a fixed response. */
    static class RecordingRuntime implements AgentRuntime {
        final AtomicReference<AgentExecutionContext> lastContext = new AtomicReference<>();

        @Override
        public String name() { return "recording"; }

        @Override
        public boolean isAvailable() { return true; }

        @Override
        public int priority() { return 0; }

        @Override
        public void configure(AiConfig.LlmSettings settings) { }

        @Override
        public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING);
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            lastContext.set(context);
            session.send("response");
            session.complete();
        }
    }

    /** Simple session that captures sends and completion. */
    static class CapturingSession implements StreamingSession {
        final List<String> sent = new ArrayList<>();
        boolean completed;
        Throwable lastError;
        final List<String> metadataKeys = new ArrayList<>();

        @Override
        public String sessionId() { return "test-session"; }

        @Override
        public void send(String text) { sent.add(text); }

        @Override
        public void sendMetadata(String key, Object value) {
            metadataKeys.add(key);
        }

        @Override
        public void progress(String message) { }

        @Override
        public void complete() { completed = true; }

        @Override
        public void complete(String summary) { completed = true; }

        @Override
        public void error(Throwable t) { lastError = t; }

        @Override
        public boolean isClosed() { return completed || lastError != null; }
    }

    @Test
    void executePassesThroughToRuntime() {
        var runtime = new RecordingRuntime();
        var pipeline = new AiPipeline(runtime, "system prompt", "gpt-4",
                null, null, List.of(), List.of(), null);
        var session = new CapturingSession();

        pipeline.execute("client-1", "hello", session);

        assertNotNull(runtime.lastContext.get());
        assertEquals("hello", runtime.lastContext.get().message());
        assertEquals("system prompt", runtime.lastContext.get().systemPrompt());
        assertEquals("gpt-4", runtime.lastContext.get().model());
        assertTrue(session.completed);
        assertEquals(List.of("response"), session.sent);
    }

    @Test
    void executeWithMemoryStoresHistory() {
        var runtime = new RecordingRuntime();
        var memory = new InMemoryConversationMemory();
        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                memory, null, List.of(), List.of(), null);
        var session = new CapturingSession();

        pipeline.execute("client-1", "hello", session);

        var history = memory.getHistory("client-1");
        assertEquals(2, history.size());
        assertEquals("user", history.get(0).role());
        assertEquals("hello", history.get(0).content());
        assertEquals("assistant", history.get(1).role());
        assertEquals("response", history.get(1).content());
    }

    @Test
    void executeWithMemoryFeedsHistoryToSubsequentCalls() {
        var runtime = new RecordingRuntime();
        var memory = new InMemoryConversationMemory();
        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                memory, null, List.of(), List.of(), null);

        pipeline.execute("client-1", "msg1", new CapturingSession());
        pipeline.execute("client-1", "msg2", new CapturingSession());

        var ctx = runtime.lastContext.get();
        assertEquals(2, ctx.history().size());
        assertEquals("msg1", ctx.history().get(0).content());
    }

    @Test
    void executeBlockedByGuardrailSendsErrorAndStops() {
        var runtime = new RecordingRuntime();
        AiGuardrail blocker = new AiGuardrail() {
            @Override
            public AiGuardrail.GuardrailResult inspectRequest(AiRequest request) {
                return AiGuardrail.GuardrailResult.block("forbidden content");
            }
        };
        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                null, null, List.of(blocker), List.of(), null);
        var session = new CapturingSession();

        pipeline.execute("client-1", "bad input", session);

        assertNotNull(session.lastError);
        assertTrue(session.lastError instanceof SecurityException);
        assertTrue(session.lastError.getMessage().contains("forbidden content"));
        // Runtime should never have been called
        assertEquals(null, runtime.lastContext.get());
    }

    @Test
    void executeModifiedByGuardrailUsesModifiedRequest() {
        var runtime = new RecordingRuntime();
        AiGuardrail modifier = new AiGuardrail() {
            @Override
            public AiGuardrail.GuardrailResult inspectRequest(AiRequest request) {
                return AiGuardrail.GuardrailResult.modify(
                        request.withMessage("sanitized: " + request.message()));
            }
        };
        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                null, null, List.of(modifier), List.of(), null);
        var session = new CapturingSession();

        pipeline.execute("client-1", "original", session);

        assertNotNull(runtime.lastContext.get());
        assertEquals("sanitized: original", runtime.lastContext.get().message());
    }

    @Test
    void executePassesGuardrailAllowsNormalFlow() {
        var runtime = new RecordingRuntime();
        AiGuardrail passer = new AiGuardrail() {
            @Override
            public AiGuardrail.GuardrailResult inspectRequest(AiRequest request) {
                return AiGuardrail.GuardrailResult.pass();
            }
        };
        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                null, null, List.of(passer), List.of(), null);
        var session = new CapturingSession();

        pipeline.execute("client-1", "safe input", session);

        assertNotNull(runtime.lastContext.get());
        assertEquals("safe input", runtime.lastContext.get().message());
        assertTrue(session.completed);
    }

    @Test
    void executeGuardrailExceptionSendsErrorAndStops() {
        var runtime = new RecordingRuntime();
        AiGuardrail failing = new AiGuardrail() {
            @Override
            public AiGuardrail.GuardrailResult inspectRequest(AiRequest request) {
                throw new RuntimeException("guardrail crash");
            }
        };
        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                null, null, List.of(failing), List.of(), null);
        var session = new CapturingSession();

        pipeline.execute("client-1", "input", session);

        assertNotNull(session.lastError);
        assertEquals(null, runtime.lastContext.get());
    }

    @Test
    void executeWithMultipleGuardrailsAppliesInOrder() {
        var runtime = new RecordingRuntime();
        AiGuardrail first = new AiGuardrail() {
            @Override
            public AiGuardrail.GuardrailResult inspectRequest(AiRequest request) {
                return AiGuardrail.GuardrailResult.modify(
                        request.withMessage(request.message() + "-first"));
            }
        };
        AiGuardrail second = new AiGuardrail() {
            @Override
            public AiGuardrail.GuardrailResult inspectRequest(AiRequest request) {
                return AiGuardrail.GuardrailResult.modify(
                        request.withMessage(request.message() + "-second"));
            }
        };
        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                null, null, List.of(first, second), List.of(), null);
        var session = new CapturingSession();

        pipeline.execute("client-1", "msg", session);

        assertEquals("msg-first-second", runtime.lastContext.get().message());
    }

    @Test
    void executeWithClientIdSetsConversationId() {
        var runtime = new RecordingRuntime();
        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                null, null, List.of(), List.of(), null);
        var session = new CapturingSession();

        pipeline.execute("telegram:12345", "hello", session);

        assertEquals("telegram:12345", runtime.lastContext.get().conversationId());
    }

    @Test
    void tryResolveApprovalReturnsFalseForPlainMessage() {
        var pipeline = new AiPipeline(new RecordingRuntime(), null, "gpt-4",
                null, null, null, null, null);
        assertEquals(false, pipeline.tryResolveApproval("hello world"));
    }

    @Test
    void executeWithNullMemoryDoesNotThrow() {
        var runtime = new RecordingRuntime();
        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                null, null, List.of(), List.of(), null);
        var session = new CapturingSession();

        pipeline.execute("client-1", "hello", session);

        assertTrue(session.completed);
        assertEquals(0, runtime.lastContext.get().history().size());
    }

    @Test
    void executeWithExtraMetadataThreadsThrough() {
        var runtime = new RecordingRuntime();
        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                null, null, List.of(), List.of(), null);
        var session = new CapturingSession();

        pipeline.execute("client-1", "hello", session,
                Map.of("custom.key", "custom.value"));

        var meta = runtime.lastContext.get().metadata();
        assertEquals("custom.value", meta.get("custom.key"));
    }

    @Test
    void setDefaultCachePolicyMergesIntoMetadata() {
        var runtime = new RecordingRuntime();
        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                null, null, List.of(), List.of(), null);
        pipeline.setDefaultCachePolicy(
                org.atmosphere.ai.llm.CacheHint.CachePolicy.CONSERVATIVE);
        var session = new CapturingSession();

        pipeline.execute("client-1", "hello", session);

        var meta = runtime.lastContext.get().metadata();
        assertTrue(meta.containsKey(org.atmosphere.ai.llm.CacheHint.METADATA_KEY));
    }
}
