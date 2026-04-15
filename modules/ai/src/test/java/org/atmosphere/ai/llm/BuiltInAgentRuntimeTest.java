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

import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.RetryPolicy;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link BuiltInAgentRuntime} — verifies metadata, capabilities,
 * configuration, and the private {@code buildRequest} helper via reflection.
 */
class BuiltInAgentRuntimeTest {

    @Test
    void nameIsBuiltIn() {
        assertEquals("built-in", new BuiltInAgentRuntime().name());
    }

    @Test
    void priorityIsZero() {
        assertEquals(0, new BuiltInAgentRuntime().priority());
    }

    @Test
    void ownsPerRequestRetryReturnsTrue() throws Exception {
        var runtime = new BuiltInAgentRuntime();
        Method m = runtime.getClass().getDeclaredMethod("ownsPerRequestRetry");
        m.setAccessible(true);
        assertEquals(true, m.invoke(runtime));
    }

    @Test
    void capabilitiesIncludesExpectedSet() {
        var caps = new BuiltInAgentRuntime().capabilities();
        assertTrue(caps.contains(AiCapability.TEXT_STREAMING));
        assertTrue(caps.contains(AiCapability.TOOL_CALLING));
        assertTrue(caps.contains(AiCapability.STRUCTURED_OUTPUT));
        assertTrue(caps.contains(AiCapability.SYSTEM_PROMPT));
        assertTrue(caps.contains(AiCapability.TOOL_APPROVAL));
        assertTrue(caps.contains(AiCapability.VISION));
        assertTrue(caps.contains(AiCapability.AUDIO));
        assertTrue(caps.contains(AiCapability.MULTI_MODAL));
        assertTrue(caps.contains(AiCapability.PROMPT_CACHING));
        assertTrue(caps.contains(AiCapability.PER_REQUEST_RETRY));
        assertTrue(caps.contains(AiCapability.TOKEN_USAGE));
        assertTrue(caps.contains(AiCapability.CONVERSATION_MEMORY));
        assertTrue(caps.contains(AiCapability.TOOL_CALL_DELTA));
    }

    @Test
    void capabilitiesCountMatchesDeclared() {
        var caps = new BuiltInAgentRuntime().capabilities();
        assertEquals(13, caps.size());
    }

    @Test
    void nativeClientClassNameIsLlmClient() throws Exception {
        var runtime = new BuiltInAgentRuntime();
        Method m = runtime.getClass().getSuperclass()
                .getDeclaredMethod("nativeClientClassName");
        m.setAccessible(true);
        assertEquals("org.atmosphere.ai.llm.LlmClient", m.invoke(runtime));
    }

    @Test
    void configureWithNullSettingsDoesNotThrow() {
        var runtime = new BuiltInAgentRuntime();
        runtime.configure(null);
        // No exception means success
    }

    @Test
    void configureWithSettingsSetsClient() {
        var runtime = new BuiltInAgentRuntime();
        var client = new FakeLlmClient("gpt-4");
        var settings = new AiConfig.LlmSettings(client, "gpt-4", "remote", null);
        runtime.configure(settings);
        // Use reflection to verify client was set
        assertNotNull(getNativeClient(runtime));
    }

    @Test
    void configureDoesNotOverrideExistingClient() {
        var runtime = new BuiltInAgentRuntime();
        var client1 = new FakeLlmClient("gpt-4");
        var client2 = new FakeLlmClient("gpt-4");
        var settings1 = new AiConfig.LlmSettings(client1, "gpt-4", "remote", null);
        var settings2 = new AiConfig.LlmSettings(client2, "gpt-4", "remote", null);
        runtime.configure(settings1);
        runtime.configure(settings2);
        assertEquals(client1, getNativeClient(runtime));
    }

