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

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpMethodTest {

    @Test
    void lifecycleConstants() {
        assertEquals("initialize", McpMethod.INITIALIZE);
        assertEquals("notifications/initialized", McpMethod.INITIALIZED);
        assertEquals("ping", McpMethod.PING);
    }

    @Test
    void toolsConstants() {
        assertEquals("tools/list", McpMethod.TOOLS_LIST);
        assertEquals("tools/call", McpMethod.TOOLS_CALL);
    }

    @Test
    void resourcesConstants() {
        assertEquals("resources/list", McpMethod.RESOURCES_LIST);
        assertEquals("resources/read", McpMethod.RESOURCES_READ);
        assertEquals("resources/subscribe", McpMethod.RESOURCES_SUBSCRIBE);
        assertEquals("resources/unsubscribe", McpMethod.RESOURCES_UNSUBSCRIBE);
    }

    @Test
    void promptsConstants() {
        assertEquals("prompts/list", McpMethod.PROMPTS_LIST);
        assertEquals("prompts/get", McpMethod.PROMPTS_GET);
    }

    @Test
    void notificationConstants() {
        assertEquals("notifications/progress", McpMethod.PROGRESS);
        assertEquals("notifications/cancelled", McpMethod.CANCELLED);
        assertEquals("notifications/resources/updated", McpMethod.RESOURCES_UPDATED);
    }

    @Test
    void constructorIsPrivate() throws Exception {
        var constructor = McpMethod.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()),
                "McpMethod constructor should be private");
    }
}
