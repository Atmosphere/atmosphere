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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatMessageTest {

    @Test
    void systemFactory() {
        var msg = ChatMessage.system("You are a helper");
        assertEquals("system", msg.role());
        assertEquals("You are a helper", msg.content());
        assertNull(msg.toolCallId());
    }

    @Test
    void userFactory() {
        var msg = ChatMessage.user("Hello");
        assertEquals("user", msg.role());
        assertEquals("Hello", msg.content());
        assertNull(msg.toolCallId());
    }

    @Test
    void assistantFactory() {
        var msg = ChatMessage.assistant("Hi there");
        assertEquals("assistant", msg.role());
        assertEquals("Hi there", msg.content());
        assertNull(msg.toolCallId());
    }

    @Test
    void toolFactory() {
        var msg = ChatMessage.tool("22°C", "call-1");
        assertEquals("tool", msg.role());
        assertEquals("22°C", msg.content());
        assertEquals("call-1", msg.toolCallId());
        assertNull(msg.name(),
                "legacy two-arg tool() preserves null name for callers predating the field");
    }

    @Test
    void toolFactoryWithName() {
        // Carries the function name so downstream serialization can
        // populate the optional OpenAI `name` field — required by Gemini's
        // v1beta/openai compatibility layer.
        var msg = ChatMessage.tool("22°C", "call-1", "get_weather");
        assertEquals("tool", msg.role());
        assertEquals("22°C", msg.content());
        assertEquals("call-1", msg.toolCallId());
        assertEquals("get_weather", msg.name());
    }

    @Test
    void twoArgConstructorNullsToolCallIdAndName() {
        var msg = new ChatMessage("user", "test");
        assertNull(msg.toolCallId());
        assertNull(msg.name());
    }

    @Test
    void threeArgConstructorPreservesExistingContract() {
        // Existing callers pass (role, content, toolCallId) — the new
        // name field defaults to null so no downstream behavior changes.
        var msg = new ChatMessage("tool", "result", "call-1");
        assertEquals("tool", msg.role());
        assertEquals("result", msg.content());
        assertEquals("call-1", msg.toolCallId());
        assertNull(msg.name());
    }

    @Test
    void nonToolFactoriesAlwaysReturnNullName() {
        assertNull(ChatMessage.system("x").name());
        assertNull(ChatMessage.user("x").name());
        assertNull(ChatMessage.assistant("x").name());
    }
}
