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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatCompletionRequestTest {

    @Test
    void factoryMethodOfCreatesSinglePromptRequest() {
        var request = ChatCompletionRequest.of("gpt-4", "Hello");

        assertEquals("gpt-4", request.model());
        assertEquals(1, request.messages().size());
        assertEquals("Hello", request.messages().get(0).content());
        assertEquals(0.7, request.temperature(), 0.001);
        assertEquals(2048, request.maxStreamingTexts());
        assertFalse(request.jsonMode());
        assertTrue(request.tools().isEmpty());
        assertNull(request.conversationId());
        assertNull(request.approvalStrategy());
        assertTrue(request.parts().isEmpty());
        assertTrue(request.listeners().isEmpty());
        assertFalse(request.cacheHint().enabled());
    }

    @Test
    void builderCreatesRequestWithDefaults() {
        var request = ChatCompletionRequest.builder("gpt-4")
                .user("Hello")
                .build();

        assertEquals("gpt-4", request.model());
        assertEquals(1, request.messages().size());
        assertEquals(0.7, request.temperature(), 0.001);
        assertEquals(2048, request.maxStreamingTexts());
        assertFalse(request.jsonMode());
    }

    @Test
    void builderSetsAllFields() {
        var request = ChatCompletionRequest.builder("gpt-4")
                .system("You are helpful")
                .user("Hello")
                .assistant("Hi there")
                .temperature(0.5)
                .maxStreamingTexts(1024)
                .jsonMode(true)
                .conversationId("conv-1")
                .cacheHint(CacheHint.conservative())
                .build();

        assertEquals("gpt-4", request.model());
        assertEquals(3, request.messages().size());
        assertEquals(0.5, request.temperature(), 0.001);
        assertEquals(1024, request.maxStreamingTexts());
        assertTrue(request.jsonMode());
        assertEquals("conv-1", request.conversationId());
        assertTrue(request.cacheHint().enabled());
    }

    @Test
    void builderMessageAddsArbitraryMessage() {
        var msg = ChatMessage.user("custom");
        var request = ChatCompletionRequest.builder("gpt-4")
                .message(msg)
                .build();

        assertEquals(1, request.messages().size());
        assertEquals("custom", request.messages().get(0).content());
    }

    @Test
    void canonicalConstructorDefensiveCopiesTools() {
        var tools = new ArrayList<org.atmosphere.ai.tool.ToolDefinition>();
        var request = new ChatCompletionRequest("gpt-4", List.of(), 0.7, 2048,
                false, tools, null);

        // mutating the original list should not affect the record
        tools.add(null);
        assertTrue(request.tools().isEmpty());
    }

    @Test
    void canonicalConstructorDefaultsNullToolsToEmptyList() {
        var request = new ChatCompletionRequest("gpt-4", List.of(), 0.7, 2048,
                false, null, null);

        assertNotNull(request.tools());
        assertTrue(request.tools().isEmpty());
    }

    @Test
    void canonicalConstructorDefaultsNullPartsToEmptyList() {
        var request = new ChatCompletionRequest("gpt-4", List.of(), 0.7, 2048,
                false, List.of(), null, null, null);

        assertNotNull(request.parts());
        assertTrue(request.parts().isEmpty());
    }

    @Test
    void canonicalConstructorDefaultsNullListenersToEmptyList() {
        var request = new ChatCompletionRequest("gpt-4", List.of(), 0.7, 2048,
                false, List.of(), null, null, List.of(), null);

        assertNotNull(request.listeners());
        assertTrue(request.listeners().isEmpty());
    }

    @Test
    void canonicalConstructorDefaultsNullCacheHintToNone() {
        var request = new ChatCompletionRequest("gpt-4", List.of(), 0.7, 2048,
                false, List.of(), null, null, List.of(), List.of(), null);

        assertNotNull(request.cacheHint());
        assertFalse(request.cacheHint().enabled());
    }

    @Test
    void builderBuildReturnsCopyOfMessages() {
        var builder = ChatCompletionRequest.builder("gpt-4")
                .user("msg1");
        var request = builder.build();

        // adding more messages to the builder should not affect the built request
        builder.user("msg2");
        var request2 = builder.build();

        assertEquals(1, request.messages().size());
        assertEquals(2, request2.messages().size());
    }

    @Test
    void builderNullPartsDefaultsToEmptyList() {
        var request = ChatCompletionRequest.builder("gpt-4")
                .user("Hello")
                .parts(null)
                .build();

        assertNotNull(request.parts());
        assertTrue(request.parts().isEmpty());
    }

    @Test
    void builderNullListenersDefaultsToEmptyList() {
        var request = ChatCompletionRequest.builder("gpt-4")
                .user("Hello")
                .listeners(null)
                .build();

        assertNotNull(request.listeners());
        assertTrue(request.listeners().isEmpty());
    }

    @Test
    void builderNullCacheHintDefaultsToNone() {
        var request = ChatCompletionRequest.builder("gpt-4")
                .user("Hello")
                .cacheHint(null)
                .build();

        assertNotNull(request.cacheHint());
        assertFalse(request.cacheHint().enabled());
    }

    @Test
    void retryPolicyIsNullByDefault() {
        var request = ChatCompletionRequest.of("gpt-4", "Hello");
        assertNull(request.retryPolicy());
    }

    @Test
    void shimConstructor6ArgSetsDefaults() {
        var request = new ChatCompletionRequest("gpt-4", List.of(), 0.7, 2048,
                false, List.of());

        assertNull(request.conversationId());
        assertNull(request.approvalStrategy());
        assertTrue(request.parts().isEmpty());
        assertTrue(request.listeners().isEmpty());
        assertFalse(request.cacheHint().enabled());
    }
}
