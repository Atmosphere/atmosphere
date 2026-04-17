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
package org.atmosphere.mcp.bridge;

import org.atmosphere.ai.bridge.ProtocolBridge;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpProtocolBridgeTest {

    @Test
    void inactiveWhenFrameworkIsFresh() {
        var framework = new AtmosphereFramework();
        var bridge = new McpProtocolBridge(framework);
        assertEquals("mcp", bridge.name());
        assertEquals(ProtocolBridge.Kind.WIRE, bridge.kind());
        assertFalse(bridge.isActive());
        assertTrue(bridge.agentPaths().isEmpty());
    }

    @Test
    void surfacesMcpSubPathsOnly() throws Exception {
        var framework = new AtmosphereFramework();
        framework.init();
        framework.addAtmosphereHandler("/atmosphere/agent/pierre", new NoopHandler());
        framework.addAtmosphereHandler("/atmosphere/agent/pierre/mcp", new NoopHandler());
        framework.addAtmosphereHandler("/atmosphere/agent/pierre/a2a", new NoopHandler());
        framework.addAtmosphereHandler("/atmosphere/agent/sophia/mcp", new NoopHandler());

        var bridge = new McpProtocolBridge(framework);
        var paths = bridge.agentPaths();
        assertEquals(2, paths.size());
        assertTrue(paths.contains("/atmosphere/agent/pierre/mcp"));
        assertTrue(paths.contains("/atmosphere/agent/sophia/mcp"));
        assertTrue(bridge.isActive());

        framework.destroy();
    }

    @Test
    void rejectsNullFramework() {
        assertThrows(NullPointerException.class, () -> new McpProtocolBridge(null));
    }

    private static final class NoopHandler implements AtmosphereHandler {
        @Override public void onRequest(org.atmosphere.cpr.AtmosphereResource r) { }
        @Override public void onStateChange(org.atmosphere.cpr.AtmosphereResourceEvent e) { }
        @Override public void destroy() { }
    }
}
