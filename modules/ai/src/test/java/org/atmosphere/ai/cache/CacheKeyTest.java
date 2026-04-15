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
package org.atmosphere.ai.cache;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.Content;
import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CacheKeyTest {

    // 15-arg shim: message, systemPrompt, model, agentId, sessionId,
    // userId, conversationId, tools, toolTarget, memory,
    // contextProviders, metadata, history, responseType, approvalStrategy
    private AgentExecutionContext ctx(String model, String systemPrompt,
                                      String message) {
        return new AgentExecutionContext(
                message, systemPrompt, model,
                null, "session", "user", "conv",
                null, null, null, null,
                null, null, null, null);
    }

    @Test
    void computeReturnsDeterministicHex() {
        var key1 = CacheKey.compute(ctx("gpt-4", "sys", "hello"));
        var key2 = CacheKey.compute(ctx("gpt-4", "sys", "hello"));
        assertEquals(key1, key2);
        assertEquals(64, key1.length(), "SHA-256 hex is 64 chars");
    }

    @Test
    void differentModelProducesDifferentKey() {
        var k1 = CacheKey.compute(ctx("gpt-4", "sys", "hello"));
        var k2 = CacheKey.compute(ctx("gpt-3.5", "sys", "hello"));
        assertNotEquals(k1, k2);
    }

    @Test
    void differentMessageProducesDifferentKey() {
        var k1 = CacheKey.compute(ctx("gpt-4", "sys", "hello"));
        var k2 = CacheKey.compute(ctx("gpt-4", "sys", "world"));
        assertNotEquals(k1, k2);
    }

    @Test
    void differentSystemPromptProducesDifferentKey() {
        var k1 = CacheKey.compute(ctx("gpt-4", "sys1", "hello"));
        var k2 = CacheKey.compute(ctx("gpt-4", "sys2", "hello"));
        assertNotEquals(k1, k2);
    }

    @Test
    void nullFieldsDoNotThrow() {
        var key = CacheKey.compute(ctx(null, null, null));
        assertNotNull(key);
        assertEquals(64, key.length());
    }

    @Test
    void historyAffectsKey() {
        var history = List.of(
                new ChatMessage("user", "prev question", null));

        var ctxNoHistory = new AgentExecutionContext(
                "msg", "sys", "gpt-4",
                null, "s", "u", "c",
                null, null, null, null,
                null, null, null, null);

        var ctxWithHistory = new AgentExecutionContext(
                "msg", "sys", "gpt-4",
                null, "s", "u", "c",
                null, null, null, null,
                null, history, null, null);

        assertNotEquals(CacheKey.compute(ctxNoHistory),
                CacheKey.compute(ctxWithHistory));
    }

    @Test
    void toolNamesAffectKey() {
        var tool = ToolDefinition.builder("weather", "Get weather")
                .executor(ctx -> "sunny")
                .build();

        var ctxNoTools = ctx("gpt-4", "sys", "msg");
        var ctxWithTools = new AgentExecutionContext(
                "msg", "sys", "gpt-4",
                null, "s", "u", "c",
                List.of(tool), null, null, null,
                null, null, null, null);

        assertNotEquals(CacheKey.compute(ctxNoTools),
                CacheKey.compute(ctxWithTools));
    }

    @Test
    void imageParts_mimeAndLengthAffectKey() {
        byte[] data1 = new byte[]{1, 2, 3};
        byte[] data2 = new byte[]{1, 2, 3, 4, 5};
        var img1 = Content.image(data1, "image/png");
        var img2 = Content.image(data2, "image/png");

        // 18-arg shim includes parts at position 17
        var ctx1 = new AgentExecutionContext(
                "msg", "sys", "gpt-4",
                null, "s", "u", "c",
                null, null, null, null,
                null, null, null, null,
                null, List.of(img1), null);
        var ctx2 = new AgentExecutionContext(
                "msg", "sys", "gpt-4",
                null, "s", "u", "c",
                null, null, null, null,
                null, null, null, null,
                null, List.of(img2), null);

        assertNotEquals(CacheKey.compute(ctx1),
                CacheKey.compute(ctx2));
    }

    @Test
    void fileParts_contentDistinguished() {
        byte[] data1 = new byte[]{1, 2, 3};
        byte[] data2 = new byte[]{4, 5, 6};
        var f1 = Content.file(data1, "text/csv", "a.csv");
        var f2 = Content.file(data2, "text/csv", "a.csv");

        var ctx1 = new AgentExecutionContext(
                "msg", "sys", "gpt-4",
                null, "s", "u", "c",
                null, null, null, null,
                null, null, null, null,
                null, List.of(f1), null);
        var ctx2 = new AgentExecutionContext(
                "msg", "sys", "gpt-4",
                null, "s", "u", "c",
                null, null, null, null,
                null, null, null, null,
                null, List.of(f2), null);

        assertNotEquals(CacheKey.compute(ctx1),
                CacheKey.compute(ctx2),
                "File parts with different content should differ");
    }

    @Test
    void responseTypeAffectsKey() {
        var ctxNoType = ctx("gpt-4", "sys", "msg");
        var ctxWithType = new AgentExecutionContext(
                "msg", "sys", "gpt-4",
                null, "s", "u", "c",
                null, null, null, null,
                null, null, String.class, null);

        assertNotEquals(CacheKey.compute(ctxNoType),
                CacheKey.compute(ctxWithType));
    }

    @Test
    void audioParts_mimeAndLengthAffectKey() {
        byte[] data1 = new byte[]{10, 20};
        byte[] data2 = new byte[]{10, 20, 30};
        var a1 = Content.audio(data1, "audio/wav");
        var a2 = Content.audio(data2, "audio/wav");

        var ctx1 = new AgentExecutionContext(
                "msg", "sys", "gpt-4",
                null, "s", "u", "c",
                null, null, null, null,
                null, null, null, null,
                null, List.of(a1), null);
        var ctx2 = new AgentExecutionContext(
                "msg", "sys", "gpt-4",
                null, "s", "u", "c",
                null, null, null, null,
                null, null, null, null,
                null, List.of(a2), null);

        assertNotEquals(CacheKey.compute(ctx1),
                CacheKey.compute(ctx2));
    }
}
