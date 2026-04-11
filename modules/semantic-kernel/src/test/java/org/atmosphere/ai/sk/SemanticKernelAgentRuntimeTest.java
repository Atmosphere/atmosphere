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
package org.atmosphere.ai.sk;

import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 12 smoke tests for {@link SemanticKernelAgentRuntime}. Verifies the
 * ServiceLoader wiring, advertised capabilities, and name. End-to-end
 * streaming tests live in a sample/integration test that provides a real
 * {@code ChatCompletionService} via {@code OpenAIChatCompletion.builder()};
 * constructing one in-process requires an Azure {@code OpenAIAsyncClient}
 * that this module's unit tests do not carry as a dependency.
 */
class SemanticKernelAgentRuntimeTest {

    @Test
    void runtimeIsDiscoverableViaServiceLoader() {
        var loader = ServiceLoader.load(AgentRuntime.class);
        var found = StreamSupport.stream(loader.spliterator(), false)
                .anyMatch(rt -> rt instanceof SemanticKernelAgentRuntime);
        assertTrue(found,
                "SemanticKernelAgentRuntime must be registered in META-INF/services/org.atmosphere.ai.AgentRuntime");
    }

    @Test
    void runtimeNameIsStable() {
        assertEquals("semantic-kernel", new SemanticKernelAgentRuntime().name());
    }

    @Test
    void capabilitiesDeclareTextStreamingSystemPromptMemoryAndTokenUsage() {
        var runtime = new SemanticKernelAgentRuntime();
        var caps = runtime.capabilities();

        assertNotNull(caps);
        assertTrue(caps.contains(AiCapability.TEXT_STREAMING));
        assertTrue(caps.contains(AiCapability.SYSTEM_PROMPT));
        assertTrue(caps.contains(AiCapability.CONVERSATION_MEMORY));
        assertTrue(caps.contains(AiCapability.TOKEN_USAGE));

        // Phase 12 initial release does NOT declare TOOL_CALLING — SK-Java's
        // annotation-driven @DefineKernelFunction plugin system needs either
        // compile-time generation or bytecode synthesis to map Atmosphere's
        // dynamic ToolDefinition shape. Documented in the runtime's Javadoc.
        assertFalse(caps.contains(AiCapability.TOOL_CALLING),
                "TOOL_CALLING intentionally deferred — see class Javadoc for the follow-up plan");
    }

    @Test
    void isAvailableReturnsTrueWhenSemanticKernelApiIsOnClasspath() {
        var runtime = new SemanticKernelAgentRuntime();
        assertTrue(runtime.isAvailable(),
                "Semantic Kernel API is a provided dependency; isAvailable should be true in the test classpath");
    }
}
