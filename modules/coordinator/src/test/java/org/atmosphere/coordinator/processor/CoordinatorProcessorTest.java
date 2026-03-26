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
package org.atmosphere.coordinator.processor;

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.coordinator.annotation.AgentRef;
import org.atmosphere.coordinator.annotation.Coordinator;
import org.atmosphere.coordinator.annotation.Fleet;
import org.atmosphere.coordinator.fleet.AgentFleet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CoordinatorProcessorTest {

    @Agent(name = "worker-a")
    static class WorkerAgentA {}

    @Agent(name = "worker-b")
    static class WorkerAgentB {}

    static class PlainClass {}

    @Coordinator(name = "coord-a")
    @Fleet(@AgentRef(type = WorkerAgentA.class))
    static class CoordinatorA {}

    @Coordinator(name = "test-coord", description = "Test", version = "2.0.0")
    @Fleet({
            @AgentRef(type = WorkerAgentA.class),
            @AgentRef(value = "remote-agent", version = "1.5.0", required = false, weight = 3)
    })
    static class FullCoordinator {
        @Prompt
        public void onPrompt(String message, AgentFleet fleet, StreamingSession session) {
        }
    }

    @Test
    void coordinatorAnnotationAttributes() {
        var ann = FullCoordinator.class.getAnnotation(Coordinator.class);
        assertNotNull(ann);
        assertEquals("test-coord", ann.name());
        assertEquals("Test", ann.description());
        assertEquals("2.0.0", ann.version());
    }

    @Test
    void fleetParsing() {
        var fleet = FullCoordinator.class.getAnnotation(Fleet.class);
        assertNotNull(fleet);
        assertEquals(2, fleet.value().length);
    }

    @Test
    void resolveAgentNameFromType() {
        var fleet = CoordinatorA.class.getAnnotation(Fleet.class);
        var ref = fleet.value()[0];
        var name = CoordinatorProcessor.resolveAgentName(ref);
        assertEquals("worker-a", name);
    }

    @Test
    void resolveAgentNameFromValue() {
        var fleet = FullCoordinator.class.getAnnotation(Fleet.class);
        var ref = fleet.value()[1];
        var name = CoordinatorProcessor.resolveAgentName(ref);
        assertEquals("remote-agent", name);
    }

    @Test
    void resolveAgentNameTypeLacksAnnotation() {
        // Create a ref with a type that doesn't have @Agent
        // We can test this via the Fleet annotation on a specially crafted class
        @Coordinator(name = "bad")
        @Fleet(@AgentRef(type = PlainClass.class))
        class BadCoordinator {}

        var fleet = BadCoordinator.class.getAnnotation(Fleet.class);
        var ref = fleet.value()[0];
        assertThrows(IllegalStateException.class,
                () -> CoordinatorProcessor.resolveAgentName(ref));
    }

    @Test
    void resolveAgentNameNeitherValueNorType() {
        // AgentRef with empty value and void type (defaults)
        @Coordinator(name = "bad2")
        @Fleet(@AgentRef)
        class BadCoordinator2 {}

        var fleet = BadCoordinator2.class.getAnnotation(Fleet.class);
        var ref = fleet.value()[0];
        assertThrows(IllegalStateException.class,
                () -> CoordinatorProcessor.resolveAgentName(ref));
    }

    @Test
    void resolveAgentNameFromCoordinatorType() {
        @Coordinator(name = "nested")
        @Fleet(@AgentRef(type = CoordinatorA.class))
        class NestedCoordinator {}

        var fleet = NestedCoordinator.class.getAnnotation(Fleet.class);
        var ref = fleet.value()[0];
        var name = CoordinatorProcessor.resolveAgentName(ref);
        assertEquals("coord-a", name);
    }

    @Coordinator(name = "dup-coord")
    @Fleet({
            @AgentRef(type = WorkerAgentA.class),
            @AgentRef(type = WorkerAgentA.class)
    })
    static class DuplicateFleetCoordinator {}

    @Coordinator(name = "no-fleet-coord")
    static class NoFleetCoordinator {}

    @Test
    void duplicateAgentNameInFleetDetected() {
        var fleet = DuplicateFleetCoordinator.class.getAnnotation(Fleet.class);
        assertNotNull(fleet);
        // Both refs resolve to the same agent name "worker-a"
        var firstName = CoordinatorProcessor.resolveAgentName(fleet.value()[0]);
        var secondName = CoordinatorProcessor.resolveAgentName(fleet.value()[1]);
        assertEquals(firstName, secondName,
                "Both refs should resolve to the same name to trigger duplication");
    }

    @Test
    void missingFleetAnnotation() {
        var coordinator = NoFleetCoordinator.class.getAnnotation(Coordinator.class);
        assertNotNull(coordinator, "@Coordinator should be present");
        var fleet = NoFleetCoordinator.class.getAnnotation(Fleet.class);
        assertNull(fleet, "@Fleet should not be present");
    }

    @Test
    void promptMethodDetected() {
        var hasPrompt = false;
        for (var method : FullCoordinator.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Prompt.class)) {
                hasPrompt = true;
                assertEquals(3, method.getParameterCount());
                assertEquals(String.class, method.getParameterTypes()[0]);
                assertEquals(AgentFleet.class, method.getParameterTypes()[1]);
                assertEquals(StreamingSession.class, method.getParameterTypes()[2]);
            }
        }
        assertTrue(hasPrompt);
    }
}
