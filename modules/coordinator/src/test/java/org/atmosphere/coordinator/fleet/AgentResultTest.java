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

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class AgentResultTest {

    @Test
    void accessors() {
        var result = new AgentResult("agent", "skill", "hello",
                Map.of("key", "val"), Duration.ofMillis(100), true);
        assertEquals("agent", result.agentName());
        assertEquals("skill", result.skillId());
        assertEquals("hello", result.text());
        assertEquals("val", result.metadata().get("key"));
        assertEquals(Duration.ofMillis(100), result.duration());
        assertTrue(result.success());
    }

    @Test
    void textOrReturnsTextOnSuccess() {
        var result = new AgentResult("a", "s", "ok", Map.of(), Duration.ZERO, true);
        assertEquals("ok", result.textOr("fallback"));
    }

    @Test
    void textOrReturnsFallbackOnFailure() {
        var result = new AgentResult("a", "s", "error", Map.of(), Duration.ZERO, false);
        assertEquals("fallback", result.textOr("fallback"));
    }

    @Test
    void failureFactory() {
        var result = AgentResult.failure("agent", "skill", "boom", Duration.ofSeconds(1));
        assertEquals("agent", result.agentName());
        assertEquals("skill", result.skillId());
        assertEquals("boom", result.text());
        assertFalse(result.success());
        assertEquals(Duration.ofSeconds(1), result.duration());
    }

    @Test
    void metadataDefensiveCopy() {
        var original = new HashMap<String, Object>();
        original.put("key", "val");
        var result = new AgentResult("a", "s", "t", original, Duration.ZERO, true);
        original.put("new", "should not appear");
        assertFalse(result.metadata().containsKey("new"));
    }

    @Test
    void nullMetadataDefaultsToEmpty() {
        var result = new AgentResult("a", "s", "t", null, Duration.ZERO, true);
        assertNotNull(result.metadata());
        assertTrue(result.metadata().isEmpty());
    }
}