    private static Object getNativeClient(BuiltInAgentRuntime runtime) {
        try {
            Method m = runtime.getClass().getSuperclass()
                    .getDeclaredMethod("getNativeClient");
            m.setAccessible(true);
            return m.invoke(runtime);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void modelsReturnsEmptyWhenNoConfig() {
        var runtime = new BuiltInAgentRuntime();
        assertEquals(List.of(), runtime.models());
    }

    @Test
    void buildRequestSetsModel() throws Exception {
        var runtime = new BuiltInAgentRuntime();
        Method m = BuiltInAgentRuntime.class.getDeclaredMethod(
                "buildRequest", AgentExecutionContext.class);
        m.setAccessible(true);

        var context = new AgentExecutionContext(
                "hello", "system prompt", "gpt-4",
                null, null, null, null,
                List.of(), null, null, List.of(), Map.of(), List.of(),
                null, null);
        var request = (ChatCompletionRequest) m.invoke(runtime, context);
        assertEquals("gpt-4", request.model());
    }

    @Test
    void buildRequestEnablesJsonModeWhenResponseTypeSet() throws Exception {
        var runtime = new BuiltInAgentRuntime();
        Method m = BuiltInAgentRuntime.class.getDeclaredMethod(
                "buildRequest", AgentExecutionContext.class);
        m.setAccessible(true);

        var context = new AgentExecutionContext(
                "hello", "system", "gpt-4",
                null, null, null, null,
                List.of(), null, null, List.of(), Map.of(), List.of(),
                String.class, null);
        var request = (ChatCompletionRequest) m.invoke(runtime, context);
        assertTrue(request.jsonMode());
    }

    @Test
    void buildRequestAddsToolsWhenPresent() throws Exception {
        var runtime = new BuiltInAgentRuntime();
        Method m = BuiltInAgentRuntime.class.getDeclaredMethod(
                "buildRequest", AgentExecutionContext.class);
        m.setAccessible(true);

        var tool = ToolDefinition.builder("myTool", "description")
                .executor(args -> "ok")
                .build();
        var context = new AgentExecutionContext(
                "hello", "system", "gpt-4",
                null, null, null, null,
                List.of(tool), null, null, List.of(), Map.of(), List.of(),
                null, null);
        var request = (ChatCompletionRequest) m.invoke(runtime, context);
        assertEquals(1, request.tools().size());
        assertEquals("myTool", request.tools().get(0).name());
    }

    @Test
    void buildRequestSetsConversationId() throws Exception {
        var runtime = new BuiltInAgentRuntime();
        Method m = BuiltInAgentRuntime.class.getDeclaredMethod(
                "buildRequest", AgentExecutionContext.class);
        m.setAccessible(true);

        var context = new AgentExecutionContext(
                "hello", "system", "gpt-4",
                null, null, null, "conv-123",
                List.of(), null, null, List.of(), Map.of(), List.of(),
                null, null);
        var request = (ChatCompletionRequest) m.invoke(runtime, context);
        assertEquals("conv-123", request.conversationId());
    }

    @Test
    void buildRequestSkipsDefaultRetryPolicy() throws Exception {
        var runtime = new BuiltInAgentRuntime();
        Method m = BuiltInAgentRuntime.class.getDeclaredMethod(
                "buildRequest", AgentExecutionContext.class);
        m.setAccessible(true);

        var context = new AgentExecutionContext(
                "hello", "system", "gpt-4",
                null, null, null, null,
                List.of(), null, null, List.of(), Map.of(), List.of(),
                null, null);
        var request = (ChatCompletionRequest) m.invoke(runtime, context);
        assertNull(request.retryPolicy());
    }

    @Test
    void buildRequestThreadsNonDefaultRetryPolicy() throws Exception {
        var runtime = new BuiltInAgentRuntime();
        Method m = BuiltInAgentRuntime.class.getDeclaredMethod(
                "buildRequest", AgentExecutionContext.class);
        m.setAccessible(true);

        var customPolicy = new RetryPolicy(5, Duration.ofSeconds(2),
                Duration.ofSeconds(30), 2.0, Set.of("rate_limit"));
        var context = new AgentExecutionContext(
                "hello", "system", "gpt-4",
                null, null, null, null,
                List.of(), null, null, List.of(), Map.of(), List.of(),
                null, null, List.of(), List.of(), null, customPolicy);
        var request = (ChatCompletionRequest) m.invoke(runtime, context);
        assertNotNull(request.retryPolicy());
        assertEquals(5, request.retryPolicy().maxRetries());
    }

    @Test
    void buildRequestNoJsonModeWhenResponseTypeNull() throws Exception {
        var runtime = new BuiltInAgentRuntime();
        Method m = BuiltInAgentRuntime.class.getDeclaredMethod(
                "buildRequest", AgentExecutionContext.class);
        m.setAccessible(true);

        var context = new AgentExecutionContext(
                "hello", "system", "gpt-4",
                null, null, null, null,
                List.of(), null, null, List.of(), Map.of(), List.of(),
                null, null);
        var request = (ChatCompletionRequest) m.invoke(runtime, context);
        assertFalse(request.jsonMode());
    }

    @Test
    void doExecuteWithHandleReturnsCancellableHandle() throws Exception {
        var runtime = new BuiltInAgentRuntime();
        var client = new FakeLlmClient("gpt-4");
        // Use reflection to set the native client
        Method setter = runtime.getClass().getSuperclass()
                .getDeclaredMethod("setNativeClient", Object.class);
        setter.setAccessible(true);
        setter.invoke(runtime, client);

        var context = new AgentExecutionContext(
                "hello", "system", "gpt-4",
                null, null, null, null,
                List.of(), null, null, List.of(), Map.of(), List.of(),
                null, null);
        var session = new CollectingSession();

        var handle = runtime.executeWithHandle(context, session);
        assertNotNull(handle);
        // The FakeLlmClient may complete quickly; just verify handle is usable
        handle.cancel();
    }
}
