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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExecutionContextTest {

    private AgentExecutionContext minimal() {
        return new AgentExecutionContext(
                "hello", null, "gpt-4", "agent-1", "sess-1", "user-1", "conv-1",
                null, null, null, null, null, null, null, null);
    }

    @Test
    void nullCollectionsDefaultToEmpty() {
        var ctx = minimal();
        assertNotNull(ctx.tools());
        assertTrue(ctx.tools().isEmpty());
        assertNotNull(ctx.contextProviders());
        assertTrue(ctx.contextProviders().isEmpty());
        assertNotNull(ctx.metadata());
        assertTrue(ctx.metadata().isEmpty());
        assertNotNull(ctx.history());
        assertTrue(ctx.history().isEmpty());
        assertNotNull(ctx.listeners());
        assertTrue(ctx.listeners().isEmpty());
        assertNotNull(ctx.parts());
        assertTrue(ctx.parts().isEmpty());
    }

    @Test
    void collectionsAreDefensivelyCopied() {
        var tools = new ArrayList<org.atmosphere.ai.tool.ToolDefinition>();
        var meta = new HashMap<String, Object>();
        meta.put("key", "value");
        var ctx = new AgentExecutionContext(
                "msg", null, "gpt-4", "a", "s", "u", "c",
                tools, null, null, null, meta, null, null, null);

        // Modifying original should not affect context
        meta.put("new", "val");
        assertEquals(1, ctx.metadata().size());
    }

    @Test
    void immutableCollectionsThrowOnModification() {
        var ctx = minimal();
        assertThrows(UnsupportedOperationException.class, () -> ctx.tools().add(null));
        assertThrows(UnsupportedOperationException.class, () -> ctx.metadata().put("k", "v"));
        assertThrows(UnsupportedOperationException.class, () -> ctx.history().add(null));
    }

    @Test
    void withMessageCreatesNewContext() {
        var ctx = minimal();
        var updated = ctx.withMessage("new message");
        assertEquals("new message", updated.message());
        assertEquals("hello", ctx.message());
        assertEquals(ctx.agentId(), updated.agentId());
    }

    @Test
    void withSystemPromptCreatesNewContext() {
        var ctx = minimal();
        var updated = ctx.withSystemPrompt("You are helpful");
        assertEquals("You are helpful", updated.systemPrompt());
        assertNull(ctx.systemPrompt());
    }

    @Test
    void withMetadataCreatesNewContext() {
        var ctx = minimal();
        var updated = ctx.withMetadata(Map.of("key", "value"));
        assertEquals(1, updated.metadata().size());
        assertTrue(ctx.metadata().isEmpty());
    }

    @Test
    void withHistoryCreatesNewContext() {
        var ctx = minimal();
        var history = List.of(org.atmosphere.ai.llm.ChatMessage.user("hi"));
        var updated = ctx.withHistory(history);
        assertEquals(1, updated.history().size());
        assertTrue(ctx.history().isEmpty());
    }

    @Test
    void withResponseTypeCreatesNewContext() {
        var ctx = minimal();
        var updated = ctx.withResponseType(String.class);
        assertEquals(String.class, updated.responseType());
        assertNull(ctx.responseType());
    }

    @Test
    void defaultApprovalPolicyIsAnnotated() {
        var ctx = minimal();
        assertNotNull(ctx.approvalPolicy());
    }

    @Test
    void defaultRetryPolicyIsDefault() {
        var ctx = minimal();
        assertEquals(RetryPolicy.DEFAULT, ctx.retryPolicy());
    }

    @Test
    void recordAccessors() {
        var ctx = minimal();
        assertEquals("hello", ctx.message());
        assertEquals("gpt-4", ctx.model());
        assertEquals("agent-1", ctx.agentId());
        assertEquals("sess-1", ctx.sessionId());
        assertEquals("user-1", ctx.userId());
        assertEquals("conv-1", ctx.conversationId());
    }

    @Test
    void shimConstructors() {
        // 16-arg constructor
        var ctx16 = new AgentExecutionContext(
                "msg", null, "m", "a", "s", "u", "c",
                null, null, null, null, null, null, null, null, null);
        assertTrue(ctx16.parts().isEmpty());

        // 18-arg constructor
        var ctx18 = new AgentExecutionContext(
                "msg", null, "m", "a", "s", "u", "c",
                null, null, null, null, null, null, null, null, null, null, null);
        assertEquals(RetryPolicy.DEFAULT, ctx18.retryPolicy());
    }
}
