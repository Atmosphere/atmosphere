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
package org.atmosphere.mcp.protocol;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class McpMessageTest {

    @Test
    void systemFactoryCreatesSystemRole() {
        var msg = McpMessage.system("You are a helper");
        assertEquals("system", msg.role());
    }

    @Test
    void userFactoryCreatesUserRole() {
        var msg = McpMessage.user("Tell me a joke");
        assertEquals("user", msg.role());
    }

    @Test
    void contentMapHasTypeAndTextKeys() {
        var msg = McpMessage.user("hello");
        assertNotNull(msg.content());
        assertEquals("text", msg.content().get("type"));
        assertEquals("hello", msg.content().get("text"));
    }

    @Test
    void systemMessageContentMatchesInput() {
        var msg = McpMessage.system("Be helpful and concise");
        assertEquals("text", msg.content().get("type"));
        assertEquals("Be helpful and concise", msg.content().get("text"));
        assertEquals(2, msg.content().size());
    }

    @Test
    void recordAccessorsWork() {
        var content = Map.of("type", "text", "text", "value");
        var msg = new McpMessage("assistant", content);
        assertEquals("assistant", msg.role());
        assertEquals(content, msg.content());
    }

    @Test
    void contentMapHasExactlyTwoEntries() {
        var msg = McpMessage.user("test");
        assertEquals(2, msg.content().size());
    }
}
