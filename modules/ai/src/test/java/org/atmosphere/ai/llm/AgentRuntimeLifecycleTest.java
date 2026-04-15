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
package org.atmosphere.ai.llm;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for {@link AgentRuntime} interface default methods: capabilities,
 * models, executeWithHandle, generate, and generateResult.
 */
class AgentRuntimeLifecycleTest {

    /** Minimal runtime that echoes the message. */
    static class EchoRuntime implements AgentRuntime {
        @Override
        public String name() { return "echo"; }

        @Override
        public boolean isAvailable() { return true; }

        @Override
        public int priority() { return 1; }

        @Override
        public void configure(AiConfig.LlmSettings settings) { }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            session.send("echo:" + context.message());
            session.complete();
        }
    }

    /** Runtime that also emits token usage. */
    static class UsageRuntime implements AgentRuntime {
        @Override
        public String name() { return "usage-runtime"; }

        @Override
        public boolean isAvailable() { return true; }

        @Override
        public int priority() { return 0; }

        @Override
        public void configure(AiConfig.LlmSettings settings) { }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            session.send("response");
            session.usage(TokenUsage.of(10, 20, 30));
            session.complete();
        }
    }

    private static AgentExecutionContext context(String message) {
        return new AgentExecutionContext(
                message, "system", "model",
                null, null, null, null,
                List.of(), null, null, List.of(),
                Map.of(), List.of(), null, null);
    }

    @Test
    void defaultCapabilitiesIncludesTextStreaming() {
        var runtime = new EchoRuntime();
        assertEquals(Set.of(AiCapability.TEXT_STREAMING), runtime.capabilities());
    }

    @Test
    void defaultModelsReturnsEmptyList() {
        var runtime = new EchoRuntime();
        assertEquals(List.of(), runtime.models());
    }

    @Test
    void executeWithHandleReturnsCompletedHandle() {
        var runtime = new EchoRuntime();
        var session = new org.atmosphere.ai.CollectingSession();

        ExecutionHandle handle = runtime.executeWithHandle(context("hello"), session);

        assertNotNull(handle);
        assertEquals("echo:hello", session.text());
    }

    @Test
    void generateCollectsResponse() {
        var runtime = new EchoRuntime();
        String result = runtime.generate(context("world"));
        assertEquals("echo:world", result);
    }

    @Test
    void generateWithTimeoutCollectsResponse() {
        var runtime = new EchoRuntime();
        String result = runtime.generate(context("timeout-test"), Duration.ofSeconds(5));
        assertEquals("echo:timeout-test", result);
    }

    @Test
    void generateResultIncludesText() {
        var runtime = new EchoRuntime();
        var result = runtime.generateResult(context("result-test"));
        assertNotNull(result);
        assertEquals("echo:result-test", result.text());
    }

    @Test
    void generateResultCapturesTokenUsage() {
        var runtime = new UsageRuntime();
        var result = runtime.generateResult(context("usage-test"));
        assertNotNull(result);
        assertEquals("response", result.text());
        assertEquals(true, result.usage().isPresent());
        assertEquals(10, result.usage().get().input());
        assertEquals(20, result.usage().get().output());
        assertEquals(30, result.usage().get().total());
    }

    @Test
    void generateResultIncludesDuration() {
        var runtime = new EchoRuntime();
        var result = runtime.generateResult(context("duration-test"));
        assertNotNull(result.duration());
    }
}
