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

import org.atmosphere.ai.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiRequestTest {

    @Test
    void singleArgConstructorSetsDefaults() {
        var req = new AiRequest("hello");
        assertEquals("hello", req.message());
        assertEquals("", req.systemPrompt());
        assertNull(req.model());
        assertNull(req.userId());
        assertNull(req.sessionId());
        assertNull(req.agentId());
        assertNull(req.conversationId());
        assertTrue(req.metadata().isEmpty());
        assertTrue(req.history().isEmpty());
    }

    @Test
    void twoArgConstructorSetsSystemPrompt() {
        var req = new AiRequest("msg", "be helpful");
        assertEquals("msg", req.message());
        assertEquals("be helpful", req.systemPrompt());
    }

    @Test
    void withMessageReturnsNewInstance() {
        var req = new AiRequest("original", "sys");
        var updated = req.withMessage("changed");
        assertEquals("changed", updated.message());
        assertEquals("sys", updated.systemPrompt());
    }

    @Test
    void withSystemPromptReturnsNewInstance() {
        var req = new AiRequest("msg");
        var updated = req.withSystemPrompt("new sys");
        assertEquals("new sys", updated.systemPrompt());
        assertEquals("msg", updated.message());
    }

    @Test
    void withModelSetsModel() {
        var req = new AiRequest("msg").withModel("gpt-4");
        assertEquals("gpt-4", req.model());
    }

    @Test
    void withUserIdSetsUserId() {
        var req = new AiRequest("msg").withUserId("user123");
        assertEquals("user123", req.userId());
    }

    @Test
    void withSessionIdSetsSessionId() {
        var req = new AiRequest("msg").withSessionId("sess-1");
        assertEquals("sess-1", req.sessionId());
    }

    @Test
    void withAgentIdSetsAgentId() {
        var req = new AiRequest("msg").withAgentId("agent-1");
        assertEquals("agent-1", req.agentId());
    }

    @Test
    void withConversationIdSetsConversationId() {
        var req = new AiRequest("msg").withConversationId("conv-1");
        assertEquals("conv-1", req.conversationId());
    }

    @Test
    void withMetadataMergesIntoExisting() {
        var req = new AiRequest("msg").withMetadata(Map.of("key1", "val1"));
        var updated = req.withMetadata(Map.of("key2", "val2"));
        assertEquals("val1", updated.metadata().get("key1"));
        assertEquals("val2", updated.metadata().get("key2"));
    }

    @Test
    void withHistorySetsHistory() {
        var history = List.of(ChatMessage.user("hi"), ChatMessage.assistant("hello"));
        var req = new AiRequest("msg").withHistory(history);
        assertEquals(2, req.history().size());
    }

    @Test
    void toolsReturnsEmptyByDefault() {
        var req = new AiRequest("msg");
        assertTrue(req.tools().isEmpty());
    }

    @Test
    void responseTypeReturnsNullByDefault() {
        var req = new AiRequest("msg");
        assertNull(req.responseType());
    }

    @Test
    void withResponseTypeSetsType() {
        var req = new AiRequest("msg").withResponseType(String.class);
        assertEquals(String.class, req.responseType());
    }

    @Test
    void metadataIsUnmodifiable() {
        var req = new AiRequest("msg");
        assertThrows(UnsupportedOperationException.class,
                () -> req.metadata().put("key", "val"));
    }

    @Test
    void chainedWithMethodsPreservePreviousValues() {
        var req = new AiRequest("msg")
                .withModel("gpt-4")
                .withUserId("user1")
                .withSessionId("sess1")
                .withAgentId("agent1");
        assertEquals("msg", req.message());
        assertEquals("gpt-4", req.model());
        assertEquals("user1", req.userId());
        assertEquals("sess1", req.sessionId());
        assertEquals("agent1", req.agentId());
    }
}
