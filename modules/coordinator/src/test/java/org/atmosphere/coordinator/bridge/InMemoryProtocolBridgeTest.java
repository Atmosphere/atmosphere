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
package org.atmosphere.coordinator.bridge;

import org.atmosphere.ai.bridge.ProtocolBridge;
import org.atmosphere.ai.bridge.ProtocolBridgeRegistry;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryProtocolBridgeTest {

    @Test
    void describeReturnsNonEmpty() {
        var framework = new AtmosphereFramework();
        var bridge = new InMemoryProtocolBridge(framework);
        assertEquals("in-memory", bridge.name());
        assertEquals(ProtocolBridge.Kind.IN_JVM, bridge.kind());
        assertFalse(bridge.describe().isBlank());
        assertEquals(0, bridge.order());
    }

    @Test
    void inactiveFrameworkReportsNotActiveAndEmptyPaths() {
        // A fresh AtmosphereFramework has not been init()'d yet.
        var framework = new AtmosphereFramework();
        var bridge = new InMemoryProtocolBridge(framework);
        assertFalse(bridge.isActive());
        assertTrue(bridge.agentPaths().isEmpty());
    }

    @Test
    void initializedFrameworkExposesAgentPaths() throws Exception {
        var framework = new AtmosphereFramework();
        framework.init();
        framework.addAtmosphereHandler("/atmosphere/agent/pierre", new NoopHandler());
        framework.addAtmosphereHandler("/atmosphere/agent/pierre/mcp", new NoopHandler());
        framework.addAtmosphereHandler("/atmosphere/agent/pierre/a2a", new NoopHandler());
        framework.addAtmosphereHandler("/atmosphere/other/thing", new NoopHandler());

        var bridge = new InMemoryProtocolBridge(framework);

        assertTrue(bridge.isActive());
        var paths = bridge.agentPaths();
        assertEquals(List.of("/atmosphere/agent/pierre"), paths,
                "in-memory bridge surfaces only the terminal agent path, "
                        + "not /mcp or /a2a sub-paths which belong to wire bridges");

        framework.destroy();
    }

    @Test
    void rejectsNullFramework() {
        assertThrows(NullPointerException.class, () -> new InMemoryProtocolBridge(null));
    }

    @Test
    void registryPicksUpAddedBridge() {
        var framework = new AtmosphereFramework();
        var bridge = new InMemoryProtocolBridge(framework);
        var registry = new ProtocolBridgeRegistry(List.of(bridge));

        assertEquals(1, registry.all().size());
        assertSame(bridge, registry.byName("in-memory").orElseThrow());
        assertEquals(Optional.empty(), registry.byName("mcp"));
    }

    @Test
    void registryOrdersByOrderThenName() {
        var framework = new AtmosphereFramework();
        var inMemory = new InMemoryProtocolBridge(framework);
        var fake = new FakeWireBridge("aux", 50);
        var registry = new ProtocolBridgeRegistry(List.of(fake, inMemory));

        // InMemory has order 0, fake has order 50, so InMemory first.
        var names = registry.all().stream().map(ProtocolBridge::name).toList();
        assertEquals(List.of("in-memory", "aux"), names);
    }

    // Minimal no-op handler so addAtmosphereHandler registers a concrete instance.
    private static final class NoopHandler implements AtmosphereHandler {
        @Override
        public void onRequest(org.atmosphere.cpr.AtmosphereResource resource) {
        }

        @Override
        public void onStateChange(org.atmosphere.cpr.AtmosphereResourceEvent event) {
        }

        @Override
        public void destroy() {
        }
    }

    private static final class FakeWireBridge implements ProtocolBridge {
        private final String name;
        private final int order;

        FakeWireBridge(String name, int order) {
            this.name = name;
            this.order = order;
        }

        @Override public String name() { return name; }
        @Override public Kind kind() { return Kind.WIRE; }
        @Override public boolean isActive() { return true; }
        @Override public String describe() { return "fake " + name; }
        @Override public List<String> agentPaths() { return List.of(); }
        @Override public int order() { return order; }
    }
}
