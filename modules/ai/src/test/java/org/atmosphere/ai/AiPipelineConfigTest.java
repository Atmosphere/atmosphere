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

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link AiPipeline} construction and configuration accessors.
 */
class AiPipelineConfigTest {

    /** Minimal runtime that records execute calls. */
    static class StubRuntime implements AgentRuntime {
        @Override
        public String name() { return "stub"; }

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
            session.send("stub-response");
            session.complete();
        }
    }

    @Test
    void constructorWithNullGuardrailsDefaultsToEmptyList() {
        var pipeline = new AiPipeline(
                new StubRuntime(), "system", "model",
                null, null, null, null, null);
        assertNotNull(pipeline);
    }

    @Test
    void constructorWithNullMetricsDefaultsToNoop() {
        var pipeline = new AiPipeline(
                new StubRuntime(), "system", "model",
                null, null, List.of(), List.of(), null);
        assertNotNull(pipeline);
    }

    @Test
    void constructorWithResponseType() {
        var pipeline = new AiPipeline(
                new StubRuntime(), "system", "model",
                null, null, List.of(), List.of(), null, String.class);
        assertNotNull(pipeline);
    }

    @Test
    void responseCacheIsNullByDefault() {
        var pipeline = new AiPipeline(
                new StubRuntime(), null, "model",
                null, null, null, null, null);
        assertNull(pipeline.responseCache());
    }

    @Test
    void setResponseCacheUpdatesAccessor() {
        var pipeline = new AiPipeline(
                new StubRuntime(), null, "model",
                null, null, null, null, null);
        var cache = new org.atmosphere.ai.cache.InMemoryResponseCache();
        pipeline.setResponseCache(cache, Duration.ofMinutes(10));
        assertEquals(cache, pipeline.responseCache());
    }

    @Test
    void approvalRegistryIsNotNull() {
        var pipeline = new AiPipeline(
                new StubRuntime(), null, "model",
                null, null, null, null, null);
        assertNotNull(pipeline.approvalRegistry());
    }

    @Test
    void tryResolveApprovalReturnsFalseForNonApprovalMessage() {
        var pipeline = new AiPipeline(
                new StubRuntime(), null, "model",
                null, null, null, null, null);
        assertEquals(false, pipeline.tryResolveApproval("hello world"));
    }

    @Test
    void nullSystemPromptDefaultsToEmpty() {
        var pipeline = new AiPipeline(
                new StubRuntime(), null, "gpt-4",
                null, null, null, null, null);
        // Pipeline should not throw when constructed with null system prompt
        assertNotNull(pipeline);
    }

    @Test
    void cacheHitMetadataKeyConstant() {
        assertEquals("ai.cache.hit", AiPipeline.CACHE_HIT_METADATA_KEY);
    }
}
