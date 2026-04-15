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
package org.atmosphere.ai.processor;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AiEndpointProcessor} focusing on annotation attribute
 * propagation, tool registration, guardrail instantiation, capability
 * validation, cache policy, and retry policy wiring.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class AiAnnotationProcessorTest {

    private AiEndpointProcessor processor;
    private AtmosphereFramework framework;

    @BeforeEach
    void setUp() {
        processor = new AiEndpointProcessor();
        framework = mock(AtmosphereFramework.class);
    }

    @Test
    void toolProviderRegisteredFromAnnotation() throws Exception {
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenReturn(new ToolEndpoint());
        when(framework.newClassInstance(eq(Object.class), eq(SampleTools.class)))
                .thenReturn(new SampleTools());

        processor.handle(framework, (Class) ToolEndpoint.class);

        var handlerCaptor = ArgumentCaptor.forClass(AtmosphereHandler.class);
        verify(framework).addAtmosphereHandler(eq("/atmosphere/tools"),
                handlerCaptor.capture(), any(List.class));
        var handler = (AiEndpointHandler) handlerCaptor.getValue();
        var tool = handler.toolRegistry().getTool("sample_tool");
        assertTrue(tool.isPresent());
        assertEquals("A sample tool", tool.get().description());
    }

    @Test
    void conversationMemoryConfigured() throws Exception {
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenReturn(new MemoryEndpoint());

        processor.handle(framework, (Class) MemoryEndpoint.class);

        var handlerCaptor = ArgumentCaptor.forClass(AtmosphereHandler.class);
        verify(framework).addAtmosphereHandler(eq("/atmosphere/memory"),
                handlerCaptor.capture(), any(List.class));
        var handler = (AiEndpointHandler) handlerCaptor.getValue();
        assertNotNull(handler.memory());
        assertEquals(50, handler.memory().maxMessages());
    }

    @Test
    void defaultMemoryIsNull() throws Exception {
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenReturn(new MinimalEndpoint());

        processor.handle(framework, (Class) MinimalEndpoint.class);

        var handlerCaptor = ArgumentCaptor.forClass(AtmosphereHandler.class);
        verify(framework).addAtmosphereHandler(eq("/atmosphere/minimal"),
                handlerCaptor.capture(), any(List.class));
        var handler = (AiEndpointHandler) handlerCaptor.getValue();
        assertNull(handler.memory());
    }

    @Test
    void endpointWithRetryPolicyRegisters() throws Exception {
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenReturn(new RetryEndpoint());

        processor.handle(framework, (Class) RetryEndpoint.class);

        var handlerCaptor = ArgumentCaptor.forClass(AtmosphereHandler.class);
        verify(framework).addAtmosphereHandler(eq("/atmosphere/retry"),
                handlerCaptor.capture(), any(List.class));
        assertNotNull(handlerCaptor.getValue());
    }

    @Test
    void invalidPromptFirstParamNotStringRejects() throws Exception {
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenReturn(new BadFirstParamEndpoint());

        processor.handle(framework, (Class) BadFirstParamEndpoint.class);

        verify(framework, never()).addAtmosphereHandler(
                any(), any(AtmosphereHandler.class), any(List.class));
    }

    @Test
    void invalidPromptSecondParamNotSessionRejects() throws Exception {
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenReturn(new BadSecondParamEndpoint());

        processor.handle(framework, (Class) BadSecondParamEndpoint.class);

        verify(framework, never()).addAtmosphereHandler(
                any(), any(AtmosphereHandler.class), any(List.class));
    }

    @Test
    void tooManyParamsPromptRejects() throws Exception {
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenReturn(new FourParamEndpoint());

        processor.handle(framework, (Class) FourParamEndpoint.class);

        verify(framework, never()).addAtmosphereHandler(
                any(), any(AtmosphereHandler.class), any(List.class));
    }

    @Test
    void tooFewParamsPromptRejects() throws Exception {
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenReturn(new OneParamEndpoint());

        processor.handle(framework, (Class) OneParamEndpoint.class);

        verify(framework, never()).addAtmosphereHandler(
                any(), any(AtmosphereHandler.class), any(List.class));
    }

    // ---- Test fixture classes ----

    @AiEndpoint(path = "/atmosphere/minimal")
    public static class MinimalEndpoint {
        @Prompt
        public void onPrompt(String msg, StreamingSession session) {
        }
    }

    @AiEndpoint(path = "/atmosphere/tools", tools = {SampleTools.class})
    public static class ToolEndpoint {
        @Prompt
        public void onPrompt(String msg, StreamingSession session) {
        }
    }

    public static class SampleTools {
        @AiTool(name = "sample_tool", description = "A sample tool")
        public String doSomething(@Param("input") String input) {
            return "result: " + input;
        }
    }

    @AiEndpoint(path = "/atmosphere/memory", conversationMemory = true,
                maxHistoryMessages = 50)
    public static class MemoryEndpoint {
        @Prompt
        public void onPrompt(String msg, StreamingSession session) {
        }
    }

    @AiEndpoint(path = "/atmosphere/retry",
                retry = @AiEndpoint.Retry(maxRetries = 3,
                        initialDelayMs = 500, maxDelayMs = 10_000,
                        backoffMultiplier = 1.5))
    public static class RetryEndpoint {
        @Prompt
        public void onPrompt(String msg, StreamingSession session) {
        }
    }

    @AiEndpoint(path = "/atmosphere/bad-first")
    public static class BadFirstParamEndpoint {
        @Prompt
        public void onPrompt(int notString, StreamingSession session) {
        }
    }

    @AiEndpoint(path = "/atmosphere/bad-second")
    public static class BadSecondParamEndpoint {
        @Prompt
        public void onPrompt(String msg, String notSession) {
        }
    }

    @AiEndpoint(path = "/atmosphere/four-param")
    public static class FourParamEndpoint {
        @Prompt
        public void onPrompt(String msg, StreamingSession s, AtmosphereResource r, int extra) {
        }
    }

    @AiEndpoint(path = "/atmosphere/one-param")
    public static class OneParamEndpoint {
        @Prompt
        public void onPrompt(String msg) {
        }
    }
}
