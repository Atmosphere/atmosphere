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
        var name = CoordinatorProcessor.resolveAgentName(ref, "test");
        assertEquals("worker-a", name);
    }

    @Test
    void resolveAgentNameFromValue() {
        var fleet = FullCoordinator.class.getAnnotation(Fleet.class);
        var ref = fleet.value()[1];
        var name = CoordinatorProcessor.resolveAgentName(ref, "test");
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
                () -> CoordinatorProcessor.resolveAgentName(ref, "test"));
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
                () -> CoordinatorProcessor.resolveAgentName(ref, "test"));
    }

    @Test
    void resolveAgentNameFromCoordinatorType() {
        @Coordinator(name = "nested")
        @Fleet(@AgentRef(type = CoordinatorA.class))
        class NestedCoordinator {}

        var fleet = NestedCoordinator.class.getAnnotation(Fleet.class);
        var ref = fleet.value()[0];
        var name = CoordinatorProcessor.resolveAgentName(ref, "test");
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
        var firstName = CoordinatorProcessor.resolveAgentName(fleet.value()[0], "test");
        var secondName = CoordinatorProcessor.resolveAgentName(fleet.value()[1], "test");
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

    /**
     * Regression for the 4.0.36 durable-HITL sample crash: CoordinatorProcessor's
     * bytecode must not contain any direct symbolic reference to
     * {@code org/atmosphere/mcp/...}, otherwise samples that depend on
     * {@code atmosphere-coordinator} without the optional {@code atmosphere-mcp}
     * jar crash at servlet init with a NoClassDefFoundError on
     * {@code McpRegistry$ParamEntry} before any handler is mapped and the
     * coordinator transport never comes alive.
     *
     * <p>All MCP type references must live in {@link McpCoordinatorRegistration},
     * which is loaded reflectively from CoordinatorProcessor only after
     * {@link org.atmosphere.agent.ClasspathDetector#hasMcp()} succeeds.</p>
     */
    @Test
    public void testCoordinatorProcessorBytecodeIsFreeOfMcpClassRefs() throws Exception {
        byte[] bytecode;
        try (var in = CoordinatorProcessor.class.getResourceAsStream(
                "/org/atmosphere/coordinator/processor/CoordinatorProcessor.class")) {
            assertNotNull(in, "CoordinatorProcessor.class must be on the classpath");
            bytecode = in.readAllBytes();
        }

        var ascii = new String(bytecode, java.nio.charset.StandardCharsets.US_ASCII);
        // Any slash-separated type reference to org/atmosphere/mcp/... in the
        // constant pool would force the JVM to resolve that class at link
        // time when CoordinatorProcessor is loaded — which is exactly the
        // failure mode this test pins down. String literals (e.g. the
        // fully-qualified name passed to Class.forName) use dot notation and
        // are safe.
        assertFalse(ascii.contains("org/atmosphere/mcp/"),
                "CoordinatorProcessor.class must not contain any symbolic "
                        + "reference to org/atmosphere/mcp/* — move MCP "
                        + "integration to McpCoordinatorRegistration (loaded "
                        + "reflectively). Found one in the bytecode constant pool.");
    }

    /**
     * Regression for the 4.0.36 durable-HITL sample crash: confirm the MCP
     * bridge class is resolvable by the classloader coordinator users actually
     * hit (so {@code Class.forName} in the registerMcp fallback path loads it)
     * and exposes the expected static entry point.
     */
    @Test
    public void testMcpCoordinatorRegistrationIsLoadableViaClassForName() throws Exception {
        var bridge = Class.forName(
                "org.atmosphere.coordinator.processor.McpCoordinatorRegistration",
                true, Thread.currentThread().getContextClassLoader());
        var register = bridge.getDeclaredMethod("register",
                org.atmosphere.cpr.AtmosphereFramework.class,
                String.class, String.class,
                org.atmosphere.ai.tool.ToolRegistry.class,
                String.class,
                java.util.List.class, java.util.List.class);
        assertNotNull(register, "McpCoordinatorRegistration.register must exist "
                + "with the signature CoordinatorProcessor invokes reflectively");
        assertTrue(java.lang.reflect.Modifier.isStatic(register.getModifiers()),
                "McpCoordinatorRegistration.register must be static");
    }
}
