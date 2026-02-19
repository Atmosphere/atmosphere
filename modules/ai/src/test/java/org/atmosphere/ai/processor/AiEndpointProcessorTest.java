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

import org.atmosphere.ai.PromptLoader;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

@SuppressWarnings({"unchecked", "rawtypes"})
public class AiEndpointProcessorTest {

    private AiEndpointProcessor processor;
    private AtmosphereFramework framework;

    @BeforeMethod
    public void setUp() throws Exception {
        processor = new AiEndpointProcessor();
        framework = mock(AtmosphereFramework.class);
    }

    @AfterMethod
    public void tearDown() {
        PromptLoader.clearCache();
    }

    @Test
    public void testRegistersHandlerAtConfiguredPath() throws Exception {
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenReturn(new ValidEndpoint());

        processor.handle(framework, (Class) ValidEndpoint.class);

        var pathCaptor = ArgumentCaptor.forClass(String.class);
        var handlerCaptor = ArgumentCaptor.forClass(AtmosphereHandler.class);
        var interceptorsCaptor = ArgumentCaptor.forClass(List.class);
        verify(framework).addAtmosphereHandler(pathCaptor.capture(), handlerCaptor.capture(), interceptorsCaptor.capture());

        assertEquals(pathCaptor.getValue(), "/atmosphere/test-ai");
        assertNotNull(handlerCaptor.getValue());
        assertTrue(handlerCaptor.getValue() instanceof AiEndpointHandler);
    }

    @Test
    public void testConfiguresPerResourceTimeout() throws Exception {
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenReturn(new CustomTimeoutEndpoint());

        processor.handle(framework, (Class) CustomTimeoutEndpoint.class);

        // Timeout should NOT be set globally
        verify(framework, never()).addInitParameter(
                eq(org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE), anyString());

        var handlerCaptor = ArgumentCaptor.forClass(AtmosphereHandler.class);
        verify(framework).addAtmosphereHandler(anyString(), handlerCaptor.capture(), any(List.class));
        var handler = (AiEndpointHandler) handlerCaptor.getValue();
        assertEquals(handler.suspendTimeout(), 60_000L);
    }

    @Test
    public void testDefaultTimeout() throws Exception {
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenReturn(new ValidEndpoint());

        processor.handle(framework, (Class) ValidEndpoint.class);

        // Timeout should NOT be set globally
        verify(framework, never()).addInitParameter(
                eq(org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE), anyString());

        var handlerCaptor = ArgumentCaptor.forClass(AtmosphereHandler.class);
        verify(framework).addAtmosphereHandler(anyString(), handlerCaptor.capture(), any(List.class));
        var handler = (AiEndpointHandler) handlerCaptor.getValue();
        assertEquals(handler.suspendTimeout(), 120_000L);
    }

    @Test
    public void testRejectsClassWithoutPromptMethod() throws Exception {
        processor.handle(framework, (Class) NoPromptEndpoint.class);

        verify(framework, never()).addAtmosphereHandler(anyString(), any(AtmosphereHandler.class), any(List.class));
    }

    @Test
    public void testRejectsInvalidPromptSignature() throws Exception {
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenReturn(new InvalidSignatureEndpoint());

        processor.handle(framework, (Class) InvalidSignatureEndpoint.class);

        // Invalid signature should prevent registration
        verify(framework, never()).addAtmosphereHandler(anyString(), any(AtmosphereHandler.class), any(List.class));
    }

    @Test
    public void testAcceptsThreeParamPrompt() throws Exception {
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenReturn(new ThreeParamEndpoint());

        processor.handle(framework, (Class) ThreeParamEndpoint.class);

        verify(framework).addAtmosphereHandler(eq("/atmosphere/three-param"), any(AtmosphereHandler.class), any(List.class));
    }

    @Test
    public void testHandlerTargetIsCorrectInstance() throws Exception {
        var instance = new ValidEndpoint();
        when(framework.newClassInstance(eq(Object.class), any())).thenReturn(instance);

        processor.handle(framework, (Class) ValidEndpoint.class);

        var handlerCaptor = ArgumentCaptor.forClass(AtmosphereHandler.class);
        verify(framework).addAtmosphereHandler(anyString(), handlerCaptor.capture(), any(List.class));

        var handler = (AiEndpointHandler) handlerCaptor.getValue();
        assertSame(handler.target(), instance);
        assertEquals(handler.promptMethod().getName(), "onPrompt");
    }

