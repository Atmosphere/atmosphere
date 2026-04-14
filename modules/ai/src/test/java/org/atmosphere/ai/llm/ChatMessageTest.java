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
    }

    @Test
    void twoArgConstructorNullsToolCallId() {
        var msg = new ChatMessage("user", "test");
        assertNull(msg.toolCallId());
    }
}
