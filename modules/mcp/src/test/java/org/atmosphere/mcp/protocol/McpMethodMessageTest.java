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

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpMethodMessageTest {

    // --- McpMethod constants ---

    @Test
    void initializeConstant() {
        assertEquals("initialize", McpMethod.INITIALIZE);
    }

    @Test
    void initializedConstant() {
        assertEquals("notifications/initialized", McpMethod.INITIALIZED);
    }

    @Test
    void pingConstant() {
        assertEquals("ping", McpMethod.PING);
    }

    @Test
    void toolsListConstant() {
        assertEquals("tools/list", McpMethod.TOOLS_LIST);
    }

    @Test
    void toolsCallConstant() {
        assertEquals("tools/call", McpMethod.TOOLS_CALL);
    }

    @Test
    void resourcesListConstant() {
        assertEquals("resources/list", McpMethod.RESOURCES_LIST);
    }

    @Test
    void resourcesReadConstant() {
        assertEquals("resources/read", McpMethod.RESOURCES_READ);
    }

    @Test
    void resourcesSubscribeConstant() {
        assertEquals("resources/subscribe", McpMethod.RESOURCES_SUBSCRIBE);
    }

    @Test
    void resourcesUnsubscribeConstant() {
        assertEquals("resources/unsubscribe", McpMethod.RESOURCES_UNSUBSCRIBE);
    }

    @Test
    void promptsListConstant() {
        assertEquals("prompts/list", McpMethod.PROMPTS_LIST);
    }

    @Test
    void promptsGetConstant() {
        assertEquals("prompts/get", McpMethod.PROMPTS_GET);
    }

    @Test
    void progressConstant() {
        assertEquals("notifications/progress", McpMethod.PROGRESS);
    }

    @Test
    void cancelledConstant() {
        assertEquals("notifications/cancelled", McpMethod.CANCELLED);
    }

    @Test
    void resourcesUpdatedConstant() {
        assertEquals("notifications/resources/updated", McpMethod.RESOURCES_UPDATED);
    }

    // --- McpMessage ---

    @Test
    void systemMessageHasCorrectRole() {
        var msg = McpMessage.system("you are a helper");
        assertEquals("system", msg.role());
        assertEquals("text", msg.content().get("type"));
        assertEquals("you are a helper", msg.content().get("text"));
    }

    @Test
    void userMessageHasCorrectRole() {
        var msg = McpMessage.user("hello");
        assertEquals("user", msg.role());
        assertEquals("text", msg.content().get("type"));
        assertEquals("hello", msg.content().get("text"));
    }

    @Test
    void messageContentIsImmutable() {
        var msg = McpMessage.user("test");
        assertEquals(2, msg.content().size());
    }
}