    @Test
    public void testSkipsClassWithoutAnnotation() throws Exception {
        processor.handle(framework, (Class) NotAnnotated.class);

        verify(framework, never()).addAtmosphereHandler(anyString(), any(AtmosphereHandler.class), any(List.class));
    }

    @Test
    public void testSystemPromptPassedToHandler() throws Exception {
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenReturn(new SystemPromptEndpoint());

        processor.handle(framework, (Class) SystemPromptEndpoint.class);

        var handlerCaptor = ArgumentCaptor.forClass(AtmosphereHandler.class);
        verify(framework).addAtmosphereHandler(anyString(), handlerCaptor.capture(), any(List.class));
        var handler = (AiEndpointHandler) handlerCaptor.getValue();
        assertEquals(handler.systemPrompt(), "You are a helpful assistant.");
    }

    @Test
    public void testDefaultSystemPromptIsEmpty() throws Exception {
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenReturn(new ValidEndpoint());

        processor.handle(framework, (Class) ValidEndpoint.class);

        var handlerCaptor = ArgumentCaptor.forClass(AtmosphereHandler.class);
        verify(framework).addAtmosphereHandler(anyString(), handlerCaptor.capture(), any(List.class));
        var handler = (AiEndpointHandler) handlerCaptor.getValue();
        assertEquals(handler.systemPrompt(), "");
    }

    @Test
    public void testSystemPromptFromResource() throws Exception {
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenReturn(new ResourcePromptEndpoint());

        processor.handle(framework, (Class) ResourcePromptEndpoint.class);

        var handlerCaptor = ArgumentCaptor.forClass(AtmosphereHandler.class);
        verify(framework).addAtmosphereHandler(anyString(), handlerCaptor.capture(), any(List.class));
        var handler = (AiEndpointHandler) handlerCaptor.getValue();
        assertEquals(handler.systemPrompt(), "You are a helpful assistant.");
    }

    @Test
    public void testResourceTakesPrecedenceOverInline() throws Exception {
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenReturn(new BothPromptsEndpoint());

        processor.handle(framework, (Class) BothPromptsEndpoint.class);

        var handlerCaptor = ArgumentCaptor.forClass(AtmosphereHandler.class);
        verify(framework).addAtmosphereHandler(anyString(), handlerCaptor.capture(), any(List.class));
        var handler = (AiEndpointHandler) handlerCaptor.getValue();
        // Resource content should win over the inline systemPrompt
        assertEquals(handler.systemPrompt(), "You are a helpful assistant.");
    }

    // ---- Test fixture classes ----

    @AiEndpoint(path = "/atmosphere/test-ai")
    public static class ValidEndpoint {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
        }
    }

    @AiEndpoint(path = "/atmosphere/custom-timeout", timeout = 60_000L)
    public static class CustomTimeoutEndpoint {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
        }
    }

    @AiEndpoint(path = "/atmosphere/no-prompt")
    public static class NoPromptEndpoint {
        public void notAPrompt(String message) {
        }
    }

    @AiEndpoint(path = "/atmosphere/invalid-sig")
    public static class InvalidSignatureEndpoint {
        @Prompt
        public void onPrompt(int notAString) {
        }
    }

    @AiEndpoint(path = "/atmosphere/three-param")
    public static class ThreeParamEndpoint {
        @Prompt
        public void onPrompt(String message, StreamingSession session, AtmosphereResource resource) {
        }
    }

    @AiEndpoint(path = "/atmosphere/system-prompt", systemPrompt = "You are a helpful assistant.")
    public static class SystemPromptEndpoint {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
        }
    }

    public static class NotAnnotated {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
        }
    }

    @AiEndpoint(path = "/atmosphere/resource-prompt",
                systemPromptResource = "prompts/test-system-prompt.md")
    public static class ResourcePromptEndpoint {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
        }
    }

    @AiEndpoint(path = "/atmosphere/both-prompts",
                systemPrompt = "This should be ignored.",
                systemPromptResource = "prompts/test-system-prompt.md")
    public static class BothPromptsEndpoint {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
        }
    }
}
