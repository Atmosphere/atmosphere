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
package org.atmosphere.ai.annotation;

import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.llm.CacheHint;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AiEndpointAnnotationTest {

    @AiEndpoint(path = "/test-chat")
    static class MinimalEndpoint { }

    @AiEndpoint(
            path = "/custom",
            timeout = 60_000L,
            systemPrompt = "Be concise.",
            systemPromptResource = "prompts/system.md",
            conversationMemory = true,
            maxHistoryMessages = 50,
            fallbackStrategy = "ROUND_ROBIN",
            autoDiscoverContextProviders = true,
            model = "gpt-4o"
    )
    static class CustomEndpoint { }

    @Test
    void retainedAtRuntime() {
        Retention retention = AiEndpoint.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void targetsTypes() {
        Target target = AiEndpoint.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertArrayEquals(new ElementType[]{ElementType.TYPE}, target.value());
    }

    @Test
    void isDocumented() {
        assertNotNull(AiEndpoint.class.getAnnotation(Documented.class));
    }

    @Test
    void pathIsRequired() {
        AiEndpoint ann = MinimalEndpoint.class.getAnnotation(AiEndpoint.class);
        assertNotNull(ann);
        assertEquals("/test-chat", ann.path());
    }

    @Test
    void defaultTimeout() {
        AiEndpoint ann = MinimalEndpoint.class.getAnnotation(AiEndpoint.class);
        assertEquals(120_000L, ann.timeout());
    }

    @Test
    void defaultSystemPromptIsEmpty() {
        AiEndpoint ann = MinimalEndpoint.class.getAnnotation(AiEndpoint.class);
        assertEquals("", ann.systemPrompt());
    }

    @Test
    void defaultSystemPromptResourceIsEmpty() {
        AiEndpoint ann = MinimalEndpoint.class.getAnnotation(AiEndpoint.class);
        assertEquals("", ann.systemPromptResource());
    }

    @Test
    void defaultConversationMemoryIsFalse() {
        AiEndpoint ann = MinimalEndpoint.class.getAnnotation(AiEndpoint.class);
        assertFalse(ann.conversationMemory());
    }

    @Test
    void defaultMaxHistoryMessages() {
        AiEndpoint ann = MinimalEndpoint.class.getAnnotation(AiEndpoint.class);
        assertEquals(20, ann.maxHistoryMessages());
    }

    @Test
    void defaultInterceptorsIsEmpty() {
        AiEndpoint ann = MinimalEndpoint.class.getAnnotation(AiEndpoint.class);
        assertEquals(0, ann.interceptors().length);
    }

    @Test
    void defaultToolsIsEmpty() {
        AiEndpoint ann = MinimalEndpoint.class.getAnnotation(AiEndpoint.class);
        assertEquals(0, ann.tools().length);
    }

    @Test
    void defaultExcludeToolsIsEmpty() {
        AiEndpoint ann = MinimalEndpoint.class.getAnnotation(AiEndpoint.class);
        assertEquals(0, ann.excludeTools().length);
    }

    @Test
    void defaultFallbackStrategyIsNone() {
        AiEndpoint ann = MinimalEndpoint.class.getAnnotation(AiEndpoint.class);
        assertEquals("NONE", ann.fallbackStrategy());
    }

    @Test
    void defaultGuardrailsIsEmpty() {
        AiEndpoint ann = MinimalEndpoint.class.getAnnotation(AiEndpoint.class);
        assertEquals(0, ann.guardrails().length);
    }

    @Test
    void defaultContextProvidersIsEmpty() {
        AiEndpoint ann = MinimalEndpoint.class.getAnnotation(AiEndpoint.class);
        assertEquals(0, ann.contextProviders().length);
    }

    @Test
    void defaultAutoDiscoverContextProvidersIsFalse() {
        AiEndpoint ann = MinimalEndpoint.class.getAnnotation(AiEndpoint.class);
        assertFalse(ann.autoDiscoverContextProviders());
    }

    @Test
    void defaultModelIsEmpty() {
        AiEndpoint ann = MinimalEndpoint.class.getAnnotation(AiEndpoint.class);
        assertEquals("", ann.model());
    }

    @Test
    void defaultRequiresIsEmpty() {
        AiEndpoint ann = MinimalEndpoint.class.getAnnotation(AiEndpoint.class);
        assertEquals(0, ann.requires().length);
    }

    @Test
    void defaultFiltersIsEmpty() {
        AiEndpoint ann = MinimalEndpoint.class.getAnnotation(AiEndpoint.class);
        assertEquals(0, ann.filters().length);
    }

    @Test
    void defaultResponseAsIsVoid() {
        AiEndpoint ann = MinimalEndpoint.class.getAnnotation(AiEndpoint.class);
        assertEquals(Void.class, ann.responseAs());
    }

    @Test
    void defaultPromptCacheIsNone() {
        AiEndpoint ann = MinimalEndpoint.class.getAnnotation(AiEndpoint.class);
        assertEquals(CacheHint.CachePolicy.NONE, ann.promptCache());
    }

    @Test
    void defaultRetryMaxRetriesIsSentinel() {
        AiEndpoint ann = MinimalEndpoint.class.getAnnotation(AiEndpoint.class);
        AiEndpoint.Retry retry = ann.retry();
        assertEquals(-1, retry.maxRetries());
        assertEquals(1000, retry.initialDelayMs());
        assertEquals(30_000, retry.maxDelayMs());
        assertEquals(2.0, retry.backoffMultiplier(), 0.001);
    }

    @Test
    void customValuesAreAccessible() {
        AiEndpoint ann = CustomEndpoint.class.getAnnotation(AiEndpoint.class);
        assertEquals("/custom", ann.path());
        assertEquals(60_000L, ann.timeout());
        assertEquals("Be concise.", ann.systemPrompt());
        assertEquals("prompts/system.md", ann.systemPromptResource());
        org.junit.jupiter.api.Assertions.assertTrue(ann.conversationMemory());
        assertEquals(50, ann.maxHistoryMessages());
        assertEquals("ROUND_ROBIN", ann.fallbackStrategy());
        org.junit.jupiter.api.Assertions.assertTrue(ann.autoDiscoverContextProviders());
        assertEquals("gpt-4o", ann.model());
    }

    @AiEndpoint(path = "/cap", requires = {AiCapability.TOOL_CALLING, AiCapability.CONVERSATION_MEMORY})
    static class CapEndpoint { }

    @Test
    void requiredCapabilitiesAreParsed() {
        AiEndpoint ann = CapEndpoint.class.getAnnotation(AiEndpoint.class);
        AiCapability[] caps = ann.requires();
        assertEquals(2, caps.length);
        assertEquals(AiCapability.TOOL_CALLING, caps[0]);
        assertEquals(AiCapability.CONVERSATION_MEMORY, caps[1]);
    }
}
