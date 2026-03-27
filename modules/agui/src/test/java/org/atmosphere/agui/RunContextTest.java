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
package org.atmosphere.agui;

import tools.jackson.databind.ObjectMapper;
import org.atmosphere.agui.runtime.RunContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RunContextTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserialization() throws Exception {
        var json = "{\"threadId\":\"t1\",\"runId\":\"r1\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";
        var ctx = mapper.readValue(json, RunContext.class);
        assertEquals("t1", ctx.threadId());
        assertEquals("r1", ctx.runId());
        assertEquals(1, ctx.messages().size());
    }

    @Test
    void lastUserMessage() {
        var messages = List.<Map<String, Object>>of(
                Map.of("role", "user", "content", "hello"),
                Map.of("role", "assistant", "content", "hi"),
                Map.of("role", "user", "content", "how are you?")
        );
        var ctx = new RunContext("t1", "r1", messages, Map.of(), Map.of(), List.of());
        assertEquals("how are you?", ctx.lastUserMessage());
    }

    @Test
    void lastUserMessageWhenEmpty() {
        var ctx = new RunContext("t1", "r1", List.of(), Map.of(), Map.of(), List.of());
        assertEquals("", ctx.lastUserMessage());
    }

    @Test
    void nullFieldsDefaultToEmpty() {
        var ctx = new RunContext("t1", "r1", null, null, null, null);
        assertNotNull(ctx.messages());
        assertNotNull(ctx.state());
        assertNotNull(ctx.forwardedProps());
        assertNotNull(ctx.tools());
    }
}
