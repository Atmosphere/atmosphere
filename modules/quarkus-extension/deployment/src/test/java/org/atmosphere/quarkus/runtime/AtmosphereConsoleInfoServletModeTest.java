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
package org.atmosphere.quarkus.runtime;

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereHandlerWrapper;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins {@code /api/console/info}'s {@code mode} for a path-template service on
 * the Quarkus path, in parity with the Spring starter
 * ({@code AtmosphereConsoleInfoEndpointModeTest}): a templated
 * {@code @ManagedService} registers only its template until the first request
 * for a concrete path, and the console mounts before that request (Mode
 * Parity, Invariant #7). Lives in the runtime package (from the deployment
 * test tree) for package-private access.
 */
class AtmosphereConsoleInfoServletModeTest {

    /** A handler outside every AI package: classified as broadcast. */
    static final class RoomHandler implements AtmosphereHandler {

        @Override
        public void onRequest(AtmosphereResource resource) {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent event) {
        }

        @Override
        public void destroy() {
        }
    }

    private static AtmosphereFramework frameworkWith(String mapping) {
        var framework = new AtmosphereFramework();
        framework.getAtmosphereHandlers().put(mapping, new AtmosphereHandlerWrapper(
                null, new RoomHandler(), mapping, framework.getAtmosphereConfig()));
        return framework;
    }

    @Test
    void pathTemplateServiceYieldsBroadcastModeBeforeAnyClientConnects() {
        var framework = frameworkWith("/atmosphere/rooms/{room}");
        assertEquals("broadcast", AtmosphereConsoleInfoServlet.detectMode(framework, "/atmosphere/rooms/general"));
    }

    @Test
    void exactPathServiceYieldsBroadcastMode() {
        var framework = frameworkWith("/atmosphere/chat");
        assertEquals("broadcast", AtmosphereConsoleInfoServlet.detectMode(framework, "/atmosphere/chat"));
    }

    @Test
    void unregisteredEndpointFallsBackToAiMode() {
        var framework = frameworkWith("/atmosphere/rooms/{room}");
        assertEquals("ai", AtmosphereConsoleInfoServlet.detectMode(framework, "/atmosphere/ai-chat"));
    }

    @Test
    void pathTemplateResolvesTheHandlerItServes() {
        // "ai" is also the answer when nothing matches, so pin the resolved handler itself.
        var served = new Object();
        Map<String, Object> handlers = Map.of("/atmosphere/classroom/{room}", served);
        assertSame(served, AtmosphereConsoleInfoServlet.templateWrapper(handlers, "/atmosphere/classroom/math"));
        assertNull(AtmosphereConsoleInfoServlet.templateWrapper(handlers, "/atmosphere/classroom/math/admin"));
    }
}
