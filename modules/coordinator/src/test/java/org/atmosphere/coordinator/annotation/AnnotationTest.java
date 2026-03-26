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
package org.atmosphere.coordinator.annotation;

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.Prompt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AnnotationTest {

    @Agent(name = "test-worker")
    static class TestWorkerAgent {}

    static class NoAnnotationClass {}

    @Coordinator(name = "test-coord", description = "A test coordinator")
    @Fleet({
            @AgentRef(type = TestWorkerAgent.class),
            @AgentRef(value = "remote-agent", version = "2.0.0", required = false, weight = 5)
    })
    static class TestCoordinator {
        @Prompt
        public void onPrompt(String message, AgentFleet fleet, StreamingSession session) {
        }
    }

    @Coordinator(name = "minimal")
    @Fleet(@AgentRef("single"))
    static class MinimalCoordinator {}

    @Test
    void coordinatorAnnotationPresent() {
        var annotation = TestCoordinator.class.getAnnotation(Coordinator.class);
        assertNotNull(annotation);
        assertEquals("test-coord", annotation.name());
        assertEquals("A test coordinator", annotation.description());
    }

    @Test
    void coordinatorDefaults() {
        var annotation = MinimalCoordinator.class.getAnnotation(Coordinator.class);
        assertNotNull(annotation);
        assertEquals("minimal", annotation.name());
        assertEquals("", annotation.skillFile());
        assertEquals("", annotation.description());
        assertEquals("1.0.0", annotation.version());
    }

    @Test
    void fleetAnnotationPresent() {
        var fleet = TestCoordinator.class.getAnnotation(Fleet.class);
        assertNotNull(fleet);
        assertEquals(2, fleet.value().length);
    }

    @Test
    void agentRefTypeBasedDefaults() {
        var fleet = TestCoordinator.class.getAnnotation(Fleet.class);
        var ref = fleet.value()[0];
        assertEquals(TestWorkerAgent.class, ref.type());
        assertEquals("", ref.value());
        assertEquals("", ref.version());
        assertTrue(ref.required());
        assertEquals(1, ref.weight());
    }

    @Test
    void agentRefValueBased() {
        var fleet = TestCoordinator.class.getAnnotation(Fleet.class);
        var ref = fleet.value()[1];
        assertEquals("remote-agent", ref.value());
        assertEquals(void.class, ref.type());
        assertEquals("2.0.0", ref.version());
        assertFalse(ref.required());
        assertEquals(5, ref.weight());
    }

    @Test
    void singleAgentRefShorthand() {
        var fleet = MinimalCoordinator.class.getAnnotation(Fleet.class);
        assertEquals(1, fleet.value().length);
        assertEquals("single", fleet.value()[0].value());
    }
}
