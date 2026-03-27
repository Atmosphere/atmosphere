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
package org.atmosphere.coordinator.fleet;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class AgentCallTest {

    @Test
    void accessors() {
        var call = new AgentCall("agent", "skill", Map.<String, Object>of("q", "test"));
        assertEquals("agent", call.agentName());
        assertEquals("skill", call.skill());
        assertEquals("test", call.args().get("q"));
    }

    @Test
    void argsDefensiveCopy() {
        var original = new HashMap<String, Object>();
        original.put("key", "val");
        var call = new AgentCall("a", "s", original);
        original.put("new", "should not appear");
        assertFalse(call.args().containsKey("new"));
    }

    @Test
    void nullArgsDefaultsToEmpty() {
        var call = new AgentCall("a", "s", null);
        assertNotNull(call.args());
        assertTrue(call.args().isEmpty());
    }

    @Test
    void argsAreImmutable() {
        var call = new AgentCall("a", "s", Map.<String, Object>of("k", "v"));
        assertThrows(UnsupportedOperationException.class,
                () -> call.args().put("new", "val"));
    }
}
