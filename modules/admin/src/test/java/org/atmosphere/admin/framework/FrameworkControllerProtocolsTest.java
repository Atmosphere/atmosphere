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
package org.atmosphere.admin.framework;

import org.atmosphere.ai.bridge.ProtocolBridge;
import org.atmosphere.ai.bridge.ProtocolBridgeRegistry;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Regression (registre#22): {@code ProtocolBridgeRegistry} was a foundation
 * primitive nothing constructed — both SPI files held only comments, and
 * the coordinator comment falsely claimed a programmatic registration that
 * did not exist. The protocol processors now install their bridges into
 * the framework's shared registry, and the admin plane lists them.
 */
class FrameworkControllerProtocolsTest {

    private record FakeBridge(String name, Kind kind, boolean isActive)
            implements ProtocolBridge {
        @Override
        public java.util.List<String> agentPaths() {
            return java.util.List.of();
        }

        @Override
        public String describe() {
            return name;
        }
    }

    @Test
    void installedBridgesAreListedByTheAdminPlane() {
        var properties = new ConcurrentHashMap<String, Object>();
        var framework = Mockito.mock(AtmosphereFramework.class);
        var config = Mockito.mock(AtmosphereConfig.class);
        when(framework.getAtmosphereConfig()).thenReturn(config);
        Mockito.<Map<String, Object>>when(config.properties()).thenReturn(properties);

        // What the protocol processors do at init.
        ProtocolBridgeRegistry.install(properties,
                new FakeBridge("mcp", ProtocolBridge.Kind.IN_JVM, true));
        ProtocolBridgeRegistry.install(properties,
                new FakeBridge("a2a", ProtocolBridge.Kind.WIRE, false));

        var listed = new FrameworkController(framework).listProtocolBridges();

        assertEquals(2, listed.size(), String.valueOf(listed));
        assertTrue(listed.stream().anyMatch(b -> "mcp".equals(b.get("name"))
                        && Boolean.TRUE.equals(b.get("active"))),
                "the live protocol must be listed active: " + listed);
        assertTrue(listed.stream().anyMatch(b -> "a2a".equals(b.get("name"))
                        && Boolean.FALSE.equals(b.get("active"))));
    }

    @Test
    void reRegistrationReplacesInsteadOfDuplicating() {
        var properties = new ConcurrentHashMap<String, Object>();
        ProtocolBridgeRegistry.install(properties,
                new FakeBridge("mcp", ProtocolBridge.Kind.IN_JVM, false));
        // A second registration site for the same protocol (e.g. agent AND
        // coordinator both wiring MCP) must not double-list it.
        var registry = ProtocolBridgeRegistry.install(properties,
                new FakeBridge("mcp", ProtocolBridge.Kind.IN_JVM, true));

        assertEquals(1, registry.all().size());
        assertTrue(registry.all().get(0).isActive(), "the latest registration wins");
    }

    @Test
    void noBridgesMeansAnEmptyListing() {
        var framework = Mockito.mock(AtmosphereFramework.class);
        var config = Mockito.mock(AtmosphereConfig.class);
        when(framework.getAtmosphereConfig()).thenReturn(config);
        Mockito.<Map<String, Object>>when(config.properties())
                .thenReturn(new ConcurrentHashMap<>());

        assertTrue(new FrameworkController(framework).listProtocolBridges().isEmpty());
    }
}
