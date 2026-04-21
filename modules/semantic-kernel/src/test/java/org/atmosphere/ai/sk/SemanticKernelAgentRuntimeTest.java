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

import com.microsoft.semantickernel.orchestration.ToolCallBehavior;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    void capabilitiesDeclareTextStreamingSystemPromptMemoryAndToolCalling() {
        var runtime = new SemanticKernelAgentRuntime();
        var caps = runtime.capabilities();

        assertNotNull(caps);
        assertTrue(caps.contains(AiCapability.TEXT_STREAMING));
        assertTrue(caps.contains(AiCapability.SYSTEM_PROMPT));
        assertTrue(caps.contains(AiCapability.CONVERSATION_MEMORY));
        assertTrue(caps.contains(AiCapability.TOKEN_USAGE));

        // TOOL_CALLING / TOOL_APPROVAL are now honored via
        // SemanticKernelToolBridge — a direct KernelFunction<String> subclass
        // (one per Atmosphere ToolDefinition) whose overridden invokeAsync
        // routes through ToolExecutionHelper.executeWithApproval. SK 1.4.0
        // exposes KernelFunction's protected constructor and abstract
        // invokeAsync precisely for this kind of programmatic bridging, so
        // the earlier "needs annotation processor / bytecode synthesis"
        // posture was wrong. See SemanticKernelToolBridge.AtmosphereSkFunction.
        assertTrue(caps.contains(AiCapability.TOOL_CALLING),
                "TOOL_CALLING is now implemented via SemanticKernelToolBridge");
        assertTrue(caps.contains(AiCapability.TOOL_APPROVAL),
                "TOOL_APPROVAL fires because AtmosphereSkFunction routes through "
                        + "ToolExecutionHelper.executeWithApproval on every invocation");
    }

    @Test
    void isAvailableReturnsTrueWhenSemanticKernelApiIsOnClasspath() {
        var runtime = new SemanticKernelAgentRuntime();
        assertTrue(runtime.isAvailable(),
                "Semantic Kernel API is a provided dependency; isAvailable should be true in the test classpath");
    }

    @Test
    void invocationContextAlwaysCarriesToolCallBehaviorEvenWithNoTools() {
        // Regression: SK 1.4.0 OpenAIChatCompletion:200 dereferences
        // getToolCallBehavior() without a null-check, so any InvocationContext
        // built without .withToolCallBehavior(...) NPEs the moment streaming
        // starts — breaking every sample that wires SK with zero @AiTool
        // methods (e.g. spring-boot-semantic-kernel-chat).
        var noTools = SemanticKernelAgentRuntime.buildInvocationContext(false);
        assertNotNull(noTools.getToolCallBehavior(),
                "ToolCallBehavior must be set even when no tools are configured");
        assertInstanceOf(ToolCallBehavior.AllowedKernelFunctions.class, noTools.getToolCallBehavior());

        var withTools = SemanticKernelAgentRuntime.buildInvocationContext(true);
        assertNotNull(withTools.getToolCallBehavior());
        assertInstanceOf(ToolCallBehavior.AllowedKernelFunctions.class, withTools.getToolCallBehavior());
    }
}
