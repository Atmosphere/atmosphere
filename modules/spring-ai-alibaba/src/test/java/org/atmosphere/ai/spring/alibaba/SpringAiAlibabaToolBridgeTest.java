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
package org.atmosphere.ai.spring.alibaba;

import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpringAiAlibabaToolBridgeTest {

    @Test
    void callbackRoutesThroughAtmosphereToolDefinition() {
        var tool = ToolDefinition.builder("greet", "Say hello")
                .parameter("name", "Name to greet", "string")
                .executor(args -> "hello " + args.get("name"))
                .build();

        var callback = SpringAiAlibabaToolBridge.toToolCallbacks(
                List.of(tool), null, null, List.of(), null).get(0);

        assertEquals("greet", callback.getToolDefinition().name());
        assertEquals("hello Ada", callback.call("{\"name\":\"Ada\"}"));
    }
}
