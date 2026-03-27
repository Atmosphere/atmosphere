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
package org.atmosphere.coordinator.test;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StubAgentTransportTest {

    @Test
    void predicateMatching() {
        var transport = StubAgentTransport.builder()
                .when("weather", "Sunny 72F")
                .when("news", "No news today")
                .defaultResponse("I don't know")
                .build();

        var result = transport.send("test", "weather", Map.of("q", "weather in Madrid"));
        assertTrue(result.success());
        assertEquals("Sunny 72F", result.text());
    }

    @Test
    void matchesBySkill() {
        var transport = StubAgentTransport.builder()
                .when("forecast", "Rainy")
                .build();

        var result = transport.send("test", "forecast", Map.of());
        assertEquals("Rainy", result.text());
    }

    @Test
    void defaultResponseWhenNoMatch() {
        var transport = StubAgentTransport.builder()
                .when("weather", "Sunny")
                .defaultResponse("fallback")
                .build();

        var result = transport.send("test", "unknown", Map.of("q", "nothing"));
        assertEquals("fallback", result.text());
    }

    @Test
    void streamDelegatesToSend() {
        var transport = StubAgentTransport.builder()
                .defaultResponse("streamed content")
                .build();

        var tokens = new ArrayList<String>();
        var completed = new boolean[]{false};
        transport.stream("test", "skill", Map.of(), tokens::add, () -> completed[0] = true);

        assertEquals(1, tokens.size());
        assertEquals("streamed content", tokens.getFirst());
        assertTrue(completed[0]);
    }

    @Test
    void unavailable() {
        var transport = StubAgentTransport.builder().unavailable().build();
        assertFalse(transport.isAvailable());
    }

    @Test
    void availableByDefault() {
        var transport = StubAgentTransport.builder().build();
        assertTrue(transport.isAvailable());
    }
}
