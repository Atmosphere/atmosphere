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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AiPipeline} covering construction, guardrail blocking,
 * approval registry access, response cache, and execute delegation.
 */
class AiPipelineTest {

    @Test
    void executeDelegatesToRuntime() {
        var captured = new AtomicReference<AgentExecutionContext>();
        var runtime = new StubRuntime() {
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                captured.set(context);
                session.send("reply");
                session.complete();
            }
        };
        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                null, null, List.of(), List.of(), null);
        var session = new CollectingSession("test-1");
        pipeline.execute("client-1", "hello", session);

        assertNotNull(captured.get());
        assertEquals("hello", captured.get().message());
        assertEquals("system", captured.get().systemPrompt());
    }

    @Test
    void guardrailBlockStopsExecution() {
        var executed = new java.util.concurrent.atomic.AtomicBoolean(false);
        var runtime = new StubRuntime() {
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                executed.set(true);
                session.complete();
            }
        };
        AiGuardrail blocker = new AiGuardrail() {
            @Override
            public AiGuardrail.GuardrailResult inspectRequest(AiRequest request) {
                return AiGuardrail.GuardrailResult.block("blocked");
            }
        };
        var pipeline = new AiPipeline(runtime, null, null,
                null, null, List.of(blocker), List.of(), null);
        var session = new CollectingSession("test-2");
        pipeline.execute("c1", "bad input", session);

        assertFalse(executed.get(), "Runtime should not execute when guardrail blocks");
    }

    @Test
    void responseCacheDefaultIsNull() {
        var pipeline = new AiPipeline(new StubRuntime(), null, null,
                null, null, null, null, null);
        assertNull(pipeline.responseCache());
    }

    @Test
    void setResponseCacheMakesItAccessible() {
        var pipeline = new AiPipeline(new StubRuntime(), null, null,
                null, null, null, null, null);
        var cache = new org.atmosphere.ai.cache.InMemoryResponseCache();
        pipeline.setResponseCache(cache, java.time.Duration.ofMinutes(10));
        assertEquals(cache, pipeline.responseCache());
    }

    @Test
    void approvalRegistryIsNotNull() {
        var pipeline = new AiPipeline(new StubRuntime(), null, null,
                null, null, null, null, null);
        assertNotNull(pipeline.approvalRegistry());
    }

    @Test
    void tryResolveApprovalReturnsFalseForNonApproval() {
        var pipeline = new AiPipeline(new StubRuntime(), null, null,
                null, null, null, null, null);
        assertFalse(pipeline.tryResolveApproval("just a regular message"));
    }

    @Test
    void executePassesMetadataToRuntime() {
        var captured = new AtomicReference<AgentExecutionContext>();
        var runtime = new StubRuntime() {
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                captured.set(context);
                session.complete();
            }
        };
        var pipeline = new AiPipeline(runtime, "", null,
                null, null, List.of(), List.of(), null);
        var session = new CollectingSession("test-meta");
        pipeline.execute("c1", "msg", session, Map.of("custom.key", "custom.value"));

        assertNotNull(captured.get());
        assertEquals("custom.value", captured.get().metadata().get("custom.key"));
    }

    @Test
    void nullGuardrailListIsTreatedAsEmpty() {
        var executed = new java.util.concurrent.atomic.AtomicBoolean(false);
        var runtime = new StubRuntime() {
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                executed.set(true);
                session.complete();
            }
        };
        var pipeline = new AiPipeline(runtime, null, null,
                null, null, null, null, null);
        var session = new CollectingSession("test-null");
        pipeline.execute("c1", "hello", session);

        assertTrue(executed.get());
    }

    /**
     * Minimal stub that satisfies the {@link AgentRuntime} interface.
     */
    private static class StubRuntime implements AgentRuntime {
        @Override public String name() { return "stub"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING);
        }
        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            session.complete();
        }
    }
}
